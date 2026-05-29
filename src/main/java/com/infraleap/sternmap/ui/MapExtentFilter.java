package com.infraleap.sternmap.ui;

import com.infraleap.sternmap.stern.domain.VenueOnMap;
import com.vaadin.flow.component.map.configuration.Extent;

import java.util.ArrayList;
import java.util.List;

/**
 * Filters a venue list down to those whose coordinates fall inside a Vaadin
 * Map {@link Extent}.
 * <p>
 * <b>Projection note:</b> Vaadin Map reports the Extent in the same coordinate
 * system as the input to {@code setCenter(Coordinate)} — by default WGS84
 * (EPSG:4326), i.e. degrees of longitude/latitude. The Extent values are NOT
 * Web Mercator metres unless {@code Map.setUserProjection("EPSG:3857")} has been
 * called. This package treats {@code minX}/{@code maxX} as longitude and
 * {@code minY}/{@code maxY} as latitude directly.
 * <p>
 * Extracted to its own class so the inclusion logic has a unit test
 * ({@code MapExtentFilterTest}). Earlier MapView code wrongly assumed Web
 * Mercator metres and ran the extent through a deg-from-radians conversion,
 * which collapsed every filter to a tiny region near (0,0) — the
 * "0 of N venues in view" bug.
 */
public final class MapExtentFilter {

    private MapExtentFilter() {}

    public static List<VenueOnMap> venuesInExtent(Extent extent, List<VenueOnMap> all) {
        if (extent == null || all == null || all.isEmpty()) return List.of();
        double minLon = extent.getMinX();
        double maxLon = extent.getMaxX();
        double minLat = extent.getMinY();
        double maxLat = extent.getMaxY();
        List<VenueOnMap> result = new ArrayList<>();
        for (VenueOnMap v : all) {
            if (v.lat() >= minLat && v.lat() <= maxLat
                    && containsLongitude(minLon, maxLon, v.lon())) {
                result.add(v);
            }
        }
        return result;
    }

    /**
     * Longitude inclusion that handles antimeridian-crossing extents (where
     * {@code minLon > maxLon} because the view wraps across the dateline).
     */
    static boolean containsLongitude(double minLon, double maxLon, double lon) {
        if (minLon <= maxLon) {
            return lon >= minLon && lon <= maxLon;
        }
        return lon >= minLon || lon <= maxLon;
    }
}
