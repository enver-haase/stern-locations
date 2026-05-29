package com.infraleap.sternmap.stern.service;

import com.infraleap.sternmap.config.SternProperties;
import com.infraleap.sternmap.pinballmap.domain.PinballMapRegion;
import com.infraleap.sternmap.pinballmap.domain.PinballMapRegionsResponse;
import com.infraleap.sternmap.pinballmap.domain.PinballMapResponse;
import com.infraleap.sternmap.pinballmap.domain.PinballMapVenue;
import com.infraleap.sternmap.stern.domain.SternMachineV2;
import com.infraleap.sternmap.stern.domain.SternVenueSearchResponse;
import com.infraleap.sternmap.stern.domain.SternVenueV2;
import com.infraleap.sternmap.stern.domain.VenueOnMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;

/**
 * Application-scoped cache of every venue rendered on the map:
 * <ul>
 *   <li><b>Stern IC</b> (global, ~4,671) — Stern's own v2 REST endpoint
 *       {@code /api/v2/portal/game_locations_with_games/}, paginated in parallel
 *       batches.</li>
 *   <li><b>Stern Army</b> (global, ~665) — Pinball Map's regions index then
 *       per-region {@code /api/v1/region/<name>/locations.json}, filtered to
 *       {@code is_stern_army=true} client-side. ~98 REST calls in parallel
 *       batches (~10–20 s). The {@code closest_by_lat_lon.json} endpoint is
 *       capped at ~50 nearest venues and can't surface global Stern Army from
 *       a single anchor, so the per-region sweep is the only pure-REST path
 *       to the full Stern Army set.</li>
 * </ul>
 *
 * <p>After both caches are loaded, Stern Army flags are <i>cross-flagged</i>
 * onto matching Stern IC venues by coordinate proximity (≤ ~1 km). Unmatched
 * Stern Army venues are appended as standalone entries.
 */
@Service
public class SternVenueCacheService {

    private static final Logger log = LoggerFactory.getLogger(SternVenueCacheService.class);
    private static final String IC_BASE = "https://cms.prd.sternpinball.io/api/v2/portal/game_locations_with_games/";
    private static final String PBM_REGIONS = "https://pinballmap.com/api/v1/regions.json";
    private static final String PBM_REGION_LOCATIONS = "https://pinballmap.com/api/v1/region/%s/locations.json";
    private static final String PBM_CLOSEST = "https://pinballmap.com/api/v1/locations/closest_by_lat_lon.json";
    private static final int PAGE_SIZE = 500;
    private static final int MAX_PARALLEL_IC = 5;
    private static final int MAX_PARALLEL_PBM = 10;
    private static final int MAX_PARALLEL_SUPPLEMENT = 20;
    /** Coord-proximity threshold for cross-flagging Stern Army onto Stern IC. ~1.1 km. */
    private static final double CROSS_FLAG_DEGREES = 0.01;
    /** Supplementary sweep: bucket size in degrees (~110 km at the equator). */
    private static final double SUPPLEMENT_BUCKET_DEGREES = 1.0;
    /** Supplementary sweep: hard cap on number of anchors queried (safety bound). */
    private static final int SUPPLEMENT_BUCKETS_MAX = 400;

