package com.infraleap.sternmap.ui.view;

import com.infraleap.sternmap.stern.domain.VenueOnMap;
import com.infraleap.sternmap.stern.service.MachineHighScoreService;
import com.infraleap.sternmap.stern.service.SternVenueCacheService;
import com.infraleap.sternmap.ui.MapExtentFilter;
import com.infraleap.sternmap.ui.MapIcons;
import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.html.Anchor;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.html.H4;
import com.vaadin.flow.component.html.ListItem;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.html.UnorderedList;
import com.vaadin.flow.component.map.Map;
import com.vaadin.flow.component.map.configuration.Coordinate;
import com.vaadin.flow.component.map.configuration.Extent;
import com.vaadin.flow.component.map.configuration.Feature;
import com.vaadin.flow.component.map.configuration.feature.MarkerFeature;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.Scroller;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;

import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;

@Route("")
@PageTitle("Stern Pinball Near Me")
public class MapView extends HorizontalLayout {

    /** How many nearest venues to list in the sidebar before the user has interacted with the map. */
    private static final int INITIAL_SIDEBAR_LIMIT = 25;

    private final SternVenueCacheService cache;
    private final MachineHighScoreService highScoreService;

    private final Map map = new Map();
    private final VerticalLayout sidebar = new VerticalLayout();
    private final Div statusLine = new Div();
    private final VerticalLayout list = new VerticalLayout();

    /** Marker → sidebar card (when the card exists). Click on the map highlights the card. */
    private final java.util.Map<Feature, Component> markerToCard = new IdentityHashMap<>();
    /** Per-venue card so we can re-show them when re-filtering by extent. */
    private final java.util.Map<Long, Component> venueIdToCard = new java.util.HashMap<>();
    private final java.util.Map<Feature, VenueOnMap> markerToVenue = new IdentityHashMap<>();
    private Component selectedCard;
    private List<VenueOnMap> allVenues = List.of();
    private double userLat, userLon;

    public MapView(SternVenueCacheService cache, MachineHighScoreService highScoreService) {
        this.cache = cache;
        this.highScoreService = highScoreService;

        setSizeFull();
        setPadding(false);
        setSpacing(false);

        sidebar.setPadding(true);
        sidebar.setSpacing(false);
        sidebar.setWidth("380px");
        sidebar.getStyle()
                .set("background", "var(--vaadin-background-container, #f5f5f5)")
                .set("border-right", "1px solid var(--vaadin-border-color, #ddd)")
                .set("overflow", "hidden");
        sidebar.setHeightFull();

        H1 title = new H1("Stern Pinball Near Me");
        title.getStyle().set("font-size", "1.4rem").set("margin", "0 0 0.25rem 0");
        Paragraph sub = new Paragraph("Stern Insider Connected venues + Stern Army locations. "
                + "Pan/zoom the map to see who's on it.");
        sub.getStyle().set("margin", "0 0 1rem 0")
                .set("color", "var(--vaadin-text-color-secondary, #666)")
                .set("font-size", "0.85rem");

        statusLine.setText("Loading venues…");
        statusLine.getStyle().set("font-size", "0.875rem")
                .set("color", "var(--vaadin-text-color-secondary, #666)");

        list.setPadding(false);
        list.setSpacing(false);
        list.setWidthFull();
        Scroller scroller = new Scroller(list);
        scroller.setSizeFull();
        scroller.getStyle().set("margin-top", "0.75rem");

        sidebar.add(title, sub, statusLine, scroller);
        sidebar.expand(scroller);

        map.setSizeFull();
        map.setCenter(new Coordinate(10.0, 50.0));
        map.setZoom(3);

        map.addFeatureClickListener(event -> {
            Component card = markerToCard.get(event.getFeature());
            if (card != null) {
                selectCard(card);
            } else if (event.getFeature() instanceof MarkerFeature m) {
                // Marker that wasn't currently in the sidebar — pop in just that venue
                VenueOnMap v = markerToVenue.get(event.getFeature());
                if (v != null) {
                    renderSidebar(List.of(v));
                    Component c = venueIdToCard.get(v.id());
                    if (c != null) selectCard(c);
                }
                map.setCenter(m.getCoordinates());
            }
        });

        map.addViewMoveEndListener(event -> filterSidebarToExtent(event.getExtent()));

        add(sidebar, map);
        setFlexGrow(0, sidebar);
        setFlexGrow(1, map);
    }

