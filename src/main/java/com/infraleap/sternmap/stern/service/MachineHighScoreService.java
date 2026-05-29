package com.infraleap.sternmap.stern.service;

import com.infraleap.sternmap.stern.domain.MachineHighScoreResponse;
import com.infraleap.sternmap.stern.domain.MachineHighScoreResponse.Entry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lazy lookup of the top 5 perpetual high scores for one Stern Insider Connected
 * machine installation.
 * <p>
 * Endpoint: {@code GET /api/v1/portal/game_machine_high_scores/?machine_id=<id>}
 * (auth required). Returns ~880 bytes per call. Results are cached per
 * machine id for the lifetime of the JVM so a venue card re-render or
 * repeated clicks don't re-fetch.
 */
@Service
public class MachineHighScoreService {

    private static final Logger log = LoggerFactory.getLogger(MachineHighScoreService.class);
    private static final String URL_BASE =
            "https://cms.prd.sternpinball.io/api/v1/portal/game_machine_high_scores/";

    private final SternAuthService authService;
    private final WebClient webClient;
    private final Map<Long, List<Entry>> cache = new ConcurrentHashMap<>();

    public MachineHighScoreService(SternAuthService authService) {
        this.authService = authService;
        this.webClient = WebClient.builder()
                .codecs(c -> c.defaultCodecs().maxInMemorySize(1024 * 1024))
                .defaultHeader("User-Agent",
                        "Mozilla/5.0 (X11; Ubuntu; Linux x86_64; rv:142.0) Gecko/20100101 Firefox/142.0")
                .defaultHeader("Accept", "application/json, text/plain, */*")
                .defaultHeader("Referer", "https://insider.sternpinball.com/")
                .defaultHeader("Origin", "https://insider.sternpinball.com")
                .build();
    }

    public List<Entry> topScores(long machineId) {
        List<Entry> cached = cache.get(machineId);
        if (cached != null) return cached;
        String token = authService.getToken();
        if (token == null || token.isBlank()) {
            log.warn("MachineHighScoreService: no auth token, returning empty for machine {}", machineId);
            return List.of();
        }
        String url = URL_BASE + "?machine_id=" + machineId;
        try {
            MachineHighScoreResponse resp = webClient.get().uri(url)
                    .header("Authorization", "Bearer " + token)
                    .header("Cookie", authService.getCookies() == null ? "" : authService.getCookies())
                    .retrieve()
                    .bodyToMono(MachineHighScoreResponse.class)
                    .block(Duration.ofSeconds(15));
            List<Entry> entries = resp == null || resp.highScore() == null
                    ? List.of() : resp.highScore();
            cache.put(machineId, entries);
            return entries;
        } catch (Exception e) {
            log.warn("High-score fetch for machine {} failed: {}", machineId, e.toString());
            return List.of();
        }
    }
}
