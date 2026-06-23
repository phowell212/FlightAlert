# Flight Alert Agent Rules

Flight Alert is a drone situational-awareness app. Refactors must preserve the current user experience and styling unless a visible difference fixes a real bug or honesty issue.

These rules are ordered by priority. If rules conflict, follow the higher-priority rule. Within the same priority, newer concrete user instructions supersede older ledger notes or older agent assumptions; update this file when that happens so obsolete guidance is not preserved.

## 0. Optimization Priorities And Master Baseline

For optimization work, preserve these priorities in order: 1) functionality to the end user, 2) visuals to the end user, 3) frame timing and implementation elegance. Performance work is only successful when the user-visible behavior remains functionally intact and visually faithful; FPS gains that introduce flicker, pop, missing layers, wrong motion, changed styling, or reduced information are failures.

Do not run `master` comparisons as a standing optimization workflow. The June 21 master-derived inventory is exhausted and retired; use the pushed post-inventory checkpoint as the normal comparison baseline. Compare against `master` only when the user explicitly requests it, or when an obvious visual glitch needs a reality check against `master` before fixing the current branch. If such a targeted master check shows `master` has a safer visual behavior for the glitch, use it as evidence to understand and fix the current branch, but do not port master's looser performance behavior or weaker visual guarantees as a shortcut.

Maintain the detached `master` worktree, normally `C:\Users\h\AndroidStudioProjects\FlightAlert-master-compare`, as read-only reference material only. Do not reopen a master-first performance inventory unless the user asks for it. Do not delete the accepted/rejected `Optimization Ledger` rows in `docs/flightalert-performance-metrics.xlsx`; those working and nonworking fixes remain guide rails for future work.

The one-file consolidation exists to make cross-cutting renderer/backend flaws easier for Codex and Pro to see: logic that is far apart but behaviorally coupled should be identified and, when useful, moved closer or redesigned under the hood. The user is open to fundamental implementation changes, helper objects, and backend rethinks of rendering, data acquisition, caching, scheduling, and state flow as long as functionality and visuals are preserved with about 99% accuracy or better, the code philosophy/design priorities remain intact, and any visible difference is a deliberate bug fix, not a performance shortcut.

## 1. No Pretending

Never fake aircraft, map, route, photo, alert, altitude, location, or source data.

If real data is unavailable, show `Unavailable`, show `Loading`, hide the feature, disable the control, or label uncertainty clearly. Never use pregenerated aircraft, location, route, tile, or path data as if it were live.

## 2. Creative Reinvention, Never The Axe

Preserve the visible and user-facing result first, then reinvent the implementation underneath until it is fast.

When performance is bad, solve the expensive work directly with profiling, caching, batching, precomputation, better data structures, retained real imagery/data, smarter request scheduling, or another equivalent implementation. Do not win frame time by removing, hiding, delaying, shrinking, darkening, simplifying, skipping, or approximating accepted visuals, features, information density, layer content, aircraft morphing, map labels, tile transitions, details, photos, or alerts unless the user explicitly asks for that tradeoff.

If a protected visual or feature is a real bottleneck, replace its implementation with one that produces the same accepted pixels, information, timing honesty, and interaction behavior faster.

Protected features that must never be optimized out include live aircraft identity/order/presence, smooth aircraft entry and exit at screen edges, aircraft dots/symbols/outlines/shadows/colors/scales/rotations/selection rings, aircraft morphing, rotorcraft blade animation, satellite and street tile imagery, tile/road/reference-layer fades, map labels and borders, county labels with zoom-tied fade behavior, selected paths, ownship display, controls/chrome, modal/details/photo/loading states, alerts, and source-freshness honesty. Optimize the logic behind these features only when the end user receives the same or better visible behavior.

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

For map performance work, automated and manual panning/zooming tests must choose the test region from the traffic-density UTC timetable stored in `docs/flightalert-performance-metrics.xlsx` before choosing a city. Do not default to an old Denver scenario just because earlier artifacts used it. Exercise dense aircraft regions around a major city center with major airports/corridors in the active high-density region: European targets such as London, Amsterdam, Frankfurt, Paris, Madrid, or dense western/central Europe corridors during Europe-heavy windows; inland US targets such as Dallas-Fort Worth, Atlanta, Phoenix, Las Vegas, Chicago, New York, or Los Angeles during US-heavy windows when the route-proof envelope remains land/traffic centered. Full zoom-sweep evidence must use targets whose continent/wide view stays land/traffic centered. The test algorithm must keep pinch and pan gestures bounded around the target map area; choosing a valid start point is not enough if the scripted gestures drift into water or empty terrain. Do not test over open ocean, lake/sea drift, empty polar regions, unintended rural/upstate drift, upper Canada, smaller fallback metros, or the launcher/home screen; if a visible run drifts into those areas, discard that run as evidence and rerun.

Include quick anchored pinch gestures, smooth slow anchored pinch gestures, horizontal pans, vertical pans, diagonal pans, slight circular or elliptical map motion, and combined pinch-while-panning gestures over visible aircraft at multiple zoom levels. Pinch tests must keep the target city/traffic focus anchored so zooming back in returns to the same target.

Device perf commands must be deliberately bounded. Harness runs are capped at 3 minutes, sanity checks should usually target about 30 seconds of device/video motion, short visual checks should usually use 60 seconds or less, and thorough runs should usually stay at 150 seconds or less. Do not leave a hanging command running past 5 minutes; stop it, fix the harness or test philosophy causing the hang, and discard the partial run as evidence. Full-range zoom tests must relaunch or otherwise re-anchor over the timetable-selected US/EU target before reversing direction so zooming back in never lands over unrelated desert, ocean, sparse terrain, or a random map location.

