package com.infraleap.sternmap.pinballmap.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * One venue (pinball machine location) from pinballmap.com.
 * <p>
 * Public API at {@code https://pinballmap.com/api/v1/locations/closest_by_lat_lon.json} —
 * community-maintained, no auth, no rate limit advertised. Returns the same Gamestate
 * Potsdamer Platz that Stern's nearby_leaderboards surfaces, but unlike Stern it gives
 * us *all* nearby venues in a single call plus their per-venue machine list.
 * <p>
 * {@code distance} is always kilometers in the response; the request param
 * {@code send_all_within_distance} is miles by default — we filter client-side.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PinballMapVenue(
        long id,
        String name,
        String street,
        String city,
        String state,
        String zip,
        String country,
        String phone,
        String lat,
        String lon,
        String website,
        Double distance,
        @JsonProperty("ic_active") Boolean icActive,
        @JsonProperty("is_stern_army") Boolean isSternArmy,
        @JsonProperty("machine_count") Integer machineCount,
        @JsonProperty("machine_names") List<String> machineNames
) {
    public double latAsDouble() { return Double.parseDouble(lat); }
    public double lonAsDouble() { return Double.parseDouble(lon); }

    public String fullAddress() {
        StringBuilder sb = new StringBuilder();
        if (street != null && !street.isBlank()) sb.append(street).append(", ");
        if (city != null && !city.isBlank()) sb.append(city);
        if (state != null && !state.isBlank()) sb.append(", ").append(state);
        if (zip != null && !zip.isBlank()) sb.append(' ').append(zip);
        if (country != null && !country.isBlank()) sb.append(" (").append(country).append(')');
        return sb.toString();
    }
}
