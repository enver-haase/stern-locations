package com.infraleap.sternmap.stern.domain;

import java.util.List;

/**
 * View-model for one Stern Insider Connected venue + its tournament names + distance.
 * <p>
 * Built from a single {@code /nearby_leaderboards/} response page: that page
 * returns multiple tournaments from the user's closest venue, which we collapse
 * into one venue here.
 */
public record SternVenueSpot(LbLocation location, List<String> tournamentNames, Double distanceKm) {}
