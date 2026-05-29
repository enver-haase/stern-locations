package com.infraleap.sternmap.ui;

import com.infraleap.sternmap.stern.domain.VenueOnMap;
import com.vaadin.flow.component.map.configuration.Extent;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression tests for {@link MapExtentFilter}.
 * <p>
 * Drives the fix for the "0 of N venues in view" bug. The earlier MapView
 * implementation wrongly converted Vaadin Map's Extent through a Web Mercator
 * de-projection — but Vaadin Map reports the Extent in the user projection
 * (EPSG:4326 by default), i.e. lon/lat degrees. The mercator conversion
 * collapsed every Extent into a sub-millimetre region near (0,0), so the
 * sidebar showed "0 in view" while every marker was clearly visible.
 * <p>
 * These tests pin the contract: an Extent's minX/maxX are longitudes,
 * minY/maxY are latitudes, and a venue is included iff its lon/lat fall
 * inside the rectangle.
 */
class MapExtentFilterTest {

    private static VenueOnMap venue(String name, double lat, double lon) {
        return new VenueOnMap(name.hashCode(), name, "", lat, lon, null, null,
                List.of(), EnumSet.of(VenueOnMap.Source.STERN_IC));
    }

    @Test
    void berlinExtentIncludesBerlinVenuesExcludesEverythingElse() {
        // Real-world Extent values logged from a live zoom-12 view centred on
        // Berlin Mitte: minX=13.22 minY=52.18 maxX=14.02 maxY=52.72.
        Extent berlin = new Extent(13.217, 52.178, 14.017, 52.717);

        VenueOnMap gamestate = venue("Gamestate Potsdamer Platz Berlin", 52.508, 13.374);
        VenueOnMap birgit = venue("Birgit & Bier", 52.499, 13.451);
        VenueOnMap hamburg = venue("Cosmos Arcade", 53.55, 10.0);          // outside
        VenueOnMap nyc = venue("Some NYC Venue", 40.7128, -74.0060);       // outside
        VenueOnMap origin = venue("Sub-millimetre-near-zero", 0.000_5, 0.000_5);
        // ^ would falsely match the buggy mercator-de-projected extent

        List<VenueOnMap> all = List.of(gamestate, birgit, hamburg, nyc, origin);
        List<VenueOnMap> inView = MapExtentFilter.venuesInExtent(berlin, all);

        assertEquals(2, inView.size(), "expected exactly the two Berlin venues");
        assertTrue(inView.contains(gamestate));
        assertTrue(inView.contains(birgit));
        assertFalse(inView.contains(hamburg), "Hamburg is north of the Berlin extent");
        assertFalse(inView.contains(nyc), "NYC is on the other side of the planet");
        assertFalse(inView.contains(origin),
                "venue near (0,0) must NOT be in a Berlin-area extent — guards against "
                        + "the regression where mercator-de-projection mapped every extent to ~(0,0)");
    }

    @Test
    void worldExtentIncludesEverything() {
        // The Extent reported by the initial zoom-3 view (whole map visible).
        Extent world = new Extent(-180, -85, 180, 85);

        List<VenueOnMap> all = List.of(
                venue("Berlin", 52.5, 13.4),
                venue("NYC", 40.7, -74.0),
                venue("Sydney", -33.86, 151.21),
                venue("Cape Town", -33.92, 18.42),
                venue("Tokyo", 35.68, 139.76)
        );
        List<VenueOnMap> inView = MapExtentFilter.venuesInExtent(world, all);
        assertEquals(5, inView.size(), "world extent must include every venue");
    }

    @Test
    void nullExtentReturnsEmpty() {
        List<VenueOnMap> inView = MapExtentFilter.venuesInExtent(null, List.of(venue("X", 0, 0)));
        assertEquals(0, inView.size());
        assertSame(List.of(), inView);
    }

    @Test
    void emptyInputReturnsEmpty() {
        Extent any = new Extent(-1, -1, 1, 1);
        assertSame(List.of(), MapExtentFilter.venuesInExtent(any, List.of()));
        assertSame(List.of(), MapExtentFilter.venuesInExtent(any, null));
    }

    @Test
    void antimeridianCrossingExtentMatchesBothSides() {
        // An extent that wraps across the dateline has minX > maxX
        // (e.g. centred on the Pacific). Logic must use OR-semantics.
        assertTrue(MapExtentFilter.containsLongitude(170, -170, 175));
        assertTrue(MapExtentFilter.containsLongitude(170, -170, -175));
        assertTrue(MapExtentFilter.containsLongitude(170, -170, 180));
        assertFalse(MapExtentFilter.containsLongitude(170, -170, 0));
        assertFalse(MapExtentFilter.containsLongitude(170, -170, 100));
    }

    /**
     * Direct regression guard: if someone later re-introduces a Web-Mercator
     * de-projection step on the Extent, this test will catch it. The buggy
     * code did roughly: {@code lon = toDegrees(x / 6378137)}, turning a Berlin
     * extent into a sub-millimetre range near zero. We re-run the bug here
     * and assert that the filter result with the bug is wildly different
     * from the correct result — so the assertion below would FAIL if
     * MapExtentFilter ever started doing that conversion.
     */
    @Test
    void doesNotReintroduceTheMercatorDeProjectionBug() {
        Extent berlin = new Extent(13.217, 52.178, 14.017, 52.717);
        VenueOnMap gamestate = venue("Gamestate Potsdamer Platz Berlin", 52.508, 13.374);
        VenueOnMap nearZero = venue("Phantom Near Zero", 0.000_5, 0.000_5);
        List<VenueOnMap> all = List.of(gamestate, nearZero);

        List<VenueOnMap> inView = MapExtentFilter.venuesInExtent(berlin, all);
        assertTrue(inView.contains(gamestate),
                "real Berlin venue must be in a Berlin extent");
        assertFalse(inView.contains(nearZero),
                "a venue at lat/lon ~(0,0) must NOT match a Berlin extent — "
                        + "if this fails, someone has likely re-introduced the "
                        + "mercator-de-projection bug that turned Berlin's extent "
                        + "into ~(0,0).");
    }

    @Test
    void nonCrossingLongitudeRangeUsesAndSemantics() {
        assertTrue(MapExtentFilter.containsLongitude(-10, 10, 0));
        assertTrue(MapExtentFilter.containsLongitude(-10, 10, -10));
        assertTrue(MapExtentFilter.containsLongitude(-10, 10, 10));
        assertFalse(MapExtentFilter.containsLongitude(-10, 10, -11));
        assertFalse(MapExtentFilter.containsLongitude(-10, 10, 100));
    }
}
