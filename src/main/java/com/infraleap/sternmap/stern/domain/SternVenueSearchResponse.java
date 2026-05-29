package com.infraleap.sternmap.stern.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * Top-level shape of {@code GET /api/v2/portal/game_locations_search/} —
 * the proximity-filtered venue listing from the Insider Connected web SPA.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SternVenueSearchResponse(int count, List<SternVenueWrapper> locations) {

    /** Each location entry is wrapped in a {@code {"location": {...}}} object. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SternVenueWrapper(SternVenueV2 location) {}

    public List<SternVenueV2> unwrap() {
        if (locations == null) return List.of();
        return locations.stream()
                .map(SternVenueWrapper::location)
                .filter(v -> v != null && v.latitude() != null && v.longitude() != null)
                .toList();
    }
}
