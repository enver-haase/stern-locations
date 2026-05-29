package com.infraleap.sternmap.stern.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.List;

/**
 * One-shot probe: with a real authenticated Stern token, hit every plausible
 * venue-listing endpoint variant and log the result. The aim is to discover
 * whether {@code /portal/game_locations/} (which returns 403 unauthenticated)
 * actually delivers a multi-venue listing when authenticated — that's the only
 * remaining un-probed path to native Stern multi-venue support.
 *
 * <p>Runs after {@link SternSmokeTest} so the auth token is already populated.
 * Skips itself entirely when not authenticated.
 *
 * <p>Toggle off with {@code stern.probe-game-locations=false} after the
 * findings have been recorded.
 */
@Component
@Order(20)
public class GameLocationsProbe implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(GameLocationsProbe.class);
    private static final String CMS_BASE = "https://cms.prd.sternpinball.io";
    private static final String API_BASE = "https://api.prd.sternpinball.io";

    private final SternAuthService authService;
    private final WebClient webClient;
    private final boolean enabled;

    public GameLocationsProbe(SternAuthService authService,
                              @Value("${stern.probe-game-locations:false}") boolean enabled) {
        this.authService = authService;
        this.enabled = enabled;
        this.webClient = WebClient.builder()
                .codecs(c -> c.defaultCodecs().maxInMemorySize(8 * 1024 * 1024))
                .defaultHeader("User-Agent",
                        "Mozilla/5.0 (X11; Ubuntu; Linux x86_64; rv:142.0) Gecko/20100101 Firefox/142.0")
                .defaultHeader("Accept", "application/json, text/plain, */*")
                .defaultHeader("Referer", "https://insider.sternpinball.com/")
                .defaultHeader("Origin", "https://insider.sternpinball.com")
                .build();
    }

    @Override
    public void run(String... args) {
        if (!enabled) return;
        String token = authService.getToken();
        if (token == null || token.isBlank()) {
            log.info("game_locations probe skipped — no auth token.");
            return;
        }
        log.info("=== game_locations authed probe (lat=52.505, lon=13.3805) ===");

        // Hunt for the per-venue leaderboard endpoint. The IC SPA bundle calls
        // /portal/leaderboards/titles/ with just `location`. We've tried pk=33885
        // (400). Try the venue's other identifiers + alternative endpoints.
        String pk = "33885";        // Gamestate Potsdamer Platz Berlin (Stern v2 id)
        String code = "11627";      // Stern's customer code for Gamestate
        String uid = "58cb78e5-1088-419a-b663-87450ac93b6b";  // Stern lb_location UUID
        List<String> paths = List.of(
                CMS_BASE + "/api/v1/portal/leaderboards/titles/?location=" + pk,
                CMS_BASE + "/api/v1/portal/leaderboards/titles/?location=" + code,
                CMS_BASE + "/api/v1/portal/leaderboards/titles/?location=" + uid,
                CMS_BASE + "/api/v2/portal/leaderboards/titles/?location=" + pk,
                CMS_BASE + "/api/v2/portal/leaderboards/titles/?location=" + uid,
                CMS_BASE + "/api/v1/portal/leaderboards/titles/?lb_location=" + pk,
                CMS_BASE + "/api/v1/portal/leaderboards/titles/?game_location=" + pk,
                CMS_BASE + "/api/v1/portal/leaderboards/?game_location=" + pk,
                CMS_BASE + "/api/v1/portal/leaderboards/?lb_location=" + pk,
                CMS_BASE + "/api/v1/portal/game_locations/" + pk + "/leaderboards/",
                CMS_BASE + "/api/v2/portal/game_locations/" + pk + "/leaderboards/",
                CMS_BASE + "/api/v2/portal/game_locations_with_games/" + pk + "/leaderboards/"
        );

        String cookies = authService.getCookies();
        int idx = 0;
        for (String url : paths) {
            idx++;
            try {
                String body = webClient.get()
                        .uri(url)
                        .header("Authorization", "Bearer " + token)
                        .header("Cookie", cookies == null ? "" : cookies)
                        .retrieve()
                        .toEntity(String.class)
                        .map(r -> {
                            String b = r.getBody() == null ? "" : r.getBody();
                            String preview = b.length() > 220 ? b.substring(0, 220) + "…" : b;
                            return r.getStatusCode().value() + "  size=" + b.length() + "B  " + preview;
                        })
                        .onErrorResume(e -> reactor.core.publisher.Mono.just("ERR " + e.getClass().getSimpleName() + ": " + e.getMessage()))
                        .block(Duration.ofSeconds(15));
                log.info("  {} -> {}", url, body == null ? "(no response)" : body.replaceAll("\\s+", " "));

                // Also dump the FULL body to disk for the OK responses
                try {
                    String full = webClient.get()
                            .uri(url)
                            .header("Authorization", "Bearer " + token)
                            .header("Cookie", cookies == null ? "" : cookies)
                            .retrieve()
                            .bodyToMono(String.class)
                            .onErrorReturn("")
                            .block(Duration.ofSeconds(15));
                    if (full != null && !full.isBlank()) {
                        java.nio.file.Files.writeString(
                                java.nio.file.Path.of("/tmp/sternprobe/probe_" + idx + ".json"),
                                full);
                    }
                } catch (Exception ignore) {}
            } catch (Exception e) {
                log.info("  {} -> EXC {}: {}", url, e.getClass().getSimpleName(), e.getMessage());
            }
        }
        log.info("=== end game_locations probe ===");
    }
}