    @Override
    protected void onAttach(AttachEvent attachEvent) {
        super.onAttach(attachEvent);
        attachEvent.getUI().getPage()
                .executeJs("return new Promise((resolve) => {"
                        + "  if (!navigator.geolocation) { resolve(''); return; }"
                        + "  navigator.geolocation.getCurrentPosition("
                        + "    p => resolve(p.coords.latitude + ',' + p.coords.longitude),"
                        + "    e => resolve('err:' + e.code + ':' + e.message),"
                        + "    {enableHighAccuracy: true, timeout: 10000, maximumAge: 60000}"
                        + "  );"
                        + "})")
                .then(String.class, this::onLocation);

        // Install zoom-aware icon scaling on the client. OpenLayers (the engine
        // underneath Vaadin Map) keeps icons at constant pixel size regardless
        // of zoom, so markers appear to shrink as the map zooms in. We wrap
        // each feature's style function with one that re-sets the icon's
        // scale based on the current view zoom, and trigger a redraw on every
        // resolution change. The wrapping is idempotent (guarded by a flag
        // on the feature) and an `addfeature` listener picks up markers added
        // later (e.g. when the venue cache finishes loading).
        attachEvent.getUI().getPage().executeJs(ZOOM_SCALE_JS);
    }

    private static final String ZOOM_SCALE_JS =
            "(() => {"
            + "  function tryInstall() {"
            + "    const mapEl = document.querySelector('vaadin-map');"
            + "    if (!mapEl || !mapEl._configuration) { setTimeout(tryInstall, 200); return; }"
            + "    const olMap = mapEl._configuration;"
            + "    const view = olMap.getView();"
            + "    const layer = olMap.getLayers().item(1);"  // background=0, features=1
            + "    if (!layer) { setTimeout(tryInstall, 200); return; }"
            + "    const src = layer.getSource();"
            + "    function factorFromZoom(z) {"
            // Linear ramp: zoom 3 → 0.10, zoom 16 → 0.35 (capped). Step ≈ 0.019/zoom.
            + "      return Math.max(0.10, Math.min(0.35, 0.10 + (z - 3) * 0.019));"
            + "    }"
            + "    function wrap(f) {"
            + "      if (f._sternWrapped) return;"
            + "      const orig = f.getStyle();"
            + "      if (typeof orig !== 'function') return;"
            + "      f.setStyle(function(feature, resolution) {"
            + "        const result = orig.call(this, feature, resolution);"
            + "        const zoom = view.getZoom();"
            + "        const factor = factorFromZoom(zoom);"
            // Hide marker text at world (0-3) and country (4-6) zoom levels —
            // labels would clutter a continent view of thousands of venues.
            + "        const showText = zoom >= 7;"
            + "        const arr = Array.isArray(result) ? result : (result ? [result] : []);"
            + "        for (const s of arr) {"
            + "          const img = s.getImage && s.getImage();"
            + "          if (img && img.setScale) img.setScale(factor);"
            + "          if (!showText) {"
            + "            const t = s.getText && s.getText();"
            + "            if (t && t.setText) t.setText('');"
            + "          }"
            + "        }"
            + "        return result;"
            + "      });"
            + "      f._sternWrapped = true;"
            + "    }"
            + "    src.getFeatures().forEach(wrap);"
            + "    if (!src._sternFeatListener) {"
            + "      src.on('addfeature', e => wrap(e.feature));"
            + "      src._sternFeatListener = true;"
            + "    }"
            + "    if (!view._sternZoomListener) {"
            + "      view.on('change:resolution', () => src.changed());"
            + "      view._sternZoomListener = true;"
            + "    }"
            + "    src.changed();"
            + "  }"
            + "  tryInstall();"
            + "})();";

