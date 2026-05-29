package com.infraleap.sternmap.stern.service;

import com.infraleap.sternmap.config.SternProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Authenticates against the Stern Insider Connected web frontend.
 * <p>
 * Reverse-engineered from the Stern IC iOS app and the existing
 * stern-home-leaderboards project. Login POSTs a JSON array
 * <code>["email","password"]</code> as the body of a Next.js server action
 * call. The response sets the <code>spb-insider-token</code> cookie which
 * doubles as the Bearer token for subsequent API calls.
 * <p>
 * The token is good for ~30 minutes; we re-auth on demand.
 */
@Service
public class SternAuthService {

    private static final Logger log = LoggerFactory.getLogger(SternAuthService.class);
    private static final String LOGIN_URL = "https://insider.sternpinball.com/login";
    private static final String INSIDER_BASE = "https://insider.sternpinball.com";
    private static final Duration AUTH_EXPIRY = Duration.ofMinutes(9); // JWT lives ~10 min; renew at 9
    private static final Pattern TOKEN_PATTERN = Pattern.compile("spb-insider-token=([^;]+)");
    private static final Pattern CHUNK_PATTERN = Pattern.compile("/_next/static/chunks/[^\"']+\\.js");
    // Matches `createServerReference)("<40-50 hex>",...,"actionName")`.
    private static final Pattern ACTION_REF_PATTERN = Pattern.compile(
            "createServerReference\\)\\(\"([a-f0-9]{30,60})\",[^,]+,[^,]+,[^,]+,\"performLogin\"");
    // Known-good fallback from 2026-05-29 reverse-engineering. Rotates whenever
    // the Next.js login bundle is rebuilt — see discoverLoginActionHash() for
    // dynamic resolution.
    private static final String FALLBACK_LOGIN_ACTION_HASH = "6019d9ac959a924fb98bf8bca486c1f893b70dcdce";

    private final SternProperties props;
    private final HttpClient httpClient;

    private volatile String token;
    private volatile String cookies;
    private volatile Instant lastAuthTime;
    private volatile String loginActionHash;

    public SternAuthService(SternProperties props) {
        this.props = props;
        this.httpClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(15))
                .build();
    }

    public synchronized boolean login() {
        String username = props.sternUsername();
        String password = props.sternPassword();
        if (username == null || username.isBlank() || password == null || password.isBlank()) {
            log.warn("Stern credentials not configured — staying anonymous. " +
                    "Set leaderboards.stern-username and leaderboards.stern-password " +
                    "in ~/stern-home-leaderboards.properties to enable authed endpoints.");
            return false;
        }

        try {
            String body = "[\"" + escapeJson(username) + "\",\"" + escapeJson(password) + "\"]";
            String actionHash = resolveLoginActionHash();

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(LOGIN_URL))
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .header("User-Agent", "Mozilla/5.0 (X11; Ubuntu; Linux x86_64; rv:142.0) Gecko/20100101 Firefox/142.0")
                    .header("Accept", "text/x-component")
                    .header("Accept-Language", "en-US,en;q=0.5")
                    .header("Referer", "https://insider.sternpinball.com/login")
                    .header("Next-Action", actionHash)
                    .header("Content-Type", "text/plain;charset=UTF-8")
                    .header("Origin", "https://insider.sternpinball.com")
                    .timeout(Duration.ofSeconds(30))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            String extractedToken = null;
            StringBuilder cookieBuilder = new StringBuilder();
            for (String setCookie : response.headers().allValues("set-cookie")) {
                if (cookieBuilder.length() > 0) cookieBuilder.append("; ");
                cookieBuilder.append(setCookie.split(";")[0]);
                Matcher m = TOKEN_PATTERN.matcher(setCookie);
                if (m.find()) extractedToken = m.group(1);
            }

            boolean authenticated = false;
            String responseBody = response.body();
            if (responseBody != null && responseBody.contains("\"authenticated\":true")) {
                authenticated = true;
            }

            if ((response.statusCode() == 200 && (authenticated || extractedToken != null))
                    || ((response.statusCode() == 302 || response.statusCode() == 303) && extractedToken != null)) {
                this.token = extractedToken;
                this.cookies = cookieBuilder.toString();
                this.lastAuthTime = Instant.now();
                log.info("Stern authentication successful");
                return true;
            }

            String preview = responseBody == null ? "<null>"
                    : responseBody.length() > 220 ? responseBody.substring(0, 220) + "…" : responseBody;
            log.error("Stern authentication failed - status: {}, authenticated: {}, hasToken: {}. Body preview: {}",
                    response.statusCode(), authenticated, extractedToken != null, preview);
            return false;
        } catch (Exception e) {
            log.error("Stern login error", e);
            return false;
        }
    }

    public boolean isExpired() {
        return lastAuthTime == null || Instant.now().isAfter(lastAuthTime.plus(AUTH_EXPIRY));
    }

    public synchronized void refreshIfNeeded() {
        if (isExpired()) login();
    }

    public String getToken() {
        refreshIfNeeded();
        return token;
    }

    public String getCookies() {
        refreshIfNeeded();
        return cookies;
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /**
     * Returns the Next.js Server-Action hash for {@code performLogin}.
     *
     * <p>Next.js content-hashes server actions, so this id rotates whenever the
     * login bundle is rebuilt. We resolve it dynamically by:
     * <ol>
     *   <li>fetching the live login page</li>
     *   <li>enumerating every chunk URL it references</li>
     *   <li>scanning each chunk for the {@code createServerReference("&lt;hash&gt;",…,"performLogin")} fingerprint</li>
     * </ol>
     * Falls back to the hash recorded at build time if the scrape fails.
     * Result is cached for the lifetime of the service.
     */
    private synchronized String resolveLoginActionHash() {
        if (loginActionHash != null) return loginActionHash;
        try {
            String pageHtml = httpGet(LOGIN_URL);
            if (pageHtml != null) {
                List<String> chunkUrls = new ArrayList<>();
                Matcher cm = CHUNK_PATTERN.matcher(pageHtml);
                while (cm.find()) chunkUrls.add(cm.group());
                for (String chunkPath : chunkUrls) {
                    String chunk = httpGet(INSIDER_BASE + chunkPath);
                    if (chunk == null) continue;
                    Matcher am = ACTION_REF_PATTERN.matcher(chunk);
                    if (am.find()) {
                        loginActionHash = am.group(1);
                        log.info("Discovered current Next-Action hash for performLogin: {} (chunk {})",
                                loginActionHash, chunkPath);
                        return loginActionHash;
                    }
                }
            }
            log.warn("Could not discover performLogin action hash from live bundle; " +
                    "falling back to known-good hash {} (may be stale)", FALLBACK_LOGIN_ACTION_HASH);
        } catch (Exception e) {
            log.warn("Action-hash discovery failed: {} — using fallback hash", e.toString());
        }
        loginActionHash = FALLBACK_LOGIN_ACTION_HASH;
        return loginActionHash;
    }

    private String httpGet(String url) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .GET()
                    .header("User-Agent", "Mozilla/5.0 (X11; Ubuntu; Linux x86_64; rv:142.0) Gecko/20100101 Firefox/142.0")
                    .timeout(Duration.ofSeconds(20))
                    .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) return resp.body();
        } catch (Exception e) {
            log.debug("httpGet {} failed: {}", url, e.toString());
        }
        return null;
    }
}