    /**
     * Hard-coded city anchors covering the Stern Army venues that Stern's IC
     * catalogue has no nearby neighbour for (so the bucket-and-anchor sweep
     * would otherwise skip their area). One-time dev-time list derived from
     * the {@code SternArmyCoverageProbe} output on 2026-05-29 — when the probe
     * later reports a non-empty miss list, add the new cities here.
     * <p>
     * Pure REST at runtime: each anchor is queried via Pinball Map's
     * {@code closest_by_lat_lon.json}; the constant just tells the sweep
     * where to look.
     */
    private static final double[][] REMOTE_ANCHORS = {
            // US — small/remote towns outside Pinball Map's named regions
            {34.23, -77.95},   // Wilmington NC (Cape Fear, Backyard Arcade, Throw Coast — Leland nearby)
            {35.05, -85.31},   // Chattanooga TN
            {30.09, -94.10},   // Beaumont TX (Colorado Canyon)
            {36.95, -84.10},   // Corbin KY
            {42.87, -97.39},   // Yankton SD
            {35.17, -79.39},   // Southern Pines NC
            {32.65, -85.38},   // Opelika AL
            {58.30, -134.40},  // Juneau AK
            {39.94, -91.41},   // Quincy IL
            {31.55, -110.30},  // Sierra Vista AZ
            // AU — outside the NSW/QLD/WA Pinball Map regions
            {-36.33, 141.67},  // Nhill VIC
            {-19.26, 146.82},  // Townsville QLD
            {-42.88, 147.33},  // Hobart TAS
            // DE — Pinball Map has no German region
            {50.11, 8.68},     // Frankfurt am Main (Flipperlounge)
            {52.99, 9.12},     // Langwedel
            {54.78, 9.44},     // Flensburg (also catches Kruså DK nearby)
            // DK
            {55.49, 8.53},     // Esbjerg
            // ES
            {37.20, -3.77},    // Chauchina (Granada)
            // IE
            {53.35, -6.26},    // Dublin
            // NO
            {60.44, 5.18},     // Kleppestø (Bergen area)
            {58.14, 7.96},     // Kristiansand
            {63.43, 10.39},    // Trondheim
            // SE
            {56.95, 12.54},    // Vinberg (Falkenberg area)
            // CA
            {44.54, -78.74},   // Kawartha Lakes ON
    };

    private final SternAuthService authService;
    private final String locationHeader;
    private final WebClient cmsClient;
    private final WebClient pbmClient;

    private final AtomicReference<List<VenueOnMap>> cache = new AtomicReference<>(List.of());
    private final Object loadLock = new Object();
    private volatile boolean loaded;
    private volatile int sternIcCount;
    private volatile int sternArmyCount;
    private volatile int crossFlaggedCount;

    public SternVenueCacheService(SternAuthService authService, SternProperties props) {
        this.authService = authService;
        this.locationHeader = "{\"country\":\"" + props.defaultCountry()
                + "\",\"continent\":\"" + props.defaultContinent() + "\"}";
        this.cmsClient = WebClient.builder()
                .codecs(c -> c.defaultCodecs().maxInMemorySize(32 * 1024 * 1024))
                .defaultHeader("User-Agent",
                        "Mozilla/5.0 (X11; Ubuntu; Linux x86_64; rv:142.0) Gecko/20100101 Firefox/142.0")
                .defaultHeader("Accept", "application/json, text/plain, */*")
                .defaultHeader("Referer", "https://insider.sternpinball.com/")
                .defaultHeader("Origin", "https://insider.sternpinball.com")
                .build();
        this.pbmClient = WebClient.builder()
                .codecs(c -> c.defaultCodecs().maxInMemorySize(64 * 1024 * 1024))
                .defaultHeader("User-Agent", "stern-locations/1.0")
                .defaultHeader("Accept", "application/json")
                .build();
    }

    public List<VenueOnMap> getAllVenues() {
        ensureLoaded();
        return cache.get();
    }

    /** Pre-warms the full cache (Stern IC + global Stern Army with cross-flagging). */
    public List<VenueOnMap> warm() {
        ensureLoaded();
        return cache.get();
    }

    public int getSternIcCount() { return sternIcCount; }
    public int getSternArmyCount() { return sternArmyCount; }
    public int getCrossFlaggedCount() { return crossFlaggedCount; }

