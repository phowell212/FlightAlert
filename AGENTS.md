# Flight Alert Agent Rules

Flight Alert is a drone situational-awareness app. Refactors must preserve the current user experience and styling unless a visible difference fixes a real bug.

These rules are ordered by priority. If two rules conflict, follow the higher-priority rule.

## 1. No Pretending

Never fake aircraft, map, route, photo, alert, altitude, location, or source data.

If real data is unavailable, show `Unavailable`, hide the feature, disable the control, or label uncertainty clearly. Never use pregenerated aircraft/location/path data as if it were live.

## 2. Real Sources Only

Map tiles must come from real map providers such as OpenStreetMap street tiles or real satellite imagery.

Aircraft traffic must come from live aircraft APIs. Flight paths must come from real trace/path APIs after an aircraft is selected. Do not accumulate app-session aircraft positions and call that a real flight path.

## 3. Safety Bias

Accuracy matters more than impressive visuals. Vertical separation is critical.

If altitude, location, source freshness, or feed data is missing, do not claim an aircraft is safe or inside/outside an alert volume unless the calculation is supported.

## 4. Alerts

Alerts are based on a 3D volume around the user: horizontal distance plus vertical separation.

Notify when an aircraft enters or leaves the configured alert volume. Do not spam repeat notifications for the same aircraft every interval. The persistent extreme-priority notification exists only while aircraft are actually in the extreme-priority list, and it must update or clear automatically.

## 5. UI Honesty

Anything that looks like a button must be interactable.

Unavailable or unverified features must be hidden, disabled, or labeled honestly. Do not show fake radar graphics or fake aviation overlays. Attribution can move later, but do not misrepresent providers.

## 6. Layout Quality

No text clipping, hidden labels, or strange overlaps.

Support portrait, landscape, folding devices, resizing, and emulator hardware input. Text should stay physically consistent unless constraints require smart adaptation. Back should close overlays/screens before leaving the app.

## 7. Map Interaction

Panning, pinch zoom, wheel zoom, and keyboard zoom should feel smooth.

Aircraft sprites may move smoothly every frame using interpolation from speed, heading, and last report. Do not request aircraft positions every frame. Sprite/dot morphing should be smooth and should not pop through the wrong intermediate shape.

## 8. Flight Paths

Only show the path button when a real usable path was retrieved.

The path must represent the selected aircraft's actual current flight/trace, not stale old legs. If the trace endpoint stops before the live sprite position, extend the visible trail to the current live position only when the live report is fresh. Fix path-jump root causes instead of hiding bugs with arbitrary implausible-jump filters.

## 9. Aircraft Details And Photos

Try exact aircraft photos first from real aircraft-photo sources.

If exact photo lookup fails, a representative same make/model photo is acceptable only with a clear "not this exact aircraft" note. Search-engine fallback photos must be labeled investigable and include source/proof view buttons. Claimed make/model/owner data must come from official or documented sources.

## 10. Military Handling

Only show military-specific stats if the aircraft is actually tagged military.

Military origin/base claims require real flight-origin and aerodrome/source data. Otherwise show unavailable or non-military status honestly.

## Styling Preservation

Keep the existing styling, layout feel, visual hierarchy, theme behavior, and interaction rhythm.

When comparing `test` to `master`, the app should look and behave the same except where `test` intentionally fixes an obvious bug or honesty issue. Do not redesign during refactors.

Past visual fixes are protected behavior. Aircraft morphing, icon sizing, dot outlines, satellite tile transitions, layer readability, aircraft details/photo loading states, and other accepted UI fixes must not be optimized out, hidden, gated away, or approximated unless motion testing proves the new implementation is visually identical while preserving the same information.

## 11. Codebase / Repo Discipline

Use Kotlin and work directly in the Android Studio project structure.

Keep one real project: one manifest, one root Gradle setup, and no duplicate generated/source projects. Do not commit Android Studio local machine-state churn, build outputs, temporary screenshots, secrets, `local.properties`, generated junk, or stale comparison artifacts.

## 12. Testing

Build and lint before release. Verify visual/UI changes on a connected device or emulator and use screenshots for comparison when useful.

Use emulators for visual inconsistency, layout, aspect-ratio, clipping, and theme checks. Use physical devices for optimization, timing, responsiveness, smoothness, and performance tests.

For map performance work, automated and manual panning/zooming tests must exercise dense aircraft regions around a fresh random major city center or busy corridor in North America or Europe on every pass, not open ocean, empty polar regions, unintended rural/upstate drift, or the launcher/home screen. Include quick pinch gestures, smooth slow pinch gestures, horizontal pans, vertical pans, and diagonal pans over visible aircraft at multiple zoom levels.

For visual completeness checks, compare against the latest valid feature-complete UI state, including accepted Artist/frontend edits when they exist. Do not compare a post-Artist optimizer build to an older pre-Artist look unless the user explicitly asks for that baseline. Capture the app at rest, right as movement starts, and during active movement so panning/zooming-only flashes, outline changes, tile flicker, or morph jumps are treated as regressions.

