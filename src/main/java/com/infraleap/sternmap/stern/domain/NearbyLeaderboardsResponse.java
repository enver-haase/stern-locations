package com.infraleap.sternmap.stern.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record NearbyLeaderboardsResponse(int count, String next, String previous, List<Leaderboard> results) {}
