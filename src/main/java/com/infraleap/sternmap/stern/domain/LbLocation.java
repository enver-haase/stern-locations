package com.infraleap.sternmap.stern.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record LbLocation(
        long pk,
        String name,
        @JsonProperty("street_address") String streetAddress,
        String city,
        String state,
        @JsonProperty("postal_code") String postalCode,
        String country,
        String latitude,
        String longitude,
        @JsonProperty("location_type_name") String locationTypeName,
        @JsonProperty("website_url") String websiteUrl
) {
    public double lat() { return Double.parseDouble(latitude); }
    public double lon() { return Double.parseDouble(longitude); }

    public String fullAddress() {
        StringBuilder sb = new StringBuilder();
        if (streetAddress != null && !streetAddress.isBlank()) sb.append(streetAddress).append(", ");
        if (city != null && !city.isBlank()) sb.append(city);
        if (state != null && !state.isBlank()) sb.append(", ").append(state);
        if (postalCode != null && !postalCode.isBlank()) sb.append(" ").append(postalCode);
        if (country != null && !country.isBlank()) sb.append(" (").append(country).append(")");
        return sb.toString();
    }
}