Every jank/frame-time test pass must include visual artifacts from the same run when practical. Save screenshots or sampled frames with the same city, zoom, map-source, restricted-layer, and gesture label as the perf report, including at least rest, movement start, and active movement. Treat a lower jank percentage as invalid if those artifacts show darker/smaller aircraft, line traces, sprite-size jumps, missing outlines, missing aircraft information, map blanking, satellite flicker, or any other degradation from the accepted look.

During device tests, accept runtime permission prompts that are specifically requested by the Flight Alert app so permission dialogs do not invalidate smoothness or visual captures.

When maintaining a progress checklist or progress screen, only remove, collapse, or clean up items that are completed and verified. Active, pending, blocked, or deferred work must stay visible so required tasks such as satellite tile loading, details/photo snappiness, morph verification, and full settings-matrix retests survive context resets.

Major workflow rule: right before any likely context compaction, context cleanup, handoff, or long pause, push the active progress checklist into this file using the exact item wording. Immediately after resuming from compaction, pull the checklist wording back from this file before continuing. Do not rename, paraphrase, merge, or soften progress items across compaction unless the user explicitly asks for that wording change.

Optimizer memory rule: keep the `Optimizer Tried And Denied` and `Optimizer Successes To Preserve` sections current. After each meaningful optimization attempt, add or update an entry if the result teaches what style of optimization to avoid, what implementation must be preserved, or what measurement/visual evidence changed the direction. These sections are part of the compaction handoff and must be read before making new optimization choices.

For smooth-map optimization work, aircraft dot-to-symbol morph and dot sizing must be governed smoothly by zoom, not by visible aircraft density. Density may choose batching and bounded symbol counts for performance, but it must not lock dots small, skip the morph, or cause abrupt dot-to-aircraft jumps. Validate this with smooth two-finger pinch motion captures or sampled frames that cover a little below the transition, the full dot-to-plane transition, and a little above it, in both zoom directions. Still screenshots alone are not enough for morph approval.

No aircraft sprite, dot, outline, or symbol gate may create a sudden apparent size jump anywhere in the zoom range. Transition starts, outline introduction, dot fading, symbol entry, and selected/priority styling must be continuous curves tied to zoom/appearance state, and motion tests should explicitly watch for one-frame outline flashes or size pops at those gates.

Aircraft overlay reuse, batching, and viewport-cache optimizations must never reduce the visible representation of aircraft during motion. If the accepted view at a zoom level renders an aircraft symbol, quick panning, edge-of-screen movement, cache reuse, and transition-state drawing must still render the same symbol/morph rather than falling back to a dot, popping in later, or changing the amount of aircraft information on screen.

Failed optimization note: the experimental shared vertex/line batched aircraft-symbol renderer caused visible line/traces while zooming and made some aircraft sprites look dark or too small. Do not reintroduce that renderer or any equivalent visual shortcut unless motion captures prove exact parity with the accepted aircraft look during rest, movement start, and active zoom/pan.

## Optimizer Tried And Denied

These optimization attempts were rejected by the user or by visual/perf evidence. Do not repeat them after compaction unless the implementation is fundamentally different and visual parity is proven before it reaches the user.