    private void ensureLoaded() {
        if (loaded) return;
        synchronized (loadLock) {
            if (loaded) return;
            Instant overallStart = Instant.now();

            // Step 1: load Stern IC + Stern Army in parallel.
            CompletableFuture<List<SternVenueV2>> icFuture =
                    CompletableFuture.supplyAsync(this::loadAllSternIc);
            CompletableFuture<List<PinballMapVenue>> armyFuture =
                    CompletableFuture.supplyAsync(this::loadAllSternArmyByRegion);

            List<SternVenueV2> ic;
            List<PinballMapVenue> army;
            try {
                ic = icFuture.get();
                army = armyFuture.get();
            } catch (Exception e) {
                log.error("Cache load failed: {}", e.toString());
                loaded = true;
                return;
            }
            sternIcCount = ic.size();
            sternArmyCount = army.size();

            // Step 2: convert + cross-flag the per-region results.
            Set<Long> perRegionIds = new HashSet<>();
            for (PinballMapVenue a : army) perRegionIds.add(a.id());
            List<VenueOnMap> merged = mergeAndCrossFlag(ic, army);

            // Step 3: supplementary sweep — Pinball Map's per-region API misses
            // any country that isn't a named region (notably Germany, France,
            // Italy, Netherlands, Belgium, Sweden, Norway, etc.). Bucket the
            // unflagged Stern IC venues into 1° cells, query Pinball Map at the
            // densest cells' centroids, and cross-flag any is_stern_army venues
            // we find. Unmatched additions get appended as standalone entries,
            // and the global Stern Army count reflects both sweeps together.
            SupplementaryResult supp = supplementaryCrossFlagSweep(merged, perRegionIds);
            crossFlaggedCount += supp.newCrossFlags();
            // sternArmyCount = total unique Stern Army venues we know about,
            // whether cross-flagged on an IC venue or standalone. Adds BOTH
            // the new cross-flags AND the new standalone venues discovered by
            // the supplementary sweep (per-region's contributions are already
            // counted by the initial `army.size()`).
            sternArmyCount += supp.newCrossFlags() + supp.unmatched().size();
            for (PinballMapVenue av : supp.unmatched()) merged.add(toVenue(av));

            merged.sort(Comparator.comparing(VenueOnMap::name, String.CASE_INSENSITIVE_ORDER));

            cache.set(merged);
            loaded = true;
            log.info("Venue cache loaded in {} ms — {} Stern IC + {} Stern Army "
                            + "({} cross-flagged on IC venues) = {} total entries",
                    Duration.between(overallStart, Instant.now()).toMillis(),
                    sternIcCount, sternArmyCount, crossFlaggedCount, merged.size());
        }
    }

    // ---- Stern IC global (Stern v2 REST) ----

    private List<SternVenueV2> loadAllSternIc() {
        String token = authService.getToken();
        if (token == null || token.isBlank()) {
            log.warn("Stern IC cache: no auth token — skipping.");
            return List.of();
        }
        SternVenueSearchResponse first = fetchIcPage(token, 0);
        if (first == null) return List.of();
        int total = first.count();
        List<SternVenueV2> all = new ArrayList<>(total);
        all.addAll(first.unwrap());
        int remaining = Math.max(0, total - PAGE_SIZE);
        if (remaining == 0) return all;
        int pages = (remaining + PAGE_SIZE - 1) / PAGE_SIZE;
        log.info("Stern IC: total={}, fetching {} more pages in parallel batches of {}",
                total, pages, MAX_PARALLEL_IC);
        List<Integer> offsets = IntStream.rangeClosed(1, pages)
                .map(i -> i * PAGE_SIZE).boxed().toList();
        for (int i = 0; i < offsets.size(); i += MAX_PARALLEL_IC) {
            List<Integer> batch = offsets.subList(i, Math.min(i + MAX_PARALLEL_IC, offsets.size()));
            List<CompletableFuture<SternVenueSearchResponse>> futures = batch.stream()
                    .map(off -> CompletableFuture.supplyAsync(() -> fetchIcPage(token, off)))
                    .toList();
            for (CompletableFuture<SternVenueSearchResponse> f : futures) {
                try {
                    SternVenueSearchResponse resp = f.get();
                    if (resp != null) all.addAll(resp.unwrap());
                } catch (Exception e) {
                    log.warn("Stern IC page fetch failed: {}", e.toString());
                }
            }
        }
        return all;
    }

    private SternVenueSearchResponse fetchIcPage(String token, int offset) {
        String url = IC_BASE + "?limit=" + PAGE_SIZE + "&offset=" + offset;
        try {
            return cmsClient.get().uri(url)
                    .header("Authorization", "Bearer " + token)
                    .header("Location", locationHeader)
                    .retrieve()
                    .bodyToMono(SternVenueSearchResponse.class)
                    .block(Duration.ofSeconds(60));
        } catch (Exception e) {
            log.warn("Stern IC fetch offset={} failed: {}", offset, e.toString());
            return null;
        }
    }

