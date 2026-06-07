package com.infraleap.sternmap.ui.view;

import com.infraleap.sternmap.stern.domain.VenueOnMap;
import com.infraleap.sternmap.stern.service.MachineHighScoreService;
import com.infraleap.sternmap.stern.service.SternVenueCacheService;
import com.infraleap.sternmap.ui.MapExtentFilter;
import com.infraleap.sternmap.ui.MapIcons;
import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.clipboard.Clipboard;
import com.vaadin.flow.component.geolocation.Geolocation;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.grid.GridVariant;
import com.vaadin.flow.component.geolocation.GeolocationError;
import com.vaadin.flow.component.geolocation.GeolocationErrorCode;
import com.vaadin.flow.component.geolocation.GeolocationOptions;
import com.vaadin.flow.component.geolocation.GeolocationPosition;
import com.vaadin.flow.component.html.Anchor;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.html.H4;
import com.vaadin.flow.component.html.ListItem;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.html.UnorderedList;
import com.vaadin.flow.component.icon.Icon;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.map.Map;
import com.vaadin.flow.component.map.configuration.Coordinate;
import com.vaadin.flow.component.map.configuration.Extent;
import com.vaadin.flow.component.map.configuration.Feature;
import com.vaadin.flow.component.map.configuration.feature.MarkerFeature;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.data.renderer.ComponentRenderer;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;

import java.time.Duration;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;

@Route("")
@PageTitle("Stern Pinball Near Me")
public class MapView extends HorizontalLayout {

    private final SternVenueCacheService cache;
    private final MachineHighScoreService highScoreService;

    private final Map map = new Map();
    private final VerticalLayout sidebar = new VerticalLayout();
    private final Div statusLine = new Div();
    /**
     * Sidebar Grid. Holds all venues sorted by distance; only the ~20 visible
     * rows are materialised as Vaadin components at any time (Grid's
     * client-side row virtualisation), so the previous "build 4797 cards"
     * cost is gone — cards are constructed lazily as the user scrolls.
     */
    private final Grid<VenueOnMap> grid = new Grid<>();

    /** Feature → venue. Used to recover the venue when the user clicks a marker. */
    private final java.util.Map<Feature, VenueOnMap> markerToVenue = new IdentityHashMap<>();
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

        // The Grid is the sidebar's scrollable content. One ComponentRenderer
        // column, no header, single-row selection. Grid's client-side row
        // virtualisation means buildCard() only runs for the ~20 rows visible
        // in the viewport at any moment — scrolling materialises more on
        // demand, so showing all 4797 entries costs no more up front than
        // showing 25 did before.
        grid.addColumn(new ComponentRenderer<>(this::buildCard))
                .setHeader((String) null)
                .setFlexGrow(1);
        grid.setSelectionMode(Grid.SelectionMode.SINGLE);
        grid.addThemeVariants(GridVariant.LUMO_NO_BORDER, GridVariant.LUMO_NO_ROW_BORDERS, GridVariant.LUMO_COMPACT);
        grid.setSizeFull();
        grid.getStyle().set("margin-top", "0.75rem");
        grid.addSelectionListener(event -> event.getFirstSelectedItem().ifPresent(v -> {
            // Selecting a row pans + zooms the map to the venue. Triggered
            // both by user click on a row and programmatically when a map
            // marker is clicked (see addFeatureClickListener below) — the
            // selectionListener handles both paths identically.
            map.setCenter(new Coordinate(v.lon(), v.lat()));
            map.setZoom(Math.max(map.getZoom(), 14));
        }));

        sidebar.add(title, sub, statusLine, grid);
        sidebar.expand(grid);

        map.setSizeFull();
        map.setCenter(new Coordinate(10.0, 50.0));
        map.setZoom(3);

        map.addFeatureClickListener(event -> {
            if (event.getFeature() instanceof MarkerFeature m) {
                VenueOnMap v = markerToVenue.get(event.getFeature());
                if (v != null) {
                    // Select + scroll-to: scrollToItem positions the row in
                    // the visible viewport, and select() highlights it. The
                    // selection listener then takes care of recentering the
                    // map on the venue.
                    grid.select(v);
                    grid.scrollToItem(v);
                } else {
                    // Defensive: marker with no associated venue (e.g. the
                    // "You" marker). Just recenter without touching the grid.
                    map.setCenter(m.getCoordinates());
                }
            }
        });