    private void onLocation(String result) {
        if (result == null || result.isBlank()) {
            statusLine.setText("Geolocation unavailable in this browser. Showing the world.");
            Notification.show("Geolocation not available", 5000, Notification.Position.TOP_END)
                    .addThemeVariants(NotificationVariant.LUMO_WARNING);
            loadVenuesAndRenderMarkers();
            return;
        }
        if (result.startsWith("err:")) {
            statusLine.setText("Couldn't get your location — showing the world.");
            Notification.show("Couldn't get your location: " + result.substring(4),
                            6000, Notification.Position.TOP_END)
                    .addThemeVariants(NotificationVariant.LUMO_ERROR);
            loadVenuesAndRenderMarkers();
            return;
        }
        String[] parts = result.split(",");
        if (parts.length != 2) {
            statusLine.setText("Bad geolocation payload: " + result);
            return;
        }
        userLat = Double.parseDouble(parts[0]);
        userLon = Double.parseDouble(parts[1]);

        MarkerFeature you = new MarkerFeature(new Coordinate(userLon, userLat), MapIcons.youMarker());
        you.setText("You");
        map.getFeatureLayer().addFeature(you);

        map.setCenter(new Coordinate(userLon, userLat));
        map.setZoom(11);

        loadVenuesAndRenderMarkers();
    }

    private void loadVenuesAndRenderMarkers() {
        statusLine.setText("Loading global Stern IC + global Stern Army (per-region REST sweep)…");
        allVenues = cache.warm();
        statusLine.setText(allVenues.size() + " venues loaded — "
                + cache.getSternIcCount() + " Stern IC + "
                + cache.getSternArmyCount() + " Stern Army global ("
                + cache.getCrossFlaggedCount() + " cross-flagged on IC venues). "
                + "Pan/zoom to filter.");

        for (VenueOnMap v : allVenues) {
            // Three marker variants:
            //   IC only     → black silhouette
            //   Army only   → Uncle Sam hat
            //   Both (crossover) → hat with silhouette overlaid on the crown
            com.vaadin.flow.component.map.configuration.style.Icon icon;
            if (v.isSternIc() && v.isSternArmy()) icon = MapIcons.crossoverMarker();
            else if (v.isSternArmy())             icon = MapIcons.sternArmyMarker();
            else                                  icon = MapIcons.sternIcMarker();
            MarkerFeature marker = new MarkerFeature(new Coordinate(v.lon(), v.lat()), icon);
            marker.setText(v.name());
            map.getFeatureLayer().addFeature(marker);
            markerToVenue.put(marker, v);
        }

        // Initial sidebar: nearest INITIAL_SIDEBAR_LIMIT venues if we have a GPS fix,
        // else just the first N alphabetically.
        List<VenueOnMap> initial;
        if (userLat != 0 || userLon != 0) {
            initial = allVenues.stream()
                    .sorted(Comparator.comparingDouble(v -> haversineKm(userLat, userLon, v.lat(), v.lon())))
                    .limit(INITIAL_SIDEBAR_LIMIT)
                    .toList();
        } else {
            initial = allVenues.stream().limit(INITIAL_SIDEBAR_LIMIT).toList();
        }
        renderSidebar(initial);
    }

    /**
     * Vaadin Map reports the Extent in the user projection (EPSG:4326 by
     * default — lon/lat degrees), not the internal Web Mercator. We treat
     * minX/maxX as longitudes and minY/maxY as latitudes directly. The actual
     * inclusion logic lives in {@link MapExtentFilter} so it can be unit
     * tested.
     */
    private void filterSidebarToExtent(Extent extent) {
        if (extent == null) return;
        List<VenueOnMap> inView = MapExtentFilter.venuesInExtent(extent, allVenues);
        inView.sort(Comparator.comparingDouble(v -> haversineKm(userLat, userLon, v.lat(), v.lon())));
        renderSidebar(inView);
        long armyInView = inView.stream().filter(VenueOnMap::isSternArmy).count();
        statusLine.setText(inView.size() + " of " + allVenues.size()
                + " in view — " + cache.getSternIcCount() + " Stern IC + "
                + cache.getSternArmyCount() + " Stern Army globally ("
                + armyInView + " Stern Army in view, " + cache.getCrossFlaggedCount()
                + " cross-flagged on IC venues).");
    }

    // ---- Sidebar rendering ----

    private void renderSidebar(List<VenueOnMap> venues) {
        list.removeAll();
        markerToCard.clear();
        venueIdToCard.clear();
        selectedCard = null;
        if (venues.isEmpty()) {
            Div empty = new Div();
            empty.setText("No venues in this view. Zoom out to see more.");
            empty.getStyle().set("padding", "0.75rem")
                    .set("color", "var(--vaadin-text-color-secondary, #888)")
                    .set("font-style", "italic");
            list.add(empty);
            return;
        }
        for (VenueOnMap v : venues) {
            Component card = buildCard(v);
            venueIdToCard.put(v.id(), card);
            // Map every venue marker back to this card so map clicks work
            markerToVenue.forEach((feature, venue) -> {
                if (venue.id() == v.id() && venue.sources().equals(v.sources())) {
                    markerToCard.put(feature, card);
                }
            });
            list.add(card);
        }
    }

