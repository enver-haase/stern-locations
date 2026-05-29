package com.infraleap.sternmap.stern.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record Leaderboard(
        long pk,
        String name,
        String type,
        Double distance,
        @JsonProperty("lb_location") LbLocation lbLocation
) {}
