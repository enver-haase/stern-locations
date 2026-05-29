package com.infraleap.sternmap.ui;

import com.vaadin.flow.component.map.configuration.style.Icon;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * SVG marker icons baked as inline {@code data:} URLs.
 * <ul>
 *   <li>{@link #youMarker()} — map-pin with person silhouette, for the user's GPS.</li>
 *   <li>{@link #sternIcMarker()} — stick figure playing pinball, in the original
 *       artwork colours (dark green tile, yellow/orange machine, white figure)
 *       for venues from Stern's native v2 endpoint.</li>
 *   <li>{@link #sternArmyMarker()} — same artwork in USA colours (navy tile,
 *       red machine, white figure) for Stern Army and crossover (both Stern IC
 *       AND Stern Army) venues.</li>
 * </ul>
 * Anchored bottom-centre so the visual tile sits on the geographic coordinate.
 */
public final class MapIcons {

    private MapIcons() {}

    private static final String YOU_SVG =
            "<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 32 40' width='32' height='40'>"
            + "<path d='M16 2C8.27 2 2 8.27 2 16c0 10.5 14 22 14 22s14-11.5 14-22c0-7.73-6.27-14-14-14z'"
            + " fill='#1976d2' stroke='white' stroke-width='2'/>"
            + "<circle cx='16' cy='13' r='3.5' fill='white'/>"
            + "<path d='M10.5 23c0-3 2.5-5 5.5-5s5.5 2 5.5 5z' fill='white'/>"
            + "</svg>";

    /**
     * Stick figure leaning into a pinball machine. Side-on view, three flat
     * fills (background tile, machine, figure) so the silhouette reads at
     * map-marker scale (~32 px square). The bottom-centre anchor sits on the
     * geographic point.
     */
    private static String playerAtMachineSvg(String bg, String machine, String figure) {
        return "<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 32 36' width='32' height='36'>"
                // soft shadow under the tile
                + "<ellipse cx='16' cy='34.5' rx='9' ry='1.2' fill='black' opacity='0.2'/>"
                // background tile (rounded square)
                + "<rect x='1' y='1' width='30' height='30' rx='3.5' fill='" + bg + "'/>"
                // machine: cabinet body
                + "<rect x='17' y='14' width='12' height='13' fill='" + machine + "'/>"
                // machine: backbox (rear/top section, taller than cabinet)
                + "<rect x='18.5' y='5' width='10.5' height='10' fill='" + machine + "'/>"
                // machine: legs (under cabinet)
                + "<rect x='18' y='27' width='2' height='3' fill='" + machine + "'/>"
                + "<rect x='25.5' y='27' width='2' height='3' fill='" + machine + "'/>"
                // stick figure: head
                + "<circle cx='8.5' cy='10.5' r='2.6' fill='" + figure + "'/>"
                // stick figure: torso/arms leaning into the machine
                + "<path d='M5.5 13 L15.5 17 L14 21 L4 19 Z' fill='" + figure + "'/>"
                // stick figure: back leg
                + "<path d='M5.5 20 L4 30 L7 30 L8.5 21 Z' fill='" + figure + "'/>"
                // stick figure: forward leg (bent toward machine)
                + "<path d='M10 21 L11.5 30 L14.5 30 L12.5 21 Z' fill='" + figure + "'/>"
                + "</svg>";
    }

    public static Icon youMarker() {
        return iconFromSvg(YOU_SVG);
    }

    /** Default Stern IC marker — the actual artwork (stick figure + pinball
     *  machine) served from {@code /pin-pinball-green.png} as a Spring static
     *  resource so all markers reference the same browser-cached URL. */
    public static Icon sternIcMarker() {
        return iconFromUrl("/pin-pinball-green.png");
    }

    /** Stern Army marker — Uncle Sam top hat (cropped from the 3-hat source). */
    public static Icon sternArmyMarker() {
        return iconFromUrl("/pin-pinball-usa.png");
    }

    /** Crossover marker (Stern IC ∩ Stern Army) — Uncle Sam hat with the
     *  black stickman+machine silhouette composited centred on the crown. */
    public static Icon crossoverMarker() {
        return iconFromUrl("/pin-pinball-crossover.png");
    }

    private static Icon iconFromUrl(String url) {
        Icon.Options opts = new Icon.Options();
        opts.setSrc(url);
        opts.setAnchor(new Icon.Anchor(0.5, 1.0));
        // The source JPG is 260×260; render at ~36 px so the marker matches
        // the SVG marker scale.
        opts.setScale(0.14);
        return new Icon(opts);
    }

    private static Icon iconFromSvg(String svg) {
        String b64 = Base64.getEncoder().encodeToString(svg.getBytes(StandardCharsets.UTF_8));
        Icon.Options opts = new Icon.Options();
        opts.setSrc("data:image/svg+xml;base64," + b64);
        opts.setAnchor(new Icon.Anchor(0.5, 1.0));
        opts.setScale(1.0);
        return new Icon(opts);
    }
}