- Shared vertex/line batched aircraft-symbol renderer: rejected because zooming produced visible line/traces and some aircraft looked dark or too small.
- Density-driven dot sizing or density-locked morphing: rejected because dot-to-aircraft morph must be governed smoothly by zoom, not traffic density.
- Outline draw gates for dense dots: rejected because they caused outline flashes and apparent sprite-size jumps; outlines must look like the accepted baseline throughout motion.
- Center-ranked/capped dense symbol overlay during motion: rejected because edge aircraft and quick-pan aircraft that should be aircraft symbols remained dots or popped into symbols later. Symbol/cache optimizations must preserve the same visible aircraft representation.
- Visual-performance tradeoffs that hide, drop, delay, darken, shrink, or simplify aircraft symbols: rejected as not a smart optimization. Optimize the same pixels with caching, batching, precomputation, or data-structure changes instead.
- Oversized 560dp dense symbol/dot cache padding: rejected because it inflated dense symbol work from roughly 950 aircraft to roughly 1700-1800 aircraft and worsened physical-phone p95 frame time to 77ms.
- Increasing `DENSE_SYMBOL_CACHE_MAX_REUSE_DP` from 180dp to 320dp or 240dp: rejected because it inflated the active symbol list to roughly 1271 aircraft and worsened p95 to about 20ms without enough benefit. Keep the 180dp dense symbol cache reuse padding unless a smarter edge-stability method is proven with motion visuals and real-phone frame data.
- Matrix-based direct cached-symbol bitmap transform: rejected/reverted because the New York City zoom 8.4 street/off shell-pan run stayed at 164/393 janky frames (41.73%, p50 30ms, p95 57ms), matching the older direct-draw bottleneck rather than improving it.
- Exact-color precomposed aircraft symbol sprite cache: rejected/reverted because altitude/color variability caused cache churn and the physical-phone New York City zoom 8.4 street/off shell-pan run worsened to 60/89 janky frames (67.42%, p50 150ms, p95 200ms) with 40-55ms traffic frames.
- Clearing `drag_started` on `ACTION_UP` is a real state cleanup fix, but it exposed expensive idle direct symbol rendering. Do not restore the old accidental "drag active forever" behavior as a performance trick.
- Removing idle prewarm entirely is rejected because the first active bitmap cache build caused a roughly 608ms traffic frame and 55/433 janky frames (12.70%) in `adaptive-street-on-nyc-z84-shellpan-20260614`.
- Calling `Bitmap.prepareToDraw()` on the retained aircraft-symbol overlay is rejected for now because it worsened the first active cache spike. Removing it improved that spike from roughly 608ms to roughly 402ms in `noprepare-street-on-nyc-z84-shellpan-20260614`, though the path still needs more work.
- One-time cold idle prewarm plus no `prepareToDraw()` is not complete enough by itself: `oneprewarm-street-on-nyc-z84-shellpan-20260614` avoided the first active 400ms spike and active pan frames were smooth, but post-gesture idle direct frames still caused 28/511 janky frames (5.48%, p95 34ms), and the motion-active screenshot showed dots where accepted aircraft symbols should be visible at zoom 8.4.
- RenderNode display-list aircraft-symbol overlay cache: rejected/reverted because `rendernode-street-on-nyc-z84-shellpan-20260614-120745` preserved the concept but worsened the frame distribution to 30/473 janky frames (6.34%, p50 14ms, p95 19ms, p99 46ms), worse than the bitmap overlay with the visual-readiness guard.
- Motion-bounded post-interaction cache hold using projected aircraft speed/drift thresholds: rejected/reverted because `motionhold-street-on-nyc-z84-shellpan-20260614-121847` worsened the physical-phone New York City zoom 8.4 street/on shell-pan run to 42/541 janky frames (7.76%, p95 40ms, p99 53ms). Do not reintroduce bounded cache-hold as currently designed.
- Per-frame precomputed symbol-mask arrays/context: rejected/reverted because `precomputedmasks-trafficonly-street-on-nyc-z84-shellpan-20260614-1242` worsened the physical-phone traffic-only run to 32/521 janky frames (6.14%, p95 32ms, p99 44ms) and introduced large cold idle direct-render spikes around 320-363ms. Do not pre-warm every symbol mask type per draw as a blanket optimization.
- Direct cached-vector aircraft-symbol drawing instead of alpha-mask bitmap drawing: rejected/reverted because `vectorpath-trafficonly-nyc-z84-shellpan-20260614-1350` worsened the physical-phone New York City zoom 8.4 traffic-only shell-pan run from 24/504 janky frames (4.76%, p50 14ms, p95 29ms) with alpha masks to 29/501 janky frames (5.79%, p50 19ms, p95 34ms). Keep the alpha-mask symbol path unless a fundamentally different vector approach proves faster with matching motion screenshots.
- Aviation restricted-layer zoom LOD using mid-zoom/low-zoom ring subsets: rejected/reverted because `maponly-street-on-nyc-z36-shellpan-airlod-20260614` worsened the physical-phone wide street/layer-on run to 9/501 janky frames (1.80%, p95 6ms, p99 38ms) while layer cache spikes remained.
- Low-zoom aviation settled-cache bypass/direct draw: rejected/reverted because `maponly-street-on-nyc-z36-shellpan-layerdirect-20260614` worsened the physical-phone wide street/layer-on run to 6/494 janky frames (1.21%, p95 6ms, p99 36ms) compared with the path-transform implementation's best 4/485 janky frames (0.82%). Keep settled-cache behavior unless a smarter invalidation or precomputation method proves better.
- Current-symbol-state edge-strip merge during active pan: rejected/reverted because `full-street-off-chicago-z84-shellpan-edgecache-20260614` worsened the physical-phone Chicago zoom 8.4 street/off transition run to 26/536 janky frames (4.85%, p50 6ms, p95 21ms, p99 29ms), worse than the partial retained-symbol bitmap cache run at 15/510 (2.94%, p50 6ms, p95 17ms, p99 32ms). Do not reintroduce edge-strip state merging as designed.

Promising optimization direction from the 2026-06-14 optimizer pass: cache and transform the full visible aircraft-symbol overlay, including edge padding, while drawing symbols through visually equivalent cached masks or other parity-proven rendering primitives. Validate with moving screenshots/video before trusting frame improvements.

## Optimizer Successes To Preserve

These optimizations improved smoothness or reliability without an accepted feature/visual loss. Preserve them unless a newer measured change proves better and keeps parity.

