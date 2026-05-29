package com.infraleap.sternmap.stern.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Local-dev-only endpoint that exposes the current Stern auth cookie + Bearer
 * token so a separate Playwright session can inject them via
 * {@code BrowserContext.addCookies()} and navigate the IC SPA as the
 * authenticated user.
 *
 * <p>Default-off; enable with {@code stern.debug-auth-endpoint=true}. NEVER
 * enable in production — this exposes session credentials over HTTP.
 */
@RestController
public class DebugAuthController {

    private final SternAuthService authService;
    private final boolean enabled;

    public DebugAuthController(SternAuthService authService,
                               @Value("${stern.debug-auth-endpoint:false}") boolean enabled) {
        this.authService = authService;
        this.enabled = enabled;
    }

    @GetMapping("/debug/stern-auth")
    public Map<String, Object> sternAuth() {
        if (!enabled) {
            return Map.of("enabled", false,
                    "hint", "set stern.debug-auth-endpoint=true to expose for local Playwright debug");
        }
        return Map.of(
                "enabled", true,
                "token", authService.getToken() == null ? "" : authService.getToken(),
                "cookies", authService.getCookies() == null ? "" : authService.getCookies()
        );
    }
}
