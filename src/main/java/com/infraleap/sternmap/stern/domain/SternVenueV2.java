package com.infraleap.sternmap.stern.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * One Stern Insider Connected venue with its machines, from
 * {@code /api/v2/portal/game_locations_search/}.
 * <p>
 * The endpoint wraps each row inside a {@code {"location": {...}}} object —
 * see {@link SternVenueWrapper}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SternVenueV2(
        long id,
        String name,
        @JsonProperty("street_address") String streetAddress,
        @JsonProperty("street_address_2") String streetAddress2,
        String city,
        String state,
        String country,
        String latitude,
        String longitude,
        String telephone,
        @JsonProperty("website_url") String websiteUrl,
        @JsonProperty("location_type_name") String locationTypeName,
        List<SternMachineV2> machines
) {
    public double lat() { return Double.parseDouble(latitude); }
    public double lon() { return Double.parseDouble(longitude); }

    public String fullAddress() {
        StringBuilder sb = new StringBuilder();
        if (streetAddress != null && !streetAddress.isBlank()) sb.append(streetAddress);
        if (streetAddress2 != null && !streetAddress2.isBlank()) sb.append(' ').append(streetAddress2);
        if (city != null && !city.isBlank()) sb.append(", ").append(city);
        if (state != null && !state.isBlank() && !state.equals(city)) sb.append(", ").append(state);
        if (country != null && !country.isBlank()) sb.append(" (").append(country).append(')');
        return sb.toString();
    }

    public int machineCount() {
        return machines == null ? 0 : machines.size();
    }
}