    // ---- Stern Army global (per-region Pinball Map sweep) ----

    private List<PinballMapRegion> fetchRegions() {
        try {
            PinballMapRegionsResponse resp = pbmClient.get().uri(PBM_REGIONS)
                    .retrieve()
                    .bodyToMono(PinballMapRegionsResponse.class)
                    .block(Duration.ofSeconds(30));
            if (resp == null || resp.regions() == null) return List.of();
            return resp.regions();
        } catch (Exception e) {
            log.warn("Pinball Map regions list fetch failed: {}", e.toString());
            return List.of();
        }
    }

    private List<PinballMapVenue> fetchRegionSternArmy(String regionSlug) {
        String url = String.format(PBM_REGION_LOCATIONS, regionSlug);
        try {
            PinballMapResponse resp = pbmClient.get().uri(url)
                    .retrieve()
                    .bodyToMono(PinballMapResponse.class)
                    .block(Duration.ofSeconds(30));
            if (resp == null || resp.locations() == null) return List.of();
            return resp.locations().stream()
                    .filter(v -> Boolean.TRUE.equals(v.isSternArmy()))
                    .filter(v -> v.lat() != null && v.lon() != null)
                    .toList();
        } catch (Exception e) {
            log.warn("Pinball Map region '{}' fetch failed: {}", regionSlug, e.toString());
            return List.of();
        }
    }

    private List<PinballMapVenue> loadAllSternArmyByRegion() {
        List<PinballMapRegion> regions = fetchRegions();
        if (regions.isEmpty()) return List.of();
        log.info("Pinball Map: sweeping {} regions for is_stern_army=true in parallel batches of {}",
                regions.size(), MAX_PARALLEL_PBM);
        java.util.Map<Long, PinballMapVenue> dedup = new HashMap<>();
        for (int i = 0; i < regions.size(); i += MAX_PARALLEL_PBM) {
            int hi = Math.min(i + MAX_PARALLEL_PBM, regions.size());
            List<CompletableFuture<List<PinballMapVenue>>> futures = regions.subList(i, hi).stream()
                    .map(r -> CompletableFuture.supplyAsync(() -> fetchRegionSternArmy(r.name())))
                    .toList();
            for (CompletableFuture<List<PinballMapVenue>> f : futures) {
                try {
                    for (PinballMapVenue v : f.get()) dedup.putIfAbsent(v.id(), v);
                } catch (Exception e) {
                    log.warn("Region fetch task failed: {}", e.toString());
                }
            }
        }
        return new ArrayList<>(dedup.values());
    }

    // ---- Merge + cross-flag ----

    private List<VenueOnMap> mergeAndCrossFlag(List<SternVenueV2> ic, List<PinballMapVenue> army) {
        List<VenueOnMap> icMerged = new ArrayList<>(ic.size());
        for (SternVenueV2 v : ic) icMerged.add(toVenue(v));

        Set<Long> matchedArmyIds = new HashSet<>();
        int crossed = 0;
        for (PinballMapVenue av : army) {
            int matchIdx = findClosestIcIndex(icMerged, av.latAsDouble(), av.lonAsDouble(),
                    CROSS_FLAG_DEGREES);
            if (matchIdx >= 0) {
                icMerged.set(matchIdx, icMerged.get(matchIdx).withSource(VenueOnMap.Source.STERN_ARMY));
                matchedArmyIds.add(av.id());
                crossed++;
            }
        }
        crossFlaggedCount = crossed;

        // Append Stern Army venues that didn't match any Stern IC venue
        List<VenueOnMap> standaloneArmy = army.stream()
                .filter(a -> !matchedArmyIds.contains(a.id()))
                .map(this::toVenue)
                .toList();

        List<VenueOnMap> all = new ArrayList<>(icMerged.size() + standaloneArmy.size());
        all.addAll(icMerged);
        all.addAll(standaloneArmy);
        all.sort(Comparator.comparing(VenueOnMap::name, String.CASE_INSENSITIVE_ORDER));
        return all;
    }

