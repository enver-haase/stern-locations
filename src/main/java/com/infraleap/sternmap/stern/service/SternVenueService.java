package com.infraleap.sternmap.stern.service;

import com.infraleap.sternmap.config.SternProperties;
import com.infraleap.sternmap.stern.domain.SternVenueSearchResponse;
import com.infraleap.sternmap.stern.domain.SternVenueV2;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.List;

/**
 * Native Stern Insider Connected venue lookup via the v2 search endpoint.
 * <p>
 * Discovered 2026-05-29 by analysing the IC Vite-built SPA bundle at
 * {@code /assets/index-*.js} on {@code insider.sternpinball.com}. The bundle
 * builds a request as:
 * <pre>{@code
 *   fe({url:"portal/game_locations_search/", manual:!0, version:2, params:e})
 * }</pre>
 * with base URL {@code https://cms.prd.sternpinball.io/api/} and
 * {@code version:2}, so the full endpoint is:
 * <pre>{@code GET /api/v2/portal/game_locations_search/?latitude=<lat>&longitude=<lon>}</pre>
 * Returns a small set of nearby venues (4 around Berlin Mitte) with each
 * venue's machines inline. Auth required.
 */
@Service
public class SternVenueService {

    private static final Logger log = LoggerFactory.getLogger(SternVenueService.class);
    private static final String SEARCH_URL =
            "https://cms.prd.sternpinball.io/api/v2/portal/game_locations_search/";

    private final SternAuthService authService;
    private final WebClient webClient;
    private final String locationHeader;

    public SternVenueService(SternAuthService authService, SternProperties props) {
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
     * Search Stern's native venue list around the given coordinates. Returns
     * each venue's machine roster inline. Requires a valid auth token.
     */
    public List<SternVenueV2> findVenues(double latitude, double longitude) {
        String url = SEARCH_URL + "?latitude=" + latitude + "&longitude=" + longitude;
        String token = authService.getToken();
        if (token == null || token.isBlank()) {
            log.warn("Stern venue search at ({},{}) skipped — no auth token.", latitude, longitude);
            return List.of();
        }
        WebClient.RequestHeadersSpec<?> spec = webClient.get().uri(url)
                .header("Authorization", "Bearer " + token)
                .header("Location", locationHeader);
        String cookies = authService.getCookies();
        if (cookies != null) spec = spec.header("Cookie", cookies);
        try {
            SternVenueSearchResponse resp = spec.retrieve()
                    .bodyToMono(SternVenueSearchResponse.class)
                    .block(Duration.ofSeconds(20));
            if (resp == null) return List.of();
            List<SternVenueV2> venues = resp.unwrap();
            log.info("Stern v2 search returned {} venues near ({}, {})",
                    venues.size(), latitude, longitude);
            return venues;
        } catch (Exception e) {
            log.error("Stern v2 search at ({},{}) failed: {}", latitude, longitude, e.toString());
            return List.of();
        }
    }
}
