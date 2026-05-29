package com.infraleap.sternmap.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Reuses the same property keys as the stern-home-leaderboards project so the
 * existing /Users/&lt;you&gt;/stern-home-leaderboards.properties file works as-is.
 */
@ConfigurationProperties(prefix = "leaderboards")
public record SternProperties(
        String sternUsername,
        String sternPassword,
        String defaultCountry,
        String defaultContinent
) {
    public SternProperties {
        if (defaultCountry == null || defaultCountry.isBlank()) defaultCountry = "DE";
        if (defaultContinent == null || defaultContinent.isBlank()) defaultContinent = "EU";
    }
}