    private static int findClosestIcIndex(List<VenueOnMap> ic, double lat, double lon, double maxDeg) {
        int best = -1;
        double bestDist = Double.MAX_VALUE;
        for (int i = 0; i < ic.size(); i++) {
            VenueOnMap v = ic.get(i);
            double dLat = v.lat() - lat;
            double dLon = v.lon() - lon;
            double d = Math.sqrt(dLat * dLat + dLon * dLon);
            if (d < maxDeg && d < bestDist) {
                bestDist = d;
                best = i;
            }
        }
        return best;
    }

    /**
     * Result of the supplementary sweep — both the count of new cross-flags
     * and the set of Stern Army venues found that did NOT match a Stern IC
     * neighbour (these get appended as standalone entries so the global Stern
     * Army count is correct).
     */
    private record SupplementaryResult(int newCrossFlags, List<PinballMapVenue> unmatched) {}

    /**
     * Supplementary sweep covering non-region countries. Buckets unflagged
     * Stern IC venues into {@value SUPPLEMENT_BUCKET_DEGREES}-degree geographic
     * cells, then queries {@code closest_by_lat_lon.json} at the centroid of
     * the densest cells (up to {@value SUPPLEMENT_BUCKETS_MAX}). Cross-flags any
     * {@code is_stern_army=true} venues in the responses, returning both the
     * count of new IC cross-flags and the Stern Army venues that did NOT match
     * any IC venue (so the caller can add them as standalone entries).
     */
    private SupplementaryResult supplementaryCrossFlagSweep(List<VenueOnMap> merged,
                                                            Set<Long> alreadyKnownArmyIds) {
        // Identify unflagged IC venues + bucket their coords
        java.util.Map<Long, int[]> bucketCounts = new HashMap<>();
        java.util.Map<Long, double[]> bucketCentroids = new HashMap<>();
        for (VenueOnMap v : merged) {
            if (!v.isSternIc() || v.isSternArmy()) continue;
            long key = bucketKey(v.lat(), v.lon());
            bucketCounts.computeIfAbsent(key, k -> new int[]{0})[0]++;
            double[] centroid = bucketCentroids.computeIfAbsent(key, k -> new double[]{0, 0});
            centroid[0] += v.lat();
            centroid[1] += v.lon();
        }
        if (bucketCounts.isEmpty()) return new SupplementaryResult(0, List.of());

        // Anchor for every non-empty cell, capped at SUPPLEMENT_BUCKETS_MAX
        // densest cells (densest first, so single-venue cells go last and
        // get clipped only if the total exceeds the safety bound).
        List<java.util.Map.Entry<Long, int[]>> ordered = bucketCounts.entrySet().stream()
                .sorted((a, b) -> b.getValue()[0] - a.getValue()[0])
                .limit(SUPPLEMENT_BUCKETS_MAX)
                .toList();
        List<double[]> anchors = new ArrayList<>();
        for (var e : ordered) {
            double[] sum = bucketCentroids.get(e.getKey());
            int n = e.getValue()[0];
            anchors.add(new double[]{sum[0] / n, sum[1] / n});
        }
        // Static remote-area anchors for cities the bucket-of-IC-venues approach
        // can't reach (Stern Army venues that have no Stern IC neighbour, e.g.
        // Hobart, Juneau, Trondheim, German cities — see REMOTE_ANCHORS).
        for (double[] a : REMOTE_ANCHORS) anchors.add(new double[]{a[0], a[1]});

        log.info("Supplementary Stern Army sweep: {} unflagged IC venues in {} cells + "
                        + "{} static remote-area anchors; querying Pinball Map at {} anchors (parallel {})",
                bucketCounts.values().stream().mapToInt(a -> a[0]).sum(),
                bucketCounts.size(), REMOTE_ANCHORS.length,
                anchors.size(), MAX_PARALLEL_SUPPLEMENT);

        // Parallel-query Pinball Map at each anchor and collect is_stern_army venues
        java.util.Map<Long, PinballMapVenue> sa = new HashMap<>();
        for (int i = 0; i < anchors.size(); i += MAX_PARALLEL_SUPPLEMENT) {
            int hi = Math.min(i + MAX_PARALLEL_SUPPLEMENT, anchors.size());
            List<CompletableFuture<List<PinballMapVenue>>> futures = anchors.subList(i, hi).stream()
                    .map(a -> CompletableFuture.supplyAsync(() -> fetchClosestSternArmy(a[0], a[1])))
                    .toList();
            for (CompletableFuture<List<PinballMapVenue>> f : futures) {
                try {
                    for (PinballMapVenue v : f.get()) sa.putIfAbsent(v.id(), v);
                } catch (Exception e) {
                    log.warn("Supplementary anchor query failed: {}", e.toString());
                }
            }
        }
        log.info("Supplementary sweep returned {} unique is_stern_army venues", sa.size());

        // Cross-flag onto the merged list; collect unmatched supplementary Stern
        // Army venues (these are NEW Stern Army venues not already in the
        // per-region results — they'd otherwise be silently dropped).
        int crossed = 0;
        List<PinballMapVenue> unmatched = new ArrayList<>();
        for (PinballMapVenue av : sa.values()) {
            int idx = findClosestIcIndex(merged, av.latAsDouble(), av.lonAsDouble(),
                    CROSS_FLAG_DEGREES);
            if (idx >= 0 && !merged.get(idx).isSternArmy()) {
                merged.set(idx, merged.get(idx).withSource(VenueOnMap.Source.STERN_ARMY));
                crossed++;
            } else if (idx < 0 && !alreadyKnownArmyIds.contains(av.id())) {
                unmatched.add(av);
            }
        }
        return new SupplementaryResult(crossed, unmatched);
    }

