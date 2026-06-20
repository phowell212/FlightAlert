# Flight Alert Agent Rules

Flight Alert is a drone situational-awareness app. Refactors must preserve the current user experience and styling unless a visible difference fixes a real bug or honesty issue.

These rules are ordered by priority. If rules conflict, follow the higher-priority rule.

## 1. No Pretending

Never fake aircraft, map, route, photo, alert, altitude, location, or source data.

If real data is unavailable, show `Unavailable`, show `Loading`, hide the feature, disable the control, or label uncertainty clearly. Never use pregenerated aircraft, location, route, tile, or path data as if it were live.

## 2. Creative Reinvention, Never The Axe

Preserve the visible and user-facing result first, then reinvent the implementation underneath until it is fast.

When performance is bad, solve the expensive work directly with profiling, caching, batching, precomputation, better data structures, retained real imagery/data, smarter request scheduling, or another equivalent implementation. Do not win frame time by removing, hiding, delaying, shrinking, darkening, simplifying, skipping, or approximating accepted visuals, features, information density, layer content, aircraft morphing, map labels, tile transitions, details, photos, or alerts unless the user explicitly asks for that tradeoff.

If a protected visual or feature is a real bottleneck, replace its implementation with one that produces the same accepted pixels, information, timing honesty, and interaction behavior faster.

Optimization bands are diagnostic tools, not tradeoff zones. If a change improves one zoom band, map mode, gesture, or layer state by moving jank, flicker, missing information, or visual inconsistency into another band/state, reject it and keep digging for the root cause. A real optimization reduces or eliminates the underlying expensive work consistently while preserving the accepted user experience everywhere.

## 3. Real Sources Only

Map tiles must come from real map providers such as OpenStreetMap, CARTO, or real satellite imagery providers.

Aircraft traffic must come from live aircraft APIs or documented live feed formats. Flight paths must come from real trace/path APIs after an aircraft is selected. Do not accumulate app-session aircraft positions and call that a real flight path.

## 4. Safety Bias

Accuracy matters more than impressive visuals. Vertical separation is critical.

If altitude, location, source freshness, or feed data is missing, do not claim an aircraft is safe or inside/outside an alert volume unless the calculation is supported.

## 5. Alerts

Alerts are based on a 3D volume around the user: horizontal distance plus vertical separation.

Notify when an aircraft enters or leaves the configured alert volume. Do not spam repeat notifications for the same aircraft every interval. The persistent extreme-priority notification means non-clearable while active, self-clearing when the extreme-priority list empties, and text-updatable while active; it should only beep or buzz on the first transition from no extreme-priority aircraft to at least one extreme-priority aircraft.

## 6. UI Honesty

Anything that looks like a button must be interactable.

Unavailable or unverified features must be hidden, disabled, or labeled honestly. Do not show fake radar graphics or fake aviation overlays. Attribution can move later, but do not misrepresent providers.

## 7. Layout Quality

No text clipping, hidden labels, or strange overlaps.

Support portrait, landscape, folding devices, resizing, and emulator hardware input. Text should stay physically consistent unless constraints require smart adaptation. Back should close overlays/screens before leaving the app.

## 8. Map Interaction

Panning, pinch zoom, wheel zoom, and keyboard zoom should feel smooth.

Aircraft sprites may move smoothly every frame using interpolation from speed, heading, and last report. Do not request aircraft positions every frame. Sprite/dot morphing, icon sizing, outlines, satellite tile transitions, layer readability, aircraft details/photo loading states, and other accepted UI fixes are protected behavior. Optimize their implementations; do not optimize them out.

No aircraft sprite, dot, outline, or symbol gate may create a sudden apparent size jump anywhere in the zoom range. Transition starts, outline introduction, dot fading, symbol entry, and selected/priority styling must be continuous curves tied to zoom/appearance state.

## 9. Flight Paths

Only show the path button when a real usable path was retrieved.

The path must represent the selected aircraft's actual current flight/trace, not stale old legs. If the trace endpoint stops before the live sprite position, extend the visible trail to the current live position only when the live report is fresh. Fix path-jump root causes instead of hiding bugs with arbitrary implausible-jump filters.

## 10. Aircraft Details And Photos

Try exact aircraft photos first from real aircraft-photo sources.

