package com.infraleap.sternmap.stern.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Ops-visibility log emitted once Spring Boot startup is complete. The actual
 * cache warm + auth now happens in {@link SternVenueCacheService#warmAtStartup}
 * (a {@code @PostConstruct} that runs BEFORE Tomcat starts accepting
 * connections) so the first user request never blocks on a cold cache. This
 * runner just reports the resulting state.
 */
@Component
@Order(10)
public class SternSmokeTest implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(SternSmokeTest.class);

    private final SternVenueCacheService cache;
    private final SternAuthService authService;

    public SternSmokeTest(SternVenueCacheService cache, SternAuthService authService) {
        this.cache = cache;
        this.authService = authService;
    }

    @Override
    public void run(String... args) {
        log.info("Stern auth at startup: token present={}", authService.getToken() != null);
        log.info("Venue cache at startup: {} entries — {} Stern IC, {} Stern Army global, {} cross-flagged",
                cache.getAllVenues().size(), cache.getSternIcCount(),
                cache.getSternArmyCount(), cache.getCrossFlaggedCount());
    }
}