    private Component buildCard(VenueOnMap v) {
        Div card = new Div();
        card.getStyle()
                .set("padding", "0.75rem")
                .set("border-bottom", "1px solid var(--vaadin-border-color, #e5e5e5)")
                .set("border-left", "4px solid transparent")
                .set("cursor", "pointer")
                .set("transition", "background 120ms, border-left-color 120ms");

        H4 name = new H4(v.name());
        name.getStyle().set("margin", "0 0 0.2rem 0").set("font-size", "1rem");
        card.add(name);

        // Row 1: source + type badges (wraps if too wide for the sidebar)
        HorizontalLayout badgeRow = new HorizontalLayout();
        badgeRow.setSpacing(true);
        badgeRow.setPadding(false);
        badgeRow.getStyle().set("flex-wrap", "wrap").set("gap", "0.25rem")
                .set("margin-bottom", "0.15rem");

        if (v.isSternIc()) {
            Span ic = new Span("Stern IC");
            ic.getElement().getThemeList().add("badge");
            ic.getStyle().set("font-size", "0.7rem");
            badgeRow.add(ic);
        }
        if (v.isSternArmy()) {
            Span army = new Span("Stern Army");
            army.getElement().getThemeList().add("badge");
            army.getStyle()
                    .set("background", "#fff8e1")
                    .set("color", "#f57f17")
                    .set("font-size", "0.7rem");
            badgeRow.add(army);
        }

        if (v.type() != null && !v.type().isBlank()) {
            Span type = new Span(v.type());
            type.getElement().getThemeList().add("badge");
            type.getStyle().set("font-size", "0.7rem");
            badgeRow.add(type);
        }
        card.add(badgeRow);

        // Row 2: machine count + distance (plain text, secondary colour)
        HorizontalLayout metaRow = new HorizontalLayout();
        metaRow.setSpacing(true);
        metaRow.setPadding(false);
        metaRow.getStyle().set("flex-wrap", "wrap").set("gap", "0.5rem")
                .set("margin-bottom", "0.2rem");

        Span mCount = new Span(v.machineCount() + " machine" + (v.machineCount() == 1 ? "" : "s"));
        mCount.getStyle().set("font-size", "0.75rem")
                .set("color", "var(--vaadin-text-color-secondary, #666)");
        metaRow.add(mCount);

        if (userLat != 0 || userLon != 0) {
            double km = haversineKm(userLat, userLon, v.lat(), v.lon());
            Span dist = new Span(String.format(km < 100 ? "%.1f km" : "%.0f km", km));
            dist.getStyle().set("font-size", "0.75rem")
                    .set("color", "var(--vaadin-text-color-secondary, #666)");
            metaRow.add(dist);
        }
        card.add(metaRow);

        if (v.address() != null && !v.address().isBlank()) {
            Paragraph addr = new Paragraph(v.address());
            addr.getStyle().set("margin", "0.15rem 0").set("font-size", "0.8rem");
            card.add(addr);
        }

        if (v.machines() != null && !v.machines().isEmpty()) {
            UnorderedList machines = new UnorderedList();
            machines.getStyle().set("margin", "0.25rem 0 0 1rem").set("padding", "0")
                    .set("font-size", "0.8rem");
            for (VenueOnMap.Machine m : v.machines()) {
                machines.add(buildMachineItem(m));
            }
            card.add(machines);
        }

        if (v.websiteUrl() != null && !v.websiteUrl().isBlank()) {
            Anchor link = new Anchor(v.websiteUrl(), "Website ↗");
            link.setTarget("_blank");
            link.getStyle().set("display", "block").set("margin-top", "0.25rem")
                    .set("font-size", "0.75rem");
            card.add(link);
        }

        card.getElement().addEventListener("click", e -> {
            selectCard(card);
            map.setCenter(new Coordinate(v.lon(), v.lat()));
            map.setZoom(Math.max(map.getZoom(), 14));
        });

        return card;
    }

