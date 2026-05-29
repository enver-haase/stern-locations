# Stern Locations

A Vaadin Platform 25.2 application that plots every **Stern Insider Connected**
venue near you on a map, with the machines on location and the active
tournaments at each venue. Data comes from Stern's own backend, discovered by
reverse-engineering both the **Stern IC** iOS app and the **Insider Connected**
web SPA.

In Berlin Mitte the app surfaces 4 native Stern IC venues: Gamestate Potsdamer
Platz, Berolina Bowling Lounge, Bata Bar & Billards, and starcade — with their
machine rosters inline (The Mandalorian Pro, Elvira's House of Horrors Premium,
Godzilla Premium, Dungeons & Dragons Pro, TMNT Pro …).

Optional: `pinballmap.enabled=true` augments the result with the public Pinball
Map community API for **all** nearby pinball venues (not just Stern IC), at the
cost of mixing in non-Stern places.

## What you see when you run it

1. Browser asks for your GPS location — grant it.
2. Map recenters on you. The "You" marker is a person silhouette; venue
   markers are stylised pinball machines.
3. We call Stern's `game_locations_search` v2 endpoint with your coordinates;
   each returned venue is plotted with its inline machine list.
4. Sidebar lists each venue: type (Arcade, Bowling Alley, Billiards / Pool
   Hall, …), full address, machines, and a "Load Stern tournaments" link.
5. Click a venue marker on the map → sidebar scrolls to that venue's card
   and highlights it. Same on click of the card.
6. Clicking "Load Stern tournaments" lazy-fetches the tournament names for
   that venue's coordinates via the older `nearby_leaderboards` endpoint.

## Architecture

```
com.infraleap.sternmap
├── Application.java                       Spring Boot + AppShellConfigurator
├── config/SternProperties.java            @ConfigurationProperties(prefix="leaderboards")
├── stern/                                 Reverse-engineered Stern IC API — primary data
│   ├── domain/
│   │   ├── SternVenueV2                  v2 venue: id, address, lat/lng, type, machines, ...
│   │   ├── SternMachineV2                machine: id, model.{type, title.name, title.code}
│   │   ├── SternVenueSearchResponse      wraps {count, locations:[{location:{...}}]}
│   │   ├── NearbyLeaderboardsResponse    legacy v1 endpoint used for tournament names only
│   │   ├── Leaderboard, LbLocation       legacy v1 wire records
│   │   └── SternVenueSpot                view-model for the legacy single-venue mode
│   └── service/
│       ├── SternAuthService              login w/ dynamic Next.js server-action hash discovery
│       ├── SternVenueService             findVenues(lat,lon) → list of SternVenueV2 + machines
│       ├── SternLocationService          findTournamentsAt(lat,lon) — legacy v1 leaderboard names
│       ├── SternSmokeTest                CommandLineRunner — exercises live data paths
│       ├── GameLocationsProbe            diagnostic; default off (stern.probe-game-locations=true)
│       └── IcLocationsProbe              diagnostic; default off (stern.probe-ic-locations=true)
├── pinballmap/                            OPT-IN augmentation
│   ├── PinballMapProperties              enabled=false (default), sternArmyOnly=false (default)
│   ├── domain/{PinballMapVenue, PinballMapResponse}
│   └── service/PinballMapService         community API: 47 venues in Berlin including non-IC
└── ui/
    ├── MapIcons.java                      inline SVG data-URL icons (person + pinball machine)
    └── view/MapView.java                  @Route(""), branches on pinballmap.enabled
```

## How we found the native venue endpoint

The `/portal/game_locations/` endpoint on `cms.prd.sternpinball.io` returns
`405 Method Not Allowed` for GET both with and without auth — its public
nephew that returns the same data lives on a different route, on v2, and
isn't visible by guessing or stringing the Flutter binary.

The trail led through the Insider Connected web frontend:

1. `https://insider.sternpinball.com/ic/locations` redirects unauthenticated
   visitors to `/login` but, when authed, returns a **Vite-built SPA shell**
   distinct from the Next.js marketing pages — script tag
   `/assets/index-BAc7x18_.js`.
2. Downloading that 884 KB JS bundle and grepping for `portal/` URLs reveals
   the working endpoint names: `portal/game_locations_with_games/`,
   `portal/game_locations_search/`, `portal/user_game_locations/`,
   `portal/game_location_stats_by_group/`, plus several leaderboard endpoints.
3. The bundle's request helpers carry `version:2` and `api:At`, where
   `At="https://cms.prd.sternpinball.io/api/"` — so the full URL is
   `cms.prd.sternpinball.io/api/v2/portal/game_locations_search/`.
4. Probing with the existing Bearer token: **200 OK**, 4 Berlin venues with
   machines inline.