- Fast binCraft/direct HTTP inventory feed for all-aircraft locations: preserve as the primary traffic inventory path. Do not reintroduce Chromium/WebView for live inventory.
- Cached dense dot batches with projected per-aircraft motion: preserves visible aircraft locations while avoiding per-frame spatial rebuilds at wide zooms. Keep interpolation tied to real last-known position, speed, heading, and freshness.
- Transform/reuse of dense dot batches during active pan/pinch: successful direction for responsiveness as long as cache coverage is sufficient and visual representation does not degrade.
- Zoom-governed dot-to-symbol morph and dot sizing: preserves smooth transitions independent of traffic density and avoids density-induced morph lockups.
- Cached symbol morph paths/mask primitives: successful direction for drawing the same accepted aircraft shapes more cheaply than rebuilding vector paths per symbol each frame, provided screenshots/video confirm no darkening, shrinking, stepping, or shape drift.
- Cached aircraft-symbol alpha masks for the full visible dense symbol overlay: successful on the physical phone transition sweep over New York City at zoom 8.4 street/off. Full symbol coverage was preserved in rest/motion-start/motion-active screenshots, and jank improved from 166/191 frames (86.91%, p50 53ms, p95 73ms) with per-frame path symbols to 28/467 frames (6.00%, p50 15ms, p95 27ms) with cached masks.
- Cached-mask traffic-only isolation over New York City zoom 8.4 street/off with `-SkipMap -SkipChrome` reached 1/444 janky frames (0.23%, p50 15ms, p95 16ms, p99 18ms), showing the traffic renderer itself is close to smooth after the cached-mask pass.
- No-traffic isolation over New York City zoom 8.4 street/off with `-SkipTraffic` reached 0/477 janky frames (0.00%, p50 6ms, p95 9ms, p99 9ms), so map/chrome without traffic is not the bottleneck in that transition test.
- Warm cached-mask full sweep over New York City zoom 8.4 street/off reached 2/465 janky frames (0.43%, p50 16ms, p95 19ms, p99 20ms), confirming remaining cold-run transition jank mostly comes from mask-cache buckets being created during the gesture.
- Retained bitmap aircraft-symbol overlay during active pan: preserves the same accepted aircraft-symbol pixels while turning the dense moving symbol layer into one bitmap draw during active map motion. On physical phone `RFCX40KPN3B`, New York City zoom 8.4 street/off traffic-only improved from 34/1066 janky frames (3.19%) with RenderNode replay to 7/1014 (0.69%), then idle prewarm improved to 4/1097 (0.36%). Full street/off with the retained bitmap/prewarm reached 2/1072 janky frames (0.19%, p50 8ms, p95 9ms, p99 10ms) and a confirmation run reached 2/1094 (0.18%) with clean rest/motion screenshots.
- Satellite tile retention/fallback pass: preserve real satellite imagery while panning by keeping the last complete interim raster above partially loaded exact tiles, fading exact tiles over interim only when safe, using access-order LRU tile memory, and resetting tile transitions without clearing useful tile memory on ordinary source/label/perf switches. Verified on physical phone `RFCX40KPN3B` over New York City zoom 8.4 satellite/off with a real shell-input pan: 1/371 janky frames (0.27%, p50 5ms, p95 8ms, p99 8ms), and rest/motion-start/motion-active/end screenshots stayed filled with satellite imagery and showed no blank loading blocks or harsh blank flicker. Reverified after compaction with map-only satellite/off shell-pan `satellite-tiles-maponly-nyc-z84-off-shellpan-retention-20260614-103512`: 1/631 janky frames (0.16%, p50 5ms, p95 15ms, p99 16ms), app-side map draw under roughly 2ms, and rest/motion-start/motion-active/motion-late/end screenshots stayed filled with real satellite imagery. Reverified again before the anti-jank loop with `satellite-tiles-maponly-nyc-z84-off-shellpan-recheck-20260614-113942`: 1/483 janky frames (0.21%, p50 5ms, p95 6ms, p99 7ms), clean rest/motion-start/motion-active/motion-late/end screenshots, and no blank or loading tiles. Reverified on the exact post-revert installed build with `satellite-tiles-maponly-nyc-z84-off-shellpan-postinstall-20260614-1224`: 3/480 janky frames (0.62%, p50 5ms, p95 7ms, p99 7ms), and motion-start/motion-active/motion-late screenshots stayed filled with satellite imagery. A zoom-transition check using `keyboardzoom` (`satellite-tiles-maponly-nyc-z84-off-keyzoom-recheck-20260614-114317`) produced 5/438 janky frames (1.14%, p50 5ms, p95 7ms, p99 22ms), clearly changed zoom level in the screenshots, and stayed filled with satellite imagery without blank loading blocks or harsh empty-tile swaps.
- Current pre-anti-jank satellite gate is clear on the active dirty tree: physical-phone map-only satellite/off New York City zoom 8.4 shell-pan `satellite-tiles-maponly-nyc-z84-off-shellpan-current-recheck-20260614` rendered 482 frames with 2 janky frames (0.41%, p50 5ms, p95 8ms, p99 9ms). Rest, motion-start, motion-active, motion-late, and end screenshots stayed filled with real satellite imagery and showed no loading placeholders, blank blocks, or harsh empty-tile swaps.
- Satellite tile retention/interim merge verification: after `MapTileRenderer` changed interim raster replacement from whole-set clearing to merge-and-prune, physical-phone `FlightMapGesturePerfTest#zoomLowToHighSweep` was repaired to launch with explicit `mapSource=SATELLITE`, accept/grant Flight Alert permissions, and save motion-phase screenshots. Valid two-finger pinch run `instrumentation-zoomLowToHighSweep-satellite-motion-20260614-125831` captured rest, motion-start, motion-active-out, reverse-start, motion-active-in, and final frames; all stayed filled with real satellite imagery with no `Loading map` placeholders or blank flicker. A 90-second Paris satellite/off soak `satellite-soak-paris-off-20260614-1302` also kept rest/active/end imagery filled without requiring an app restart. A 60-second Paris street/off soak `street-soak-paris-off-20260614-1305` kept street tiles filled too, so the broader base-map disappearance note is visually cleared for this pass.
- Post-partial-cache base-map disappearance check: physical-phone `RFCX40KPN3B` 90-second Paris street/off soak `street-soak-paris-off-postpartial-20260614` kept rest, mid-soak, and end screenshots filled with real street tiles and aircraft. Matching 90-second Paris satellite/off soak `satellite-soak-paris-off-postpartial-20260614` kept rest, mid-soak, and end screenshots filled with real satellite imagery and aircraft. No local PowerShell perf jobs remained after the soaks. These runs visually clear the reported base-map disappearance for this iteration, though idle aircraft-symbol direct drawing remains too janky and should be optimized separately.
- Perf harness foreground checks: preserve because they prevent accidental launcher/home-screen tests from being counted as Flight Alert results.
- Hidden perf chrome/details hit-test fix: in debug perf skip-chrome mode, hidden details/chrome no longer intercept map gestures. Preserve this because it makes shell-pan tests reach the map (`mapTouchActive=true`, symbol cache hit, direct symbol drawing avoided during active pan) instead of accidentally testing invisible controls or the launcher.
- Jank test visual artifacts tied to the same run as framestats: preserve and fix when broken, because frame wins are invalid if rest/motion-start/motion-active/end captures show visual regression. On multi-display phones, choose the screencap display with real image content; the first SurfaceFlinger display id can be a black/blank capture.
- `ACTION_UP` now clears `drag_started` and `drag_start_center`, and records map interaction only when the drag was not blocked. Preserve this cleanup because it prevents late stale active-cache state and avoids hiding real idle/direct rendering cost.
- Adaptive aircraft motion redraw gating now uses cached maximum projected aircraft speed (`max_projected_speed_zoom_zero`) with zoom-aware ticker thresholds. Preserve the O(1) direction for avoiding unnecessary subpixel idle redraws, but keep optimizing because the current post-gesture direct path is still too expensive.
- The retained aircraft-symbol overlay no longer calls `prepareToDraw()` before being drawn. Preserve unless a measured replacement proves better with matching screenshots, because avoiding the upload hint reduced the first active cache stall in physical-phone testing.
- Aircraft-symbol overlay visual-readiness guard: preserve the cache key's coarse appearance bucket and the rule that retained symbol overlays are not drawn until the aircraft symbols are actually visible. This fixed the oneprewarm visual regression where rest frames showed aircraft symbols but motion-start/motion-active showed dot-only fallback at New York City zoom 8.4. `cacheappearance-street-on-nyc-z84-shellpan-20260614-115702` and `cacheinitialonly-street-on-nyc-z84-shellpan-20260614-120243` restored the accepted aircraft-symbol look in motion-start/motion-active/motion-late screenshots.
- Pre-interaction-only idle refresh for the retained aircraft-symbol overlay: preserve the `symbol_overlay_saw_interaction` guard unless a measured replacement proves better with matching motion visuals. It keeps the useful initial warmup while preventing repeated post-pan idle cache refreshes from landing in the measured tail.
- Aircraft-symbol overlay active key refresh guard: preserve the rule that active pan/pinch does not rebuild the retained symbol overlay when only visual cache keys drift. `instrumentation-zoomLowToHighSweep-satellite-activekey-20260614-131326` removed the previous 600-800ms active-pinch symbol-overlay rebuild spikes while keeping satellite motion frames filled and aircraft symbols visible; later New York shell-pan logs showed active map motion mostly in the 0.3-3.4ms traffic range. The remaining bottleneck is idle direct symbol drawing, not active touch rebuilds.
- Dense symbol state idle reuse with projected per-aircraft motion: preserve the direction where dense symbol overlay states carry screen velocity, motion lifetime, and appearance timing so the app can reuse state between feed updates without freezing aircraft or appearance curves. `idlesymbolreuse-street-off-nyc-z84-shellpan-20260614` improved the physical-phone New York City zoom 8.4 street/off full shell-pan from the prior 40/494 janky frames (8.10%, p50 18ms, p95 34ms, p99 44ms) to 34/500 janky frames (6.80%, p50 18ms, p95 27ms, p99 36ms), with motion-start/motion-active screenshots preserving the accepted outlined aircraft-symbol look and no dot fallback, dark/small symbols, or line traces. It is not complete by itself: the traffic-only diagnostic `idlesymbolreuse-trafficonly-nyc-z84-shellpan-20260614` still measured 26/524 janky frames (4.96%, p50 14ms, p95 29ms), so continue optimizing direct fallback frames.
- Aviation restricted-layer prebuilt world-space path rendering: preserve the direction of preparing airspace rings once in projected world coordinates and drawing them through canvas translation/scale instead of rebuilding screen paths/interaction line arrays during movement. On physical phone `RFCX40KPN3B`, New York City zoom 3.6 map-only street/layer-on improved from `maponly-street-on-nyc-z36-shellpan-layeron-20260614` at 7/494 janky frames (1.42%, p50 5ms, p95 8ms, p99 34ms) to best run `maponly-street-on-nyc-z36-shellpan-airpath-20260614` at 4/485 janky frames (0.82%, p50 5ms, p95 8ms, p99 31ms), with confirmation `maponly-street-on-nyc-z36-shellpan-airpathconfirm-20260614` at 6/483 (1.24%, p50 5ms, p95 7ms, p99 38ms). Motion screenshots stayed visually intact.
- Retained bitmap dot overlay for wide dense dot batches: preserve because it draws the same accepted colored/outlined aircraft dots as a cached bitmap during active wide-map movement instead of sending roughly eleven thousand individual point draw commands through RenderThread. On physical phone `RFCX40KPN3B`, New York City zoom 3.6 full street/off improved from `full-street-off-nyc-z36-shellpan-airpathconfirm-20260614` at 77/435 janky frames (17.70%, p50 20ms, p95 28ms, p99 31ms) to `full-street-off-nyc-z36-shellpan-dotbitmap-20260614` at 7/495 janky frames (1.41%, p50 5ms, p95 7ms, p99 12ms). The same dot-bitmap build measured full street/on at 9/490 (1.84%, p50 5ms, p95 8ms, p99 38ms), full satellite/off at 4/487 (0.82%, p50 5ms, p95 6ms, p99 14ms), and full satellite/on at 7/490 (1.43%, p50 5ms, p95 7ms, p99 36ms), with same-run screenshots showing real map imagery and visible aircraft dots/outlines.
- Partial retained aircraft-symbol bitmap cache reuse during active pan: preserve the active cache rule where center changes do not invalidate the retained symbol bitmap as long as zoom/shape/theme visual keys match and the bitmap still intersects the viewport; uncovered edges are direct-drawn by the existing parity path. On physical phone `RFCX40KPN3B`, Chicago zoom 8.4 full street/off improved from `full-street-off-chicago-z84-shellpan-dotbitmap-20260614` at 23/510 janky frames (4.51%, p50 13ms, p95 17ms, p99 30ms) to `full-street-off-chicago-z84-shellpan-partialcache-20260614` at 15/510 (2.94%, p50 6ms, p95 17ms, p99 32ms). Motion screenshots showed accepted aircraft symbols/outlines with no line traces, dark/small shortcut, or dot fallback.

