package com.infraleap.sternmap.stern.service;

import com.infraleap.sternmap.stern.domain.VenueOnMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Exercises the live data paths at startup and warms the global venue cache so
 * the first browser request doesn't pay the parallel-pagination cost.
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
        boolean authed = authService.login();
        log.info("Stern auth at startup: {} (token present={})",
                authed ? "OK" : "FAILED", authService.getToken() != null);

        log.info("Warming venue cache: global Stern IC v2 pagination + global Stern Army per-region REST sweep…");
        List<VenueOnMap> all = cache.warm();
        log.info("Cache warm: {} entries total — {} Stern IC, {} Stern Army global, {} cross-flagged",
                all.size(), cache.getSternIcCount(), cache.getSternArmyCount(),
                cache.getCrossFlaggedCount());
    }
}