### The endpoints we use

```
GET https://cms.prd.sternpinball.io/api/v2/portal/game_locations_search/?latitude=<lat>&longitude=<lon>
    → { count, locations: [{ location: { id, name, address, lat/lon, machines:[…], … } }] }
    Proximity-filtered. Requires Bearer token. 4 venues in Berlin Mitte.

GET https://cms.prd.sternpinball.io/api/v1/portal/nearby_leaderboards/?latitude=<lat>&longitude=<lon>
    → { count, results: [{ name (tournament), lb_location, distance, … }] }
    Public — no auth. We call it with each venue's coords to attach
    tournament names lazily. Note: the 10 names returned are network-wide,
    not venue-specific (same list shows for every venue).
```

The legacy v1 `nearby_leaderboards` is still useful for tournament data even
though it only returns one venue per call. v2 `game_locations_search` is the
real venue listing.

## Endpoints that don't work (probed exhaustively 2026-05-29, all with auth)

- `/portal/game_locations/` — **405 GET** (POST-only; operator CRUD).
- `/portal/game_locations_with_games/` (v1) — 404. v2 works (`?location=<id>`
  for a single venue, or paginated for global 4,671-venue list).
- `/portal/game_locations_search/` (v1) — 404. v2 works.
- `/portal/lb_locations/`, `/portal/locations/`, `/portal/nearby_locations/`,
  `/portal/venues/`, `/portal/find_machine/`, `/portal/find_a_machine/`,
  `/portal/nearby_machines/` — 404 across v1 and v2 on both `cms.` and `api.`.
- `/portal/leaderboards/titles/?location=<id>`,
  `/portal/leaderboards/machines/?location=<id>` — 404 on this base.
- `/portal/user_game_locations/?group_type=home` — works but returns only the
  authenticated user's own home-group machines, not a public venue listing.

## Auth: dynamic Next-Action discovery

Stern's `insider.sternpinball.com/login` is a Next.js *Server Action* —
`POST /login` with a content-hashed `Next-Action` header. The hash rotates
whenever the bundle is rebuilt. `SternAuthService.resolveLoginActionHash()`
finds the current hash dynamically:

1. `GET https://insider.sternpinball.com/login`
2. Enumerate every `/_next/static/chunks/*.js` referenced by the page.
3. Scan each chunk for the fingerprint
   `createServerReference)("<40-hex>",…,"performLogin")`.
4. Use that hash for the actual login POST.

The hash is cached for the lifetime of the service; the JWT cookie itself
expires every ~10 min and is re-fetched on demand.

## Pinball Map augmentation (opt-in)

`https://pinballmap.com/api/v1/locations/closest_by_lat_lon.json` —
community-maintained, no auth. Returns 47 venues within 50 mi of Berlin Mitte
including all the non-Stern places (Birgit & Bier, Blauer Affe, Linie 1, …).
Stern's own `sternpinball.com/stern-army-locator/` page server-side-inlines
Pinball Map data filtered to `is_stern_army=true` — 665 venues globally, 7 in
Germany, 0 in Berlin.

Disable by default (set in `application.properties`). Enable with:

```properties
pinballmap.enabled=true
# Optional: filter to is_stern_army=true to mirror sternpinball.com's locator:
pinballmap.stern-army-only=true
```

## Running it

The properties file is the same one `stern-home-leaderboards` uses, so you
don't need to copy anything:

```properties
# ~/stern-home-leaderboards.properties
server.port=8888
leaderboards.stern-username=...
leaderboards.stern-password=...
```

Then:

```bash
mvn spring-boot:run
# wait for "Application running at http://localhost:8888/"
# open that URL → grant geolocation
```

Default-mode startup output:

```
Stern auth at startup: OK (token present=true)
Stern v2 venue search at (52.505, 13.3805)
Stern v2 search returned 4 venues near (52.505, 13.3805)
Stern v2 OK — 4 venues with 6 machines total:
  - Gamestate Potsdamer Platz Berlin (Arcade) [1 machines: The Mandalorian (Pro)]
  - Berolina Bowling Lounge (Bowling Alley) [2 machines: Elvira's House of Horrors (Premium), Godzilla (Premium)]
  - Bata Bar & Billards (Billiards / Pool Hall) [1 machines: Godzilla (Premium)]
  - starcade (Arcade) [2 machines: Dungeons & Dragons (Pro), Teenage Mutant Ninja Turtles (Pro)]
Pinball Map disabled (set pinballmap.enabled=true to augment with the community API).
```

## Version note

`pom.xml` pins Vaadin Platform `25.2.0-beta1`. The Map component is commercial
and requires a Vaadin developer license to build and run.