    /**
     * Render one machine line. The default list bullet is replaced with a
     * disclosure arrow: ▶ when collapsed, ▼ when expanded. Stern IC machines
     * are clickable to lazy-load + toggle the perpetual top-5 high scores
     * (via {@link MachineHighScoreService}). Stern-Army-only entries (no Stern
     * machine id, no score lookup possible) render with a neutral middle dot
     * and aren't clickable.
     */
    private Component buildMachineItem(VenueOnMap.Machine m) {
        ListItem item = new ListItem();
        item.getStyle().set("list-style", "none");

        Span marker = new Span();
        marker.getStyle()
                .set("display", "inline-block")
                .set("width", "1.1em")
                .set("text-align", "center")
                .set("color", "var(--vaadin-text-color-secondary, #888)");
        Span name = new Span(m.displayName());

        if (!m.hasSternId()) {
            marker.setText("·");
            item.add(marker, name);
            return item;
        }

        marker.setText("▶");
        item.add(marker, name);

        Div scoresContainer = new Div();
        scoresContainer.getStyle()
                .set("margin", "0.15rem 0 0.4rem 1.1em")
                .set("display", "none");
        item.add(scoresContainer);

        boolean[] loaded = {false};
        item.getStyle().set("cursor", "pointer");
        item.getElement().addEventListener("click", e -> {
            boolean expanded = "▼".equals(marker.getText());
            if (expanded) {
                marker.setText("▶");
                scoresContainer.getStyle().set("display", "none");
            } else {
                if (!loaded[0]) {
                    renderTopScores(scoresContainer, highScoreService.topScores(m.sternMachineId()));
                    loaded[0] = true;
                }
                marker.setText("▼");
                scoresContainer.getStyle().set("display", "block");
            }
        }).stopPropagation();

        return item;
    }

    private void renderTopScores(Div container,
                                 List<com.infraleap.sternmap.stern.domain.MachineHighScoreResponse.Entry> entries) {
        container.removeAll();
        if (entries.isEmpty()) {
            Span empty = new Span("No scores returned (anonymous lookup?).");
            empty.getStyle().set("color", "var(--vaadin-text-color-secondary, #888)")
                    .set("font-style", "italic").set("font-size", "0.75rem");
            container.add(empty);
            return;
        }
        com.vaadin.flow.component.orderedlayout.VerticalLayout list = new com.vaadin.flow.component.orderedlayout.VerticalLayout();
        list.setPadding(false);
        list.setSpacing(false);
        list.getStyle().set("gap", "0.1rem").set("font-size", "0.75rem");
        for (int i = 0; i < entries.size(); i++) {
            var e = entries.get(i);
            // Stern's leaderboard convention: slot 0 is the Grand Champion (GC),
            // the remaining four are #1..#4.
            String label = i == 0 ? "GC" : "#" + i;
            Span row = new Span(label + "  " + e.displayName() + "  " + e.scoreFormatted());
            row.getStyle().set("font-family", "var(--lumo-font-family-monospace, monospace)");
            if (i == 0) row.getStyle().set("font-weight", "600");
            list.add(row);
        }
        container.add(list);
    }

    private void selectCard(Component card) {
        if (selectedCard != null && selectedCard != card) {
            selectedCard.getElement().getStyle()
                    .set("background", "")
                    .set("border-left-color", "transparent");
        }
        card.getElement().getStyle()
                .set("background", "var(--lumo-primary-color-10pct, #e3f2fd)")
                .set("border-left-color", "var(--lumo-primary-color, #1976d2)");
        card.getElement().executeJs(
                "this.scrollIntoView({behavior:'smooth', block:'center'})");
        selectedCard = card;
    }

    // ---- Helpers ----

    private static double haversineKm(double lat1, double lon1, double lat2, double lon2) {
        double r = 6371.0;
        double phi1 = Math.toRadians(lat1), phi2 = Math.toRadians(lat2);
        double dPhi = Math.toRadians(lat2 - lat1);
        double dLambda = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dPhi / 2) * Math.sin(dPhi / 2)
                + Math.cos(phi1) * Math.cos(phi2)
                  * Math.sin(dLambda / 2) * Math.sin(dLambda / 2);
        return 2 * r * Math.asin(Math.min(1.0, Math.sqrt(a)));
    }

}
