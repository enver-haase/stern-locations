package com.infraleap.sternmap.stern.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * One pinball machine at a venue, as returned by Stern's
 * {@code /api/v2/portal/game_locations_search/} response.
 * <p>
 * The wire shape is:
 * <pre>{@code
 * {"id": 86472, "model": {"model_type": "pro",
 *                         "title": {"name": "The Mandalorian", "code": "MAN"}}}
 * }</pre>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SternMachineV2(long id, Model model) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Model(@JsonProperty("model_type") String modelType, Title title) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Title(String name, String code) {}

    /** "The Mandalorian (Pro)" / "Godzilla (Premium)". Manufacturer is implicit (Stern). */
    public String displayName() {
        String title = model == null || model.title() == null ? "?" : model.title().name();
        String type = model == null || model.modelType() == null ? null : capitalize(model.modelType());
        return type == null ? title : title + " (" + type + ")";
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