Human-like motion is a first-class test requirement. Prefer slightly overlapping pan and pinch gestures with bounded, natural-feeling movement over mechanical sequences of isolated pan, pause, zoom, pause. Harness sanity checks should still exercise meaningful map motion. Prefer a short pan-plus-overlapped-zoom smoke test such as `satellitePanZoomSanityPerf`; use a pan-only test only when the explicit question is close-pan label stability.

Performance tests must separate results by scale band instead of hiding them inside one average. Always include known difficult bands around the 100 mi scale-bar range and the 200-500 mi scale-bar range, plus local, country, and continent/global checks in both street and satellite modes when the change could affect map/traffic rendering.

Version-to-version app comparisons must be thorough enough to support the claim being made. A quick bounded sanity run can reject an obviously bad change or guide the next investigation, but it is not enough to declare a pushed checkpoint, branch, or build broadly faster. For accepted comparisons, run comparable timetable-selected dense-region tests across the affected hard bands and map modes, include human-like overlapping pan/zoom motion, include roads/borders-on video inspection when satellite roads/labels/borders could be affected, and report produced FPS, present mean FPS, p50/p95/p99, Android jank, and route proof for each artifact.

At least one hotspot-finding test in an optimization cycle should sweep through the practical zoom range in and out, pan at representative zoom levels, and pan while zooming. The artifacts must make frame timing/FPS data correlatable to the active zoom band or phase. Treat those hotspots as starting points for broader root-cause investigation; do not patch a single band by reducing information, visual quality, morph continuity, outlines, labels, or layer fidelity.

For satellite tile-load work, include a fast close-to-wide zoom-out test that starts at close/high-detail satellite imagery over an inland major-airport traffic target, then quickly pinches out to country/continent scale and measures how long exact visible tiles remain unloaded. Use this to optimize real tile catch-up time; do not judge satellite load speed only from slow zooms or at-rest screenshots.

For visual completeness checks, compare against the latest valid feature-complete UI state. Capture or inspect the app at rest, right as movement starts, and during active movement so panning/zooming-only flashes, outline changes, tile flicker, or morph jumps are treated as regressions.

Satellite roads-on testing must include motion-video inspection, not just still screenshots, contact sheets, route proof, or aggregate layer counters. The June 21 manual user check caught road flicker in satellite view that prior evidence missed, so future harness/video review must look for frame-to-frame road opacity changes, road LOD scale swaps, whole-road-layer blink, parent/exact overlay crossfades, and label/border/road mismatches while panning and zooming with roads enabled. A test mechanism that cannot observe those temporal failures is incomplete even if frame stats and route proof pass. When this class of bug is found, fix both the renderer issue and the detection mechanism that missed it.

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

For optimization work, also read `docs/flightalert-performance-metrics.xlsx`, especially `Performance Notes`. The workbook is the authoritative home for the detailed optimization loop, benchmark lanes, Pro/Android Studio MCP usage, warning-audit requirements, rejected-attempt rules, and historical evidence. Keep `AGENTS.md` focused on fundamental product rules and compact workflow pointers.

Do not move, weaken, or delete the fundamental app rules in sections 0-14 when cleaning workflow documentation. Do not re-expand `AGENTS.md` with the detailed optimization loop; update `docs/flightalert-performance-metrics.xlsx` `Performance Notes` instead, then leave a compact pointer here if needed.

Flight Alert consumes shared MCP/Pro tooling; do not develop bridge tooling in this app repo. If Pro Agent Bridge or Android Studio MCP usage fails, report diagnostics to the `Use advisory bridge` agent/thread as described in the workbook.

Use subagents only for low-conflict, clearly bounded work. Do not touch files owned by an active editing subagent unless the user explicitly redirects ownership.

After meaningful non-optimization changes, run `.\gradlew.bat assembleDebug`. For optimization build/test/logging requirements, follow the workbook.

## 16. Performance Workbook And Optimization Ledger

The single durable performance notebook is `docs/flightalert-performance-metrics.xlsx`.

For optimization work, read all three workbook sheets before editing:

- `Data`: chartable workbook tests, all parsed runs, detailed audits, trace audits, and frame correlations.
- `Charts`: user-facing performance graphs and run inventory.
- `Performance Notes`: dashboard context, workflow rules, Pro/MCP/tooling rules, benchmark-lane rules, rejected-attempt requirements, and the full optimization ledger.

Use `tools/perf/BuildFlightAlertPerformanceWorkbook.mjs` with the bundled Node.js/Python runtimes to rebuild the workbook. Temporary preview workbooks are disposable staging artifacts only; do not let them become a second source of truth.

Every optimization iteration must run the same 60-second apples-to-apples multi-zoom performance lane before being treated as accepted. Record each iteration in `docs/flightalert-performance-metrics.xlsx`, update the comparison graph, and keep the run parameters, target city/region, map mode, layer state, duration, and thermal state comparable across iterations.

## 17. Security / Privacy

Prefer HTTPS-only APIs and assets. Do not hardcode API keys, tokens, secrets, credentials, personal device IDs, or private test artifacts.

Avoid cleartext traffic unless there is a documented reason. Lock-screen notifications should avoid exposing sensitive aircraft details unnecessarily.

## 18. Release Behavior

Before pushing: build, inspect git status, check for duplicate project files, scan for obvious secrets, update README accurately, and clean generated junk.

Push only scoped, intentional app/source/docs changes. Only push important changes, or changes large enough to add major functionality.

When the user gives a newer concrete rule that supersedes one of these rules, the newer rule takes priority. Update this file so later agents do not preserve obsolete guidance.