    private List<PinballMapVenue> fetchClosestSternArmy(double lat, double lon) {
        String url = PBM_CLOSEST + "?lat=" + lat + "&lon=" + lon
                + "&send_all_within_distance=100";
        try {
            PinballMapResponse resp = pbmClient.get().uri(url)
                    .retrieve()
                    .bodyToMono(PinballMapResponse.class)
                    .block(Duration.ofSeconds(20));
            if (resp == null || resp.locations() == null) return List.of();
            return resp.locations().stream()
                    .filter(v -> Boolean.TRUE.equals(v.isSternArmy()))
                    .filter(v -> v.lat() != null && v.lon() != null)
                    .toList();
        } catch (Exception e) {
            log.debug("Supplementary anchor ({},{}) failed: {}", lat, lon, e.toString());
            return List.of();
        }
    }

    private static long bucketKey(double lat, double lon) {
        long b = (long) Math.floor(lat / SUPPLEMENT_BUCKET_DEGREES);
        long c = (long) Math.floor(lon / SUPPLEMENT_BUCKET_DEGREES);
        return (b << 32) ^ (c & 0xFFFFFFFFL);
    }

    private VenueOnMap toVenue(SternVenueV2 v) {
        List<VenueOnMap.Machine> machines = v.machines() == null ? List.of()
                : v.machines().stream()
                .map(m -> new VenueOnMap.Machine(m.displayName(), m.id()))
                .toList();
        return new VenueOnMap(
                v.id(), v.name(), v.fullAddress(), v.lat(), v.lon(),
                v.websiteUrl(), v.locationTypeName(), machines,
                EnumSet.of(VenueOnMap.Source.STERN_IC));
    }

    private VenueOnMap toVenue(PinballMapVenue v) {
        List<VenueOnMap.Machine> machines = v.machineNames() == null ? List.of()
                : v.machineNames().stream()
                .map(name -> new VenueOnMap.Machine(name, null))
                .toList();
        return new VenueOnMap(
                v.id(), v.name(), v.fullAddress(), v.latAsDouble(), v.lonAsDouble(),
                v.website(), null,
                machines,
                EnumSet.of(VenueOnMap.Source.STERN_ARMY));
    }
}
