package com.infraleap.sternmap.pinballmap.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record PinballMapResponse(List<PinballMapVenue> locations) {}