## Optimizer Testing Notes

Keep this section current with testing methods that worked, failed, or had device quirks.

- Do not run two `RunFlightMapPerf.ps1` invocations against the same device at the same time. They race the perf viewport/run id, invalidate both runs, and can produce misleading ownership errors.
- Physical phone `RFCX40KPN3B` exposes multiple SurfaceFlinger display ids. The first listed id can capture a 19 KB black PNG; the app content was on the larger PNG display id in recent tests. Use the harness display probe or verify screenshot file size/content before trusting visual artifacts.
- Valid visual artifacts should include rest, motion-start, motion-active, and end captures from the same run label as framestats. Black frames or launcher/home frames invalidate visual evidence.
- Traffic-only isolation should use `-SkipMap -SkipChrome`; no-traffic isolation should use `-SkipTraffic`. Run them sequentially after a full run to separate overlay cost from map/chrome cost.
- New York City zoom 8.4 street/off sweep is a good dense transition-zone test for dot-to-aircraft morph, edge symbols, and quick zoom/pan behavior.
- App draw logs are useful for renderer cost (`Debug draw perf ... traffic=... symbols=... dots=...`), while system framestats capture end-to-end jank. Screenshot jobs can add some overhead, so compare app logs and system framestats together.
- `RunFlightMapPerf.ps1` intentionally settles the app before resetting gfxinfo, so logcat can contain idle direct-render logs from before the measured gesture. Judge measured motion with the run framestats plus post-touch log slices, not pre-reset idle spikes.
- Device-side `nohup sh -c 'sleep ...; screencap ...' &` screenshots have been validated for motion captures on the real phone. Keep tying those visual artifacts to the same run label as the framestats.
- On physical phone `RFCX40KPN3B`, the Java `MultiTouchGestureRunner` did not visibly move the app during recent satellite tests even after a display-id reflection attempt; do not count its screenshots as motion proof unless app `Debug perf touch` logs confirm delivered touches and the frames visibly change. Plain `adb shell input swipe ...` did deliver touch callbacks and real map panning when the app was in perf mode; use app logs to confirm `Debug perf touch action=0/1`.
- The Java `MultiTouchGestureRunner` quick/pinch path is invalid on the physical phone when it produces zero frames and no app touch callbacks. A zero-frame pinch/quick run must not be counted as satellite, morph, or jank evidence.
- On the same phone, `input -d 0 touchscreen ...` and explicit display/source forms produced no app touch callbacks in testing, while plain `adb shell input tap/swipe ...` did. Prefer the plain shell-input form for panning verification until the multi-touch harness is repaired.
- Concurrent shell `input swipe ... & input swipe ...` is not a valid pinch on physical phone `RFCX40KPN3B`: the app logged separate one-pointer streams plus cancel/down events, not `pointers=2`. Do not count those runs as morph proof.
- Low-level `sendevent` on `/dev/input/event10` (`sec_touchscreen2`, the active front-screen touch device) is blocked by permission denied for the shell user. Do not rely on raw event injection unless the device permission situation changes; use instrumentation/UIAutomation or emulator for automated multi-touch if the Java `InputManager` harness remains invalid.
- Physical-phone UIAutomator instrumentation `FlightMapGesturePerfTest#zoomLowToHighSweep` produced valid two-finger evidence when the app log showed real `pointers=2` touch streams. Use that path for pinch/morph/satellite zoom evidence when shell or Java injection does not deliver real multi-touch.
- The first valid `zoomLowToHighSweep` run caught the active map/tile blanking bug: `tools/perf/out/instrumentation-zoomLowToHighSweep-20260614/flightalert-perf-zoomLowToHighSweep.png` showed aircraft overlays over mostly dark satellite "Loading map" tiles with only a small loaded imagery patch. This validates the pending map/tile bug as base imagery disappearing, not aircraft disappearing.
- A later `zoomLowToHighSweep` rerun after the current tile-retention patch failed before gestures with `Refusing to run gestures outside Flight Alert`; that run is invalid and must not be counted as evidence for or against the tile fix.
- `connectedDebugAndroidTest` can remove the app package after a run on the physical phone. Reinstall `build\outputs\apk\debug\Flight Alert-debug.apk` and grant Flight Alert runtime permissions before subsequent `RunFlightMapPerf.ps1` or manual phone tests if `adb` reports package/class not found.
- UIAutomator `takeScreenshot()` cannot save directly under `/sdcard` on the physical phone because MediaProvider rejects the root directory; save instrumentation motion screenshots under `/sdcard/Download/` and pull from there. Shell `screencap` can still write final/rest images to `/sdcard`.
- For full-map perf screenshots with `PERF_SKIP_CHROME=true`, hidden details state can otherwise block map gestures. The app now ignores hidden details as a map-gesture blocker only in debug perf skip-chrome mode; production visible details still block map drags normally.
- `oneprewarm-street-on-nyc-z84-shellpan-20260614` active gesture logs were promising (`symbolCache=hit direct=0`, traffic mostly around 0.3-0.6ms), but the run is not acceptable because the bad frames came before touch and after the gesture (`symbolCache=miss reason=inactive direct=~619`, traffic roughly 8-24ms), and the motion-active artifact showed dot fallback at a zoom level where aircraft symbols should remain visible.
- If an optimization produces lower jank but a motion screenshot shows dots instead of aircraft symbols, edge aircraft staying dots during quick pan, outline flashes, dark/small symbols, line traces, map blanking, or satellite tile swaps, that run fails visual completeness and must not be counted as progress.
- Satellite tile transition verification must happen before the broad anti-jank loop whenever that item is active: use moving screenshots over real satellite imagery while panning/zooming and confirm there are no blank loading blocks, harsh imagery swaps, or flicker. The `keyboardzoom` perf mode is an acceptable satellite zoom-transition fallback when the injected multi-touch harness produces zero frames, as long as screenshots prove the zoom level changed.
- `keyboardzoom` is only an acceptable fallback for satellite tile zoom-transition verification when Java multi-touch produces zero frames. It is not aircraft morph approval, because morph approval still requires smooth touch-style motion samples through the full dot-to-plane transition.
- `RunFlightMapPerf.ps1 -NoScreenshots` is diagnostic only; screenshot-backed runs remain required for visual completeness. `noscreens-cacheinitialonly-street-on-nyc-z84-shellpan-20260614-121421` still measured 33/526 janky frames (6.27%, p50 7ms, p95 36ms, p99 48ms), so screenshot capture was not the main remaining tail in that case.
- `RunFlightMapPerf.ps1` shell-pan gestures must stay on the real map surface, not over the persistent nearest-traffic/details panel. The left-map shell-pan route (`x` roughly 16%-48% of display width) fixed the earlier control-hit issue where the right-side pan started inside the details panel.

