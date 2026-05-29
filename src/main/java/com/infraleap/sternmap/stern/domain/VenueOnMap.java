package com.infraleap.sternmap.stern.domain;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Unified view-model for a venue rendered on the map.
 */
public record VenueOnMap(
        long id,
        String name,
        String address,
        double lat,
        double lon,
        String websiteUrl,
        String type,
        List<Machine> machines,
        Set<Source> sources
) {
    public enum Source { STERN_IC, STERN_ARMY }

    /**
     * One pinball machine at a venue. {@code displayName} is always populated.
     * {@code sternMachineId} is set when the machine record came from Stern's
     * native v2 catalogue and can therefore be used to fetch top-5 high scores
     * via {@code /api/v1/portal/game_machine_high_scores/?machine_id=<id>}.
     * For Pinball-Map-sourced Stern Army venues we only know the model name,
     * so {@code sternMachineId} is null and high-score lookup isn't possible.
     */
    public record Machine(String displayName, Long sternMachineId) {
        public boolean hasSternId() { return sternMachineId != null; }
    }

    public VenueOnMap {
        sources = sources == null ? EnumSet.noneOf(Source.class) : EnumSet.copyOf(sources);
        machines = machines == null ? List.of() : List.copyOf(machines);
    }

    public boolean isSternIc() { return sources.contains(Source.STERN_IC); }
    public boolean isSternArmy() { return sources.contains(Source.STERN_ARMY); }

    public int machineCount() { return machines.size(); }

    public VenueOnMap withSource(Source extra) {
        if (sources.contains(extra)) return this;
        EnumSet<Source> ns = EnumSet.copyOf(sources);
        ns.add(extra);
        return new VenueOnMap(id, name, address, lat, lon, websiteUrl, type, machines, ns);
    }

    public VenueOnMap withAddress(String newAddress) {
        if (java.util.Objects.equals(address, newAddress)) return this;
        return new VenueOnMap(id, name, newAddress, lat, lon, websiteUrl, type, machines, sources);
    }
}
