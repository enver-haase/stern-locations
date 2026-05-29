package com.infraleap.sternmap.stern.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Response shape of {@code GET /api/v1/portal/game_machine_high_scores/?machine_id=<id>}.
 * <p>
 * Returns the top 5 perpetual scores for one physical Stern Insider Connected
 * machine. Each entry is a {@link Entry} with the score and the player's
 * display info (initials, username, avatar).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MachineHighScoreResponse(
        boolean success,
        @JsonProperty("high_score") List<Entry> highScore
) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Entry(String score, User user) {
        /** Score as a long for sorting; returns 0 if unparseable. */
        public long scoreAsLong() {
            try { return Long.parseLong(score); } catch (Exception e) { return 0L; }
        }

        /** "1,234,567,890" — group separators every 3 digits. */
        public String scoreFormatted() {
            try {
                return String.format("%,d", Long.parseLong(score));
            } catch (Exception e) { return score == null ? "?" : score; }
        }

        public String displayName() {
            String u = user == null ? null : user.username();
            String i = user == null ? null : user.initials();
            if (u != null && !u.isBlank()) return u;
            if (i != null && !i.isBlank()) return i;
            return "—";
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record User(String id, String username, String initials,
                       @JsonProperty("avatar_url") String avatarUrl,
                       @JsonProperty("background_color_hex") String backgroundColorHex) {}
}