Smooth-map optimization target: panning, diagonal panning, quick pinching, slow pinching, zooming through all transition bands, selection, details entry, street tiles, satellite tiles, and restricted/no-fly layers on and off should stay silky in dense traffic. Test the combinations while the screen is moving, not only while it is still: pan at different zoom levels, pinch at different zoom levels, and use small, medium, and large zoom deltas across the transition ranges. Cover street and satellite map modes with restricted/no-fly zones both on and off. Aim for 120 Hz-class interaction on capable real phones. Further optimizer passes should keep iterating until protected visuals are intact and the app is definitively above 100 fps in the full required settings matrix.

Base-map disappearance reliability note for this optimizer pass: the earlier failure was a map/tile retention/loading bug where the base map layer disappeared or blanked while aircraft overlays remained visible, not aircraft disappearing. It is visually cleared for the current pass by the satellite/street moving and soak evidence listed above; reopen only if a new same-run screenshot shows real map tiles disappearing again.

Satellite tile patch verification contract: retained/interim raster tiles merge across recent zoom levels instead of clearing the whole interim tile set every time one complete exact tile frame loads. This preserves older lower-zoom imagery as a full-screen fallback during rapid zoom-out/zoom-in cycles while exact satellite tiles load. Physical-phone satellite pinch motion frames and satellite/street soak screenshots listed in `Optimizer Successes To Preserve` verify no blank map placeholders or restart-required tile disappearance in this pass.