If exact photo lookup fails, a representative same make/model photo is acceptable only with a clear "not this exact aircraft" note. Search-engine fallback photos must be labeled investigable and include source/proof view buttons. Claimed make/model/owner data must come from official or documented sources.

## 11. Military Handling

Only show military-specific stats if the aircraft is actually tagged military.

Military origin/base claims require real flight-origin and aerodrome/source data. Otherwise show unavailable or non-military status honestly. Registry-country fallback may use real ICAO 24-bit allocation ranges when registration prefix data is unavailable, but it must be labeled as an allocation-derived result.

## 12. Codebase / Repo Discipline

Use Kotlin and work directly in the Android Studio project structure.

Keep one real project: one manifest, one root Gradle setup, and no duplicate generated/source projects. Do not commit Android Studio local machine-state churn, build outputs, temporary screenshots, secrets, `local.properties`, generated junk, stale comparison artifacts, or agent scratch output.

## 13. Testing

Build before release. Use lint and unit/instrumented tests when the touched area warrants them.

Use emulators for visual inconsistency, layout, aspect-ratio, clipping, and theme checks. Use physical devices for optimization, timing, responsiveness, smoothness, and performance tests when available.

For map performance work, automated and manual panning/zooming tests must exercise dense aircraft regions around a major city center with major airports/corridors in the United States, Europe, or a Mexico City-class North American megacity. Primary optimizer evidence should prefer inland major-airport targets such as Dallas-Fort Worth, Atlanta, Denver, Phoenix, or Las Vegas. Full zoom-sweep evidence must use targets whose continent/wide view stays land/traffic centered. The test algorithm must keep pinch and pan gestures bounded around the target map area; choosing a valid start point is not enough if the scripted gestures drift into water or empty terrain. Do not test over open ocean, lake/sea drift, empty polar regions, unintended rural/upstate drift, upper Canada, smaller fallback metros, or the launcher/home screen; if a visible run drifts into those areas, discard that run as evidence and rerun.

Include quick anchored pinch gestures, smooth slow anchored pinch gestures, horizontal pans, vertical pans, diagonal pans, slight circular or elliptical map motion, and combined pinch-while-panning gestures over visible aircraft at multiple zoom levels. Pinch tests must keep the target city/traffic focus anchored so zooming back in returns to the same target.

Performance tests must separate results by scale band instead of hiding them inside one average. Always include known difficult bands around the 100 mi scale-bar range and the 200-500 mi scale-bar range, plus local, country, and continent/global checks in both street and satellite modes when the change could affect map/traffic rendering.

At least one hotspot-finding test in an optimization cycle should sweep through the practical zoom range in and out, pan at representative zoom levels, and pan while zooming. The artifacts must make frame timing/FPS data correlatable to the active zoom band or phase. Treat those hotspots as starting points for broader root-cause investigation; do not patch a single band by reducing information, visual quality, morph continuity, outlines, labels, or layer fidelity.

For satellite tile-load work, include a fast close-to-wide zoom-out test that starts at close/high-detail satellite imagery over an inland major-airport traffic target, then quickly pinches out to country/continent scale and measures how long exact visible tiles remain unloaded. Use this to optimize real tile catch-up time; do not judge satellite load speed only from slow zooms or at-rest screenshots.

For visual completeness checks, compare against the latest valid feature-complete UI state. Capture or inspect the app at rest, right as movement starts, and during active movement so panning/zooming-only flashes, outline changes, tile flicker, or morph jumps are treated as regressions.

Every meaningful performance test should report frame-time and FPS evidence, not only subjective smoothness, screenshots, or a single jank percentage. Capture or summarize `gfxinfo`/FrameTimeline data when available, including p50/p95/p99 frame timing, mean/present FPS or FPS-equivalent cadence, produced FPS, Android jank percentage, and 120Hz present-drop or latency-miss percentage. Treat jank counters as supporting evidence, not the sole optimization target.

During device tests, accept runtime permission prompts that are specifically requested by the Flight Alert app so permission dialogs do not invalidate smoothness or visual captures.

When taking Android screenshots from PowerShell, prefer `adb shell screencap -p /sdcard/name.png` followed by `adb pull /sdcard/name.png local.png`. Direct `adb exec-out screencap -p > file.png` can produce PNGs that local image viewers fail to decode.

