package com.infraleap.sternmap.pinballmap.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * One region from Pinball Map's {@code /api/v1/regions.json}. We only use the
 * slug ({@code name}) — the full URL pattern is
 * {@code /api/v1/region/<name>/locations.json}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PinballMapRegion(String name, String full_name) {}