As a general optimization rule, solve the expensive work directly before reducing user-visible output. Prefer profiling, data-structure changes, caching, batching, precomputation, and drawing the same pixels more efficiently. If stuck, research how similar map/overlay/rendering systems solve the bottleneck before making visual tradeoffs. Do not remove visual fixes, lower information density, or add thresholds that make accepted behavior disappear unless the user explicitly asks for that tradeoff.

Delete temporary screenshots taken by agents after verification. Use bounded verify/fix loops: improve weak features, but do not get stuck forever and block handoff or release.

## 13. Security / Privacy

Prefer HTTPS-only APIs and assets. Do not hardcode API keys, tokens, secrets, or credentials.

Avoid cleartext traffic unless there is a documented reason. Lock-screen notifications should avoid exposing sensitive aircraft details unnecessarily.

## 14. Release Behavior

Before pushing: build, lint, inspect git status, check for duplicate project files, scan for obvious secrets, update README accurately, and clean generated junk.

Push only scoped, intentional app/source/docs changes. Only push important changes, or changes large enough to add major functionality.

## Newer Rule Priority

When the user gives a newer concrete rule that supersedes one of these rules, the newer rule takes priority. Update this file so later agents do not preserve obsolete guidance.

## Code Organization

Flight Alert code should read like objects doing things.