        map.addViewMoveEndListener(event -> updateInViewStatus(event.getExtent()));

        add(sidebar, map);
        setFlexGrow(0, sidebar);
        setFlexGrow(1, map);
    }

    @Override
    protected void onAttach(AttachEvent attachEvent) {
        super.onAttach(attachEvent);
        GeolocationOptions options = GeolocationOptions.builder()
                .highAccuracy(true)
                .timeout(Duration.ofSeconds(10))
                .maximumAge(Duration.ofMinutes(1))
                .build();
        Geolocation.getPosition(this::onPosition, this::onLocationError, options);

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

    private void onPosition(GeolocationPosition position) {
        userLat = position.coords().latitude();
        userLon = position.coords().longitude();

        MarkerFeature you = new MarkerFeature(new Coordinate(userLon, userLat), MapIcons.youMarker());
        you.setText("You");
        map.getFeatureLayer().addFeature(you);

        map.setCenter(new Coordinate(userLon, userLat));
        map.setZoom(11);

        loadVenuesAndRenderMarkers();
    }

    private void onLocationError(GeolocationError error) {
        GeolocationErrorCode code = error.errorCode();
        String human = switch (code) {
            case PERMISSION_DENIED -> "Location permission denied";
            case POSITION_UNAVAILABLE -> "Location unavailable";
            case TIMEOUT -> "Location request timed out";
            case UNKNOWN -> "Geolocation not available";
        };
        statusLine.setText(human + " — showing the world.");
        NotificationVariant variant = code == GeolocationErrorCode.PERMISSION_DENIED
                ? NotificationVariant.LUMO_WARNING
                : NotificationVariant.LUMO_ERROR;
        Notification.show(human, 5000, Notification.Position.TOP_END).addThemeVariants(variant);
        loadVenuesAndRenderMarkers();
    }

    private void loadVenuesAndRenderMarkers() {
        long t0 = System.nanoTime();
        statusLine.setText("Loading global Stern IC + global Stern Army (per-region REST sweep)…");
        allVenues = cache.warm();
        long tWarm = System.nanoTime();

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
        long tMarkers = System.nanoTime();

        // Populate the Grid with every venue, sorted by distance to the user
        // (or alphabetically when we don't yet have a GPS fix). Grid's row
        // virtualisation means we hand it 4797 items but only the visible
        // ~20 become Vaadin components — buildCard() runs lazily as the user
        // scrolls. The sort is stable for the lifetime of the view.
        List<VenueOnMap> ordered = (userLat != 0 || userLon != 0)
                ? allVenues.stream()
                    .sorted(Comparator.comparingDouble(v -> haversineKm(userLat, userLon, v.lat(), v.lon())))
                    .toList()
                : allVenues.stream()
                    .sorted(Comparator.comparing(VenueOnMap::name, String.CASE_INSENSITIVE_ORDER))
                    .toList();
        grid.setItems(ordered);
        long tDone = System.nanoTime();

        statusLine.setText(allVenues.size() + " venues — "
                + cache.getSternIcCount() + " Stern IC + "
                + cache.getSternArmyCount() + " Stern Army ("
                + cache.getCrossFlaggedCount() + " cross-flagged). "
                + "Click a marker to scroll the list to it.");

        System.out.printf(
                "[PERF] load: cache.warm() %.1f ms, %d markers %.1f ms, grid.setItems(%d) %.1f ms — total %.1f ms%n",
                (tWarm - t0) / 1_000_000.0,
                allVenues.size(), (tMarkers - tWarm) / 1_000_000.0,
                ordered.size(), (tDone - tMarkers) / 1_000_000.0,
                (tDone - t0) / 1_000_000.0);
    }

    /**
     * Status-line-only reaction to pan/zoom. The Grid contents are fixed once
     * loaded (all venues, sorted by distance), so the extent listener no
     * longer drives sidebar rendering. We still report "X of N in view" for
     * the user, and keep the extent value dump in the `[PERF]` line so the
     * earlier "0 of N in view" bug at certain zoom levels can be diagnosed
     * from real values. {@link MapExtentFilter} is unit-tested for the
     * EPSG:4326-degrees contract.
     */
    private void updateInViewStatus(Extent extent) {
        if (extent == null) return;
        long t0 = System.nanoTime();
        List<VenueOnMap> inView = MapExtentFilter.venuesInExtent(extent, allVenues);
        long tFilter = System.nanoTime();
        long armyInView = inView.stream().filter(VenueOnMap::isSternArmy).count();
        statusLine.setText(inView.size() + " of " + allVenues.size()
                + " in view — " + cache.getSternIcCount() + " Stern IC + "
                + cache.getSternArmyCount() + " Stern Army globally ("
                + armyInView + " Stern Army in view, " + cache.getCrossFlaggedCount()
                + " cross-flagged on IC venues).");
        long tDone = System.nanoTime();
        System.out.printf(
                "[PERF] viewMoveEnd: %d/%d in view — extent [lon %.3f..%.3f, lat %.3f..%.3f] zoom %.2f — filter %.1f ms, total %.1f ms%n",
                inView.size(), allVenues.size(),
                extent.getMinX(), extent.getMaxX(), extent.getMinY(), extent.getMaxY(),
                map.getZoom(),
                (tFilter - t0) / 1_000_000.0,
                (tDone - t0) / 1_000_000.0);
    }

    private Component buildCard(VenueOnMap v) {
        // Card chrome (border, selected-state styling) is handled by the Grid
        // row, not this Div — keep this lean so the ComponentRenderer can
        // materialise rows quickly as the user scrolls.
        Div card = new Div();
        card.getStyle().set("padding", "0.25rem 0").set("width", "100%");

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
            Span addrText = new Span(v.address());
            addrText.getStyle().set("font-size", "0.8rem").set("flex", "1");
            HorizontalLayout addrRow = new HorizontalLayout(addrText, copyButton(v.address(), "Copy address"));
            addrRow.setSpacing(false);
            addrRow.setPadding(false);
            addrRow.setAlignItems(com.vaadin.flow.component.orderedlayout.FlexComponent.Alignment.CENTER);
            addrRow.getStyle().set("gap", "0.25rem").set("margin", "0.15rem 0");
            card.add(addrRow);
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
            link.getStyle().set("font-size", "0.75rem").set("flex", "1");
            HorizontalLayout webRow = new HorizontalLayout(link, copyButton(v.websiteUrl(), "Copy URL"));
            webRow.setSpacing(false);
            webRow.setPadding(false);
            webRow.setAlignItems(com.vaadin.flow.component.orderedlayout.FlexComponent.Alignment.CENTER);
            webRow.getStyle().set("gap", "0.25rem").set("margin-top", "0.25rem");
            card.add(webRow);
        }

        // No per-card click handler: the enclosing Grid row's selection
        // listener (set up in the constructor) handles row clicks and pans
        // the map. Click-to-select-row also fires when a marker click calls
        // grid.select(v), so both paths converge on a single listener.
        return card;
    }

    /**
     * Small tertiary-inline button with the two-rectangle COPY icon that writes
     * {@code text} to the system clipboard on click via the Vaadin 25.2
     * {@link Clipboard} API. Click is stopped from bubbling to the card so the
     * map doesn't re-center.
     */
    private Button copyButton(String text, String tooltip) {
        Button btn = new Button(new Icon(VaadinIcon.COPY));
        btn.addThemeVariants(ButtonVariant.LUMO_TERTIARY_INLINE, ButtonVariant.LUMO_SMALL);
        btn.setTooltipText(tooltip);
        btn.getStyle().set("padding", "0").set("min-width", "auto").set("flex-shrink", "0");
        btn.getElement().getStyle().set("--lumo-icon-size-m", "0.95rem");
        Clipboard.onClick(btn).writeText(text);
        btn.addClickListener(e ->
                Notification.show("Copied", 1200, Notification.Position.BOTTOM_CENTER));
        btn.getElement().addEventListener("click", e -> {}).stopPropagation();
        return btn;
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
