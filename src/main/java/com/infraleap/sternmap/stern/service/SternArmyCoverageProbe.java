package com.infraleap.sternmap.stern.service;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import com.infraleap.sternmap.stern.domain.VenueOnMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * One-shot diagnostic: after the venue cache is warm, fetches the
 * {@code sternpinball.com/stern-army-locator/} page (which server-side-inlines
 * the complete {@code is_stern_army=true} list as a JSON literal in the HTML)
 * and logs which venues are NOT in our cache. Helps quantify and locate the
 * pure-REST coverage gap.
 *
 * <p>Default-off; enable with {@code stern.probe-army-coverage-diff=true}.
 */
@Component
@Order(20)
public class SternArmyCoverageProbe implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(SternArmyCoverageProbe.class);
    private static final String LOCATOR = "https://sternpinball.com/stern-army-locator/";

    private final SternVenueCacheService cache;
    private final boolean enabled;
    private final WebClient http;
    private final ObjectMapper mapper = JsonMapper.builder().build();

    public SternArmyCoverageProbe(SternVenueCacheService cache,
                                  @Value("${stern.probe-army-coverage-diff:false}") boolean enabled) {
        this.cache = cache;
        this.enabled = enabled;
        this.http = WebClient.builder()
                .codecs(c -> c.defaultCodecs().maxInMemorySize(8 * 1024 * 1024))
                .defaultHeader("User-Agent", "stern-locations-debug/1.0")
                .build();
    }

    @Override
    public void run(String... args) {
        if (!enabled) return;

        // Make sure the cache is warm.
        List<VenueOnMap> all = cache.warm();
        // Build the set of "cached Stern Army" venue names+coords for fuzzy matching.
        // The cache merges by *coord proximity*, not by Pinball Map id, so we'll
        // match the marketing-page venue against any cached venue whose name OR
        // coords are close.
        record Cached(String name, double lat, double lon) {}
        List<Cached> cachedArmy = all.stream()
                .filter(VenueOnMap::isSternArmy)
                .map(v -> new Cached(v.name(), v.lat(), v.lon()))
                .toList();

        // Fetch the marketing page and extract the "locations":[…] array.
        String html;
        try {
            html = http.get().uri(LOCATOR).retrieve()
                    .bodyToMono(String.class).block(Duration.ofSeconds(30));
        } catch (Exception e) {
            log.warn("Coverage probe: failed to fetch marketing page: {}", e.toString());
            return;
        }
        if (html == null) return;
        int idx = html.indexOf("\"locations\":[");
        if (idx < 0) {
            log.warn("Coverage probe: no 'locations' array in marketing-page HTML.");
            return;
        }
        int start = idx + "\"locations\":".length();
        int depth = 0, end = start;
        for (; end < html.length(); end++) {
            char c = html.charAt(end);
            if (c == '[') depth++;
            else if (c == ']') { depth--; if (depth == 0) { end++; break; } }
        }
        JsonNode arr;
        try {
            arr = mapper.readTree(html.substring(start, end));
        } catch (Exception e) {
            log.warn("Coverage probe: failed to parse 'locations' JSON: {}", e.toString());
            return;
        }

        int truthTotal = arr.size();
        int matched = 0;
        Map<String, List<String>> missingByCountry = new TreeMap<>();
        for (JsonNode v : arr) {
            String name = v.path("name").asText("?");
            String country = v.path("country").asText("?");
            String city = v.path("city").asText("?");
            String latS = v.path("lat").asText(null);
            String lonS = v.path("lon").asText(null);
            if (latS == null || lonS == null || latS.isEmpty() || lonS.isEmpty()) continue;
            double lat, lon;
            try {
                lat = Double.parseDouble(latS);
                lon = Double.parseDouble(lonS);
            } catch (NumberFormatException e) { continue; }
            boolean found = false;
            for (Cached c : cachedArmy) {
                if (Math.abs(c.lat() - lat) < 0.02 && Math.abs(c.lon() - lon) < 0.02) {
                    found = true; break;
                }
            }
            if (found) matched++;
            else missingByCountry.computeIfAbsent(country, k -> new java.util.ArrayList<>())
                    .add(name + " (" + city + ")");
        }
        log.info("=== Stern Army coverage diff ===");
        log.info("Marketing-page truth: {} venues. Our cache has Stern Army flag on {} matches; missing {}.",
                truthTotal, matched, truthTotal - matched);
        int totalMissing = 0;
        for (var e : missingByCountry.entrySet()) totalMissing += e.getValue().size();
        log.info("Missing total: {} (= {} - {}). By country:", totalMissing, truthTotal, matched);
        for (var e : missingByCountry.entrySet()) {
            log.info("  {} ({} venues):", e.getKey(), e.getValue().size());
            for (String n : e.getValue()) log.info("    - {}", n);
        }
        log.info("=== end coverage diff ===");
    }
}