## 14. Code Organization

Flight Alert code should read like objects doing jobs.

`FlightMapView` is the coordinator. It should coordinate map state, selection, draw order, and user input. Feature objects should own feed parsing, route validation, photo lookup, impact scoring, settings math, motion projection, and feature-specific business rules.

Prefer small objects with names that explain their job, such as `CurrentRouteValidator`, `AircraftPositionProjector`, or `AircraftRoutePresenter`. New feature work should usually start as a new object/file with a narrow public method, then be called by the coordinator.

Keep settings and tuning values in explicit settings files. Give major features their own files or small packages, but do not create one-folder islands for tiny concepts.

## 15. Agent Workflow

Before changing code, read this file and `docs/code-organization.md`.

For optimization work, use a connected physical phone when one is available. Preserve a pre-optimization copy or branch before making changes. Compare against that copy or the latest accepted baseline so the app displays the same information and preserves the same user experience except for lag reductions and real bug fixes.

Use subagents only for low-conflict work with a clearly bounded scope, especially read-only history/code research, test-harness additions, standalone verification helpers, and small files that can be owned independently. Put subagents in a separate workspace or explicitly assign a disjoint file set before allowing edits. Do not touch files owned by an active editing subagent until it reports back, unless the user explicitly redirects ownership.

After meaningful changes, run:

```powershell
.\gradlew.bat assembleDebug
```

When an optimization attempt works, fails, is reverted, or remains pending, record it in the Optimization Ledger below with the scenario and measured evidence. This is required before context compaction, handoff, or switching to a different optimization target.

## 16. Optimization Ledger

Record only meaningful optimization evidence here. Do not keep rejected experiments in source just because they were tried.

Current target: none.

Branch comparison evidence:

- `test/local-perf` working tree versus clean `master` on physical Fold `RFCX40KPN3B`, Denver, `countryScaleZoomContinuityStreetPerf` / `countryScaleZoomContinuitySatellitePerf`, 120 Hz summaries, June 19, 2026: street continent/wide improved on the test branch from 33.62% to 5.04% present-drop120, 27.68% to 19.95% Android jank, and 75.05 ms to 16.68 ms present p95. Street country worsened from 5.04% to 15.25% present-drop120, 8.96% to 20.31% Android jank, and 33.36 ms to 83.41 ms present p95. Satellite regressed in that A/B: continent 50.43% -> 99.02% present-drop120 and 83.4 ms -> 183.5 ms present p95; country 4.2% -> 95.28% present-drop120 and 8.34 ms -> 183.45 ms present p95. Important caveat: `master` uses an older harness and often draws fewer accepted visual/test states, so low master timings are not automatically feature-complete wins. Treat the street-wide improvement as useful evidence and the country/satellite numbers as hotspots to investigate, not as permission to drop visuals.

Keeper candidates currently allowed on this clean branch:

- Reusable `Matrix` drawing for cached aircraft symbol masks. It preserves the same fill/stroke masks and transform while reducing per-symbol canvas state churn. Bounded DFW z5.4 street diagonal run `opt-matrix-symboldraw-dfw-z54-street-diag-real` measured Android jank about 11.28%, present-drop120 about 8.55%, present p50 about 8.34 ms, present p95 about 41.62 ms. Same-target A/B with the old save/translate/scale/rotate path, `opt-ab-savecanvas-dfw-z54-street-diag-real`, worsened to about 22.22% Android jank and 20.43% present-drop120. Keep the Matrix path unless a separate visual/video check catches a transform mismatch.
- Count-only spatial-index probing for dense traffic state. It preserves the same padded aircraft set and same rendered aircraft pixels, but replaces the first allocating `TrafficSpatialIndex.query()` used only for density/symbol decisions with `query_count()`, then performs the real padded query once. Real-phone Denver z6.2 satellite full-traffic run `traffic-countquery-denver-z62-satellite-real` improved the immediate comparison from about 55.56% Android jank / 58.33% present-drop120 / 91.72 ms present p95 to about 21.43% / 22.94% / 50.04 ms, with motion screenshot visually intact and labels at `288/288 labelReq=0`.
- Aircraft symbol body path cache increased from 512 to 768 entries to reduce churn across symbols and morph buckets. Keep only if later visual and timing tests remain neutral or better.
- Street tile-load catch-up keeper, June 19/20: labeled street tiles now use real CARTO Voyager raster tiles with a separate `carto_voyager` cache key instead of `tile.openstreetmap.org`; no-label tiles already used CARTO Voyager no-labels. `StreetMapTileRenderer` uses 6 tile workers, async disk writes after memory display, newest visible request priority, coarsest parent requests before exact requests, parent fallback to depth 3, and a conservative complete child-mosaic fallback from cached higher-detail real street tiles before showing `Loading map`. Real-phone Denver map-only video run `bestof-layer16-street-fastzoomout-childfallback-visual-denver-rfcx` passed route proof, showed no visible `Loading map` recovery hole in the contact sheet, and measured produced FPS 79.2, present mean FPS 101.2, present p50 8.33 ms, present p95 25 ms, present-drop120 5.93%, and Android jank 5.52%. This improved over `bestof-layer15-street-fastzoomout-carto-labeled-visual-denver-rfcx`, which still had one small 250 ms recovery hole and measured present mean FPS 73.4 / present p95 41.65 ms / Android jank 7.04%. Earlier `bestof-layer14-street-fastzoomout-parentpriority-visual-denver-r3cx` on OSM showed many placeholders and only 61.1 present mean FPS, confirming provider/tail-latency mattered as well as fallback behavior.
- The FPS-first summarizer output in `tools/perf/SummarizeFrameStats.ps1` is a testing keeper. It keeps jank fields in CSV, but the default table now leads with produced FPS, present mean FPS, and frame-time percentiles so visual review and performance review can stay separate.

Rejected or not-yet-allowed on this clean branch:

- Do not port satellite renderer/layer rewrites wholesale from `test/local-perf`: the latest branch comparison showed satellite country and continent frame-time regressions versus clean `master`, even though parts of the label work were useful.
- Do not reintroduce optimizations that hide, remove, delay, shrink, darken, simplify, or approximate aircraft morphing, outlines, symbol detail, labels, tile transition behavior, details/photos, alert behavior, or traffic density.
- Rejected June 19 active aircraft-overlay-cache experiment: allowing symbol overlay recording during active wide zoom did not produce cache hits in `bestof-layer11-street-fullapp-activeoverlaycache-perf-denver-rfcx`; full-app street fast-zoom remained about 56.5 present FPS / 23.53% Android jank with `symbolCache=miss reason=active_coverage direct=4306` and produced FPS fell versus the prior full-app perf run. Keep investigating the aircraft overlay miss path, but do not keep active recording unless a future implementation proves actual cache hits, equal visuals, and better full-app frame data.
- Rejected June 19 street generation-pruning experiment: a `PriorityBlockingQueue` plus aggressive stale queued-tile removal skipped useful visible tile work and produced `bestof-layer12-street-fastzoomout-priorityqueue-visual-denver-r3cx` with repeated `Loading map` placeholders, about 65.2 present mean FPS, present p95 41.66 ms, and Android jank 27.14%. Do not bring back tile request dropping/pruning unless a future test proves equal visuals and better sustained loading.
- Rejected previous experiments include feed-publish waiting/staging, visual signatures as a restoration shortcut, atlas packing, parallel retained-symbol tiling, low-scale direct vector fallbacks, scale-aware direct masks, redraw-cadence forcing, dot alpha threshold-only changes, and cadence-only traffic publish tuning unless a future branch proves the exact same accepted pixels and better full-frame evidence.

## 17. Security / Privacy

Prefer HTTPS-only APIs and assets. Do not hardcode API keys, tokens, secrets, credentials, personal device IDs, or private test artifacts.

Avoid cleartext traffic unless there is a documented reason. Lock-screen notifications should avoid exposing sensitive aircraft details unnecessarily.

## 18. Release Behavior

Before pushing: build, inspect git status, check for duplicate project files, scan for obvious secrets, update README accurately, and clean generated junk.

Push only scoped, intentional app/source/docs changes. Only push important changes, or changes large enough to add major functionality.

When the user gives a newer concrete rule that supersedes one of these rules, the newer rule takes priority. Update this file so later agents do not preserve obsolete guidance.
