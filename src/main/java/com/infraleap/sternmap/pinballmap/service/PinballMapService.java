package com.infraleap.sternmap.pinballmap.service;

import com.infraleap.sternmap.pinballmap.PinballMapProperties;
import com.infraleap.sternmap.pinballmap.domain.PinballMapResponse;
import com.infraleap.sternmap.pinballmap.domain.PinballMapVenue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.Comparator;
import java.util.List;

/**
 * Calls the public Pinball Map API for nearby venues + per-venue machine lists.
 * <p>
 * Stern's {@code /nearby_leaderboards/} endpoint returns thousands of perpetual
 * leaderboards belonging only to the user's *closest* venue (probed at multiple
 * cities — every page is the same venue), so it can't surface a venue list.
 * Pinball Map is community-maintained, public, and explicitly designed for the
 * "what pinball is near me" use case.
 */
@Service
public class PinballMapService {

    private static final Logger log = LoggerFactory.getLogger(PinballMapService.class);
    private static final String CLOSEST_URL =
            "https://pinballmap.com/api/v1/locations/closest_by_lat_lon.json";

    private final WebClient webClient;
    private final PinballMapProperties properties;

    public PinballMapService(PinballMapProperties properties) {
        this.properties = properties;
        this.webClient = WebClient.builder()
                .codecs(c -> c.defaultCodecs().maxInMemorySize(8 * 1024 * 1024))
                .defaultHeader("User-Agent", "stern-locations/1.0")
                .defaultHeader("Accept", "application/json")
                .build();
    }

    /**
     * Find every venue within {@code maxKm} of the given coordinates, sorted by
     * distance ascending. Pinball Map's {@code send_all_within_distance} is in
     * miles, so we ask for a generous net (1.6× the km target) and then filter
     * client-side using the response's {@code distance} (kilometers).
     */
    public List<PinballMapVenue> findVenues(double latitude, double longitude, double maxKm) {
        int requestMiles = Math.max(10, (int) Math.ceil(maxKm));
        String url = CLOSEST_URL
                + "?lat=" + latitude
                + "&lon=" + longitude
                + "&send_all_within_distance=" + requestMiles;
        log.info("GET {}", url);
        try {
            PinballMapResponse resp = webClient.get().uri(url)
                    .retrieve()
                    .bodyToMono(PinballMapResponse.class)
                    .block(Duration.ofSeconds(20));
            if (resp == null || resp.locations() == null) return List.of();
            List<PinballMapVenue> filtered = resp.locations().stream()
                    .filter(v -> v.distance() != null && v.distance() <= maxKm)
                    .filter(v -> v.lat() != null && v.lon() != null)
                    .filter(v -> !properties.sternArmyOnly() || Boolean.TRUE.equals(v.isSternArmy()))
                    .sorted(Comparator.comparingDouble(PinballMapVenue::distance))
                    .toList();
            log.info("Pinball Map returned {} venues, {} within {} km{}",
                    resp.locations().size(), filtered.size(), maxKm,
                    properties.sternArmyOnly() ? " (filtered to is_stern_army=true)" : "");
            return filtered;
        } catch (Exception e) {
            log.error("Pinball Map request failed: {}", e.toString());
            return List.of();
        }
    }
}