`FlightMapView` is the coordinator. It should coordinate map state, selection, draw order, and user input. Feature objects should own feed parsing, route validation, photo lookup, impact scoring, settings math, motion projection, and feature-specific business rules.

Prefer small objects with names that explain their job, such as `CurrentRouteValidator`, `AircraftPositionProjector`, or `AircraftRoutePresenter`. New feature work should usually start as a new object/file with a narrow public method, then be called by the coordinator.

Use underscore-style names for app-owned functions and properties as the code is refactored, matching The Cistern's readability style. Do not rename Android framework overrides, manifest entry-point class names, generated/build API names, or external source keys that must match provider data.

## Agent Workflow

Before changing code, read this file and `docs/code-organization.md`.

For optimization work, use a connected physical phone when one is available. Preserve a pre-optimization copy of the app before making changes. Compare against that copy or `master` so the app displays the same information and preserves the same user experience except for lag reductions and real bug fixes.

After meaningful changes, run:

```powershell
.\gradlew.bat assembleDebug
```

For refactors, compare against `master` on an emulator when practical. Treat styling or behavior drift as a regression unless it is explicitly tied to a bug fix or honesty fix.

When taking Android screenshots from PowerShell, prefer `adb shell screencap -p /sdcard/name.png` followed by `adb pull /sdcard/name.png local.png`. Direct `adb exec-out screencap -p > file.png` can produce PNGs that local image viewers fail to decode.
