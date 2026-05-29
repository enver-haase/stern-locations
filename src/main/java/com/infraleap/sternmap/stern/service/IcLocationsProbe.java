package com.infraleap.sternmap.stern.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Probes the authenticated Insider Connected web route
 * {@code https://insider.sternpinball.com/ic/locations} to discover what data
 * it actually serves — does Stern have a logged-in venue locator on the web
 * frontend (separate from the sternpinball.com Stern Army page)?
 *
 * <p>Server-side fetch with the existing {@link SternAuthService} cookie so we
 * don't need credentials in the agent transcript. Toggle on with
 * {@code stern.probe-ic-locations=true}.
 */
@Component
@Order(30)
public class IcLocationsProbe implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(IcLocationsProbe.class);
    private static final String URL = "https://insider.sternpinball.com/ic/locations";

    private final SternAuthService authService;
    private final boolean enabled;
    private final HttpClient http;

    public IcLocationsProbe(SternAuthService authService,
                            @Value("${stern.probe-ic-locations:false}") boolean enabled) {
        this.authService = authService;
        this.enabled = enabled;
        this.http = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(15))
                .build();
    }

    @Override
    public void run(String... args) {
        if (!enabled) return;
        String cookies = authService.getCookies();
        if (cookies == null || cookies.isBlank()) {
            log.info("/ic/locations probe skipped — no auth cookies.");
            return;
        }
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(URL))
                    .GET()
                    .header("User-Agent",
                            "Mozilla/5.0 (X11; Ubuntu; Linux x86_64; rv:142.0) Gecko/20100101 Firefox/142.0")
                    .header("Accept", "text/html,application/xhtml+xml")
                    .header("Cookie", cookies)
                    .timeout(Duration.ofSeconds(20))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            int status = resp.statusCode();
            String body = resp.body() == null ? "" : resp.body();
            log.info("=== /ic/locations probe — status={} length={} ===", status, body.length());
            log.info("Location header: {}", resp.headers().firstValue("Location").orElse("(none)"));

            // Look for indicators of what the page actually does
            Matcher title = Pattern.compile("<title>([^<]*)</title>").matcher(body);
            if (title.find()) log.info("title: {}", title.group(1));

            // Inline JSON data?
            String[] needles = {
                    "\"locations\":[", "\"venues\":[", "\"locationsList\"",
                    "\"is_stern_army\"", "\"ic_active\"", "wp-json",
                    "google.maps", "mapbox", "leaflet", "openlayers",
                    "pinballmap.com", "createServerReference",
                    "lb_location", "nearby_leaderboards"
            };
            for (String n : needles) {
                int idx = body.indexOf(n);
                if (idx >= 0) {
                    String snip = body.substring(Math.max(0, idx - 40), Math.min(body.length(), idx + 180))
                            .replace('\n', ' ').replaceAll("\\s+", " ");
                    log.info("hit {}: ...{}...", n, snip);
                }
            }

            // Next.js chunk URLs referenced from this page
            Matcher chunkRe = Pattern.compile("/_next/static/chunks/[a-zA-Z0-9._-]+\\.js").matcher(body);
            java.util.Set<String> chunks = new java.util.LinkedHashSet<>();
            while (chunkRe.find()) chunks.add(chunkRe.group());
            log.info("chunks referenced: {} → {}", chunks.size(), chunks);

            // Dump the body to a file so we can grep it later
            try {
                java.nio.file.Path out = java.nio.file.Path.of("/tmp/sternprobe/ic_locations_body.html");
                java.nio.file.Files.writeString(out, body);
                log.info("body written to {}", out);
            } catch (Exception ignore) {}
            log.info("=== end /ic/locations probe ===");
        } catch (Exception e) {
            log.error("/ic/locations probe failed: {}", e.toString());
        }
    }
}
