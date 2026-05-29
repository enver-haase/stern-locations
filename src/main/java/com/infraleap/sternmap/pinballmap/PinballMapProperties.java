package com.infraleap.sternmap.pinballmap;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Pinball Map integration is <b>opt-in</b>. By default the app uses only
 * Stern's reverse-engineered API and shows the single closest Stern Insider
 * Connected venue — which is all Stern's endpoints can actually surface to
 * any caller (the supposed venue-listing endpoint {@code /portal/game_locations/}
 * returns 405 GET even with a valid Bearer token, confirmed 2026-05-29).
 *
 * <p>Set {@code pinballmap.enabled=true} to augment with the public Pinball
 * Map community API. With {@code pinballmap.stern-army-only=true} the result
 * is filtered to venues whose {@code is_stern_army} flag is true — which is
 * exactly the dataset Stern's own marketing page
 * {@code sternpinball.com/stern-army-locator/} embeds.
 */
@ConfigurationProperties(prefix = "pinballmap")
public record PinballMapProperties(boolean enabled, boolean sternArmyOnly) {}
