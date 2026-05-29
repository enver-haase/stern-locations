package com.infraleap.sternmap.stern.service;

import com.infraleap.sternmap.config.SternProperties;
import com.infraleap.sternmap.stern.domain.Leaderboard;
import com.infraleap.sternmap.stern.domain.LbLocation;
import com.infraleap.sternmap.stern.domain.NearbyLeaderboardsResponse;
import com.infraleap.sternmap.stern.domain.SternVenueSpot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Tournament name lookup against Stern's {@code /nearby_leaderboards/} endpoint.
 * <p>
 * The endpoint was reverse-engineered from the Stern IC iOS app. Probing
 * confirmed (2026-05-29) that it returns <em>only the closest venue's</em>
 * leaderboards regardless of how deep you paginate — pages 1..N for any
 * coordinate are all the same venue, and standard DRF filter params
 * ({@code is_active}, {@code distinct}, {@code distance__lte}, …) are silently
 * ignored. So we use this endpoint for what it can actually deliver: given
 * coordinates close to a venue, return that venue's tournament names. Calling
 * with each Pinball Map venue's own coords lets us attach tournaments to any
 * venue we want, one Stern call per venue.
 */
@Service
public class SternLocationService {

    private static final Logger log = LoggerFactory.getLogger(SternLocationService.class);
    private static final String CMS_BASE = "https://cms.prd.sternpinball.io/api/v1/portal";

    private final SternAuthService authService;
    private final WebClient webClient;
    private final String locationHeader;

    public SternLocationService(SternAuthService authService, SternProperties props) {
        this.authService = authService;
        this.locationHeader = "{\"country\":\"" + props.defaultCountry()
                + "\",\"continent\":\"" + props.defaultContinent() + "\"}";
        this.webClient = WebClient.builder()
                .codecs(c -> c.defaultCodecs().maxInMemorySize(8 * 1024 * 1024))
                .defaultHeader("User-Agent",
                        "Mozilla/5.0 (X11; Ubuntu; Linux x86_64; rv:142.0) Gecko/20100101 Firefox/142.0")
                .defaultHeader("Accept", "application/json, text/plain, */*")
                .defaultHeader("Referer", "https://insider.sternpinball.com/")
                .defaultHeader("Origin", "https://insider.sternpinball.com")
                .build();
    }

    /**
     * Fetch the venue closest to the supplied coordinates, including its
     * tournament names. Built from one page of {@code /nearby_leaderboards/}
     * results — all rows on page 1 always share a venue, so we read the venue
     * info from the first row and collect the tournament names across the page.
     */
    public Optional<SternVenueSpot> findClosestVenue(double latitude, double longitude) {
        NearbyLeaderboardsResponse resp = fetch(latitude, longitude);
        if (resp == null || resp.results() == null || resp.results().isEmpty()) {
            return Optional.empty();
        }
        Leaderboard first = resp.results().get(0);
        LbLocation loc = first.lbLocation();
        if (loc == null || loc.latitude() == null || loc.longitude() == null) {
            return Optional.empty();
        }
        List<String> names = resp.results().stream()
                .map(Leaderboard::name)
                .filter(n -> n != null && !n.isBlank())
                .distinct()
                .toList();
        return Optional.of(new SternVenueSpot(loc, names, first.distance()));
    }

    /**
     * Fetch Stern tournament names for the venue closest to the supplied
     * coordinates. Used by the Pinball-Map-enabled view to lazily attach
     * tournaments to a Pinball Map venue card.
     */
    public List<String> findTournamentsAt(double latitude, double longitude) {
        NearbyLeaderboardsResponse resp = fetch(latitude, longitude);
        if (resp == null || resp.results() == null) return List.of();
        return resp.results().stream()
                .map(Leaderboard::name)
                .filter(n -> n != null && !n.isBlank())
                .distinct()
                .toList();
    }

    private NearbyLeaderboardsResponse fetch(double latitude, double longitude) {
        String url = CMS_BASE + "/nearby_leaderboards/?latitude=" + latitude
                + "&longitude=" + longitude;
        WebClient.RequestHeadersSpec<?> spec = webClient.get().uri(url);
        String token = authService.getToken();
        String cookies = authService.getCookies();
        if (token != null) spec = spec.header("Authorization", "Bearer " + token);
        if (cookies != null) spec = spec.header("Cookie", cookies);
        spec = spec.header("Location", locationHeader);
        try {
            return spec.retrieve()
                    .bodyToMono(NearbyLeaderboardsResponse.class)
                    .block(Duration.ofSeconds(20));
        } catch (Exception e) {
            log.debug("Stern call at ({},{}) failed: {}", latitude, longitude, e.toString());
            return null;
        }
    }
}
