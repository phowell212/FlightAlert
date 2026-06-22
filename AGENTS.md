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

For optimization work, use a connected physical phone when one is available. Preserve a pre-optimization copy or branch before making changes. Compare against that copy or the latest accepted baseline so the app displays the same information and preserves the same user experience except for lag reductions and real bug fixes.

Normal optimization loop after master inventory:

1. The June 21 master-derived optimization inventory is exhausted and retired as the active tunnel. Keep `C:\Users\h\AndroidStudioProjects\FlightAlert-master-compare` read-only as historical reference only; do not run master comparisons unless the user explicitly requests one, or an obvious visual glitch needs a targeted reality check before fixing the current branch.
2. Use the pushed `one-huge-file` checkpoint after the master inventory as the normal comparison baseline for future work. The June 21 checkpoint tag is `checkpoint-one-huge-file-20260621-fair-master`; compare candidate changes against that tag or a newer accepted checkpoint, not against a half-tested local experiment.
3. For each new hotspot, start from profiling/detail evidence, run the Android Studio MCP function audit before hot renderer/perf edits, make the smallest behavior-preserving implementation change, build with Android Studio MCP when available, and validate on a physical device.
4. Version-to-version comparisons must be thorough. Quick sanity runs can reject a candidate or identify a hotspot, but accepted branch/checkpoint comparisons need comparable timetable-selected dense-region coverage across affected hard bands, map modes, and protected visual states, with video inspection where roads/labels/borders or other visual claims matter.
5. Record every accepted, rejected, or deferred optimization in `docs/flightalert-performance-metrics.xlsx` with artifacts and exact frame evidence before switching targets or pushing a new comparison checkpoint.

Flight Alert is a consumer of shared MCP/Pro tooling, not the place where that tooling is developed. Do not add Pro bridge scripts, MCP proxy scripts, startup prompts, or bridge-status experiments here. Source-of-truth guidance for consuming those tools lives in `C:\Users\h\plugins\pro-agent-bridge\docs\CONSUMER-WORKSPACE-MCP.md`; bridge defects belong in `C:\Users\h\plugins\pro-agent-bridge`, not in this app repo.

Whenever Pro Agent Bridge or Android Studio MCP bridge usage fails, times out, returns malformed/stale/no-fresh-response data, or otherwise blocks or degrades Flight Alert work, send a concise diagnostics report to the `Use advisory bridge` agent/thread in `C:\Users\h\OneDrive\Documents\MCP SuperAssist with Docker.`. Include the exact command/tool call shape, working directory, timeout, observed result, expected result, and what local fallback was used. Real Flight Alert usage is part of hardening those shared tools over time.

For Android work on this machine, prefer the Android Studio MCP server when Android Studio is open. Use it to inspect real IDE state (`get_file_problems` with `errorsOnly: false`, open files, modules, dependencies, run configs, symbol info/refactors), make or reformat focused edits when useful, and build with `build_project` after code changes. Default project path: `C:\Users\h\AndroidStudioProjects\FlightAlert`; current high-traffic Kotlin file: `app/src/main/java/com/flightalert/FlightAlertRewritten.kt`. If the Android Studio MCP is not exposed in the active tool surface, use the bridge-owned consumer wrapper from this project root instead of copying wrapper logic into Flight Alert.

Android Studio audits must collect warnings, not only errors. Before completing each optimization iteration, run a full warning audit on touched files/functions and dispose of the warnings: fix behavior-preserving/scoped warnings, suppress inspections that are intentional or irrelevant for this app, or explicitly record why a warning remains because it is broad legacy monolith debt, future-safe API shape, or would require unrelated/risky refactoring. Do not silently leave fresh warnings from the current iteration. The Kotlin monolith intentionally uses snake_case helper names, locals, and properties, so `FlightAlertRewritten.kt` suppresses Kotlin naming-style inspections at file scope; if Android Studio still shows naming-style noise, disable those naming inspections in the local/project inspection profile rather than renaming the code. Ignore naming-convention warnings unless they are actual errors or block compilation; do not spend optimization turns on broad naming churn. Add legitimate Flight Alert domain/program words that Android Studio flags as spelling/typo warnings to the local IDE dictionary or project inspection dictionary, not to app runtime source, so future warning audits stay meaningful.

For renderer/performance work, Android Studio MCP function-specific audit is required before speculative hot-code edits, not optional. Use native `android_studio` tools when exposed: `search_symbol` to locate the hot function and exact line range, `get_symbol_info` on the function declaration position, `get_file_problems(errorsOnly:false)` on the file, `search_regex`/`search_text` when available for callers, related state, timing logs, and rejected experiments, and `build_project` after edits. Keep Gradle/ADB/logcat as runtime evidence, but use MCP to reduce wrong edits in complex Kotlin/Java surfaces. Do not replace the required audit with plain grep unless Android Studio is closed or MCP is genuinely unavailable; grep may supplement the audit, not substitute for it. If native `android_studio` tools are not exposed in the active chat, run:

```powershell
node C:\Users\h\plugins\pro-agent-bridge\scripts\android-studio-mcp-call.mjs --function-audit draw_aircraft_symbols --file app/src/main/java/com/flightalert/FlightAlertRewritten.kt --max-results 5 --timeout 60000
```

For Android Studio wrapper builds, prefer the first-class PowerShell-safe command instead of inline JSON:

```powershell
node C:\Users\h\plugins\pro-agent-bridge\scripts\android-studio-mcp-call.mjs --build-project --timeout 180000
```

If `buildDiagnostics.failureClass` is `kotlin-incremental-cache-corruption`, treat it as Android Studio/Kotlin incremental cache state, not a source compile failure by itself. If Gradle `compileDebugKotlin` or `assembleDebug` passes, continue using Gradle as source validation, retry once with `--build-project --rebuild --timeout 300000` if IDE validation is required, and report repeated failures to the `Use advisory bridge` agent.

This function audit must succeed for the function being changed, or the agent must state the exact blocker. For timing analysis, first identify the hot function from logs, `gfxinfo`, or perf artifacts; audit that function; search for existing timing instrumentation such as `debug_detail`, `elapsed`, `SystemClock`, `trace`, `perf`, or the function name; add narrowly scoped timing only around the exact function or branch if timing is missing; then run the bounded physical-device harness, compare p50/p95/p99, present mean FPS, produced FPS, jank, and route proof, and record the result in `docs/flightalert-performance-metrics.xlsx`.

Use subagents only for low-conflict work with a clearly bounded scope, especially read-only history/code research, test-harness additions, standalone verification helpers, and small files that can be owned independently. Put subagents in a separate workspace or explicitly assign a disjoint file set before allowing edits. Do not touch files owned by an active editing subagent until it reports back, unless the user explicitly redirects ownership.

When Pro mode / Pro Agent Bridge is available and useful, use it for parallelizable, high-value analysis such as independent diagnosis, visual/performance strategy, UI/architecture critique, or test-result review. Follow the bridge-owned consumer guide above: keep Pro use scoped and advisory, attach or summarize only selected evidence, prefer native registered MCP tools in fresh/reloaded sessions, avoid repeated tiny asks, and accept a Pro result only when a new complete assistant answer is read back for the current prompt.

Latest user instruction on June 21, 2026: Pro mode is enabled for Flight Alert optimization work. Use native Pro tools when healthy, or the bridge-owned wrapper path from the consumer guide when native tools are unavailable.

If a Pro readback shows a stub assistant message such as `I` while `Finalizing answer` is visible, treat it as pending/active thinking, continue useful local work, and re-read after the reported poll interval. If `Finalizing answer` disappears and the latest assistant is still only a stub, treat it as incomplete/transient, report the bridge diagnostic, and use local fallback or a fresh targeted ask only if Pro guidance is still worth spending.

When asking Pro to compare branches, give Pro explicit evidence from both branches: scoped diffs, `git show master:path` snippets, screenshots/videos, logs, and artifact summaries. Do not assume Pro can see local branches, the filesystem, or Git history unless Codex uploaded or inlined the relevant materials. For Flight Alert optimization work, include the current branch snippet, matching `master` snippet or trick inventory, accepted-current visual constraints, and the specific question Pro should answer.

For hard optimization problems, prefer asking Pro for candidate solutions before implementing a new substantial renderer/backend strategy, then synthesize and implement only the candidates that fit the exact-fidelity contract and current evidence. Also cue Pro on broader performance optimizations and on specific visual/performance failures that have already survived one local fix attempt. If a Pro call actually fails, returns no fresh assistant answer for the current prompt, or the local evidence already makes a solution clear enough to synthesize safely, continue locally and document that reason when it matters.

Recommended Android loop: inspect IDE diagnostics through Android Studio MCP when available, make focused edits, validate with Android Studio diagnostics/build tools, then use physical-device ADB evidence for performance and visual claims. Use Pro when its larger context, independent review, or broader optimizer perspective materially helps.

After meaningful changes, run:

```powershell
.\gradlew.bat assembleDebug
```

When an optimization attempt works, fails, is reverted, or remains pending, record it in `docs/flightalert-performance-metrics.xlsx` with the scenario and measured evidence. This is required before context compaction, handoff, or switching to a different optimization target.

## 16. Performance Workbook And Optimization Ledger

The optimization evidence ledger now lives in `docs/flightalert-performance-metrics.xlsx`. Treat that workbook as the source of truth for performance metrics, detailed debug timing/audit rows, notable gains, rejected experiments, diagnostic attempts, current iteration notes, route-proof artifacts, and chartable run history.

Before starting or completing an optimization iteration, read the workbook sheets `Runs`, `Detailed Audits`, `Trace Audits`, `Frame Correlations`, `Optimization Ledger`, `Workbook Tests`, `Workbook Test Summary`, `Best By Workload`, `Workbook Test Exclusions`, `Chart Data`, `Iteration Checks`, and `Notes`. The `Detailed Audits` sheet must preserve the detailed performance-level timing/counter rows from `Debug draw perf`, not only simplified summaries. The `Frame Correlations` sheet must preserve matched per-frame Perfetto/log metrics when frame-token correlation artifacts exist, not only a simplified GO/STOP summary. The `Optimization Ledger` sheet carries the historical accepted/rejected/deferred notes that used to live in this file.

When an optimization attempt works, fails, is reverted, or remains pending, update the workbook before switching targets or handing off. Include artifact paths, region/city, map mode, roads/borders/labels state, traffic/detail flags, produced FPS, present mean FPS, p50/p95/p99, Android jank, route proof, visual/video evidence status, warning/build status, and the accept/reject/defer reason.

The user-facing workbook chart must be an apples-to-apples benchmark lane, not a plot of whatever ran last. Keep dirty, diagnostic, video, route-failed, hidden-aircraft, layer-isolation, skip-traffic, trace/Perfetto, workload-specific, and nonmatching-city/series runs out of `Chart Data`; retain them only in raw/supporting sheets with explicit exclusion reasons. `Chart Data` should use the active comparable workbook-test series matching the latest workbook-test lane, city, map mode, roads, borders, and detail-timing flags. Each optimization iteration should include one roughly minute-budget standardized "workbook test" in the timetable-selected dense US/EU region for that time of day, with full visible UI and aircraft traffic, satellite + roads + borders/labels when relevant, route proof, human-like overlapping pan/zoom, and frame-time/FPS metrics. Prefer no-detail-timing workbook tests for user-facing FPS/p95 trend comparisons; use `TrafficDetailTiming`/`MapDetailTiming` runs as detailed diagnostic evidence in matching detail-on series or supporting sheets. Do not select a best checkpoint or restore point until suspected iterations have been compared with these standardized full workload tests.

Unless a run is explicitly an experiment and excluded from the workbook-test lane, a consistently worsening user-facing chart is a failure signal: return to the last good comparable checkpoint and retry from there rather than optimizing on top of a degraded baseline.

Use `tools/perf/BuildFlightAlertPerformanceWorkbook.mjs` to rebuild the workbook from existing perf artifacts and AGENTS/workbook ledger state. Run it with the bundled Node.js and bundled Python packages from `codex_app.load_workspace_dependencies`; the builder writes the large workbook through bundled Python/XlsxWriter and reads prior ledger rows through bundled Python/openpyxl so detailed audit history can keep growing without the artifact-tool exporter limit. Do not install repo-local spreadsheet dependencies. If adding a one-off current-iteration note without re-bloating AGENTS, pass it through `FLIGHTALERT_EXTRA_LEDGER_NOTE` before running the builder.

Keep this AGENTS file focused on rules and workflow. Do not paste long optimization-history entries here after June 22, 2026 unless the workbook is unavailable and the note is needed to prevent evidence loss; if that happens, migrate the note back into the workbook at the next opportunity.

## 17. Security / Privacy

Prefer HTTPS-only APIs and assets. Do not hardcode API keys, tokens, secrets, credentials, personal device IDs, or private test artifacts.

Avoid cleartext traffic unless there is a documented reason. Lock-screen notifications should avoid exposing sensitive aircraft details unnecessarily.

## 18. Release Behavior

Before pushing: build, inspect git status, check for duplicate project files, scan for obvious secrets, update README accurately, and clean generated junk.

Push only scoped, intentional app/source/docs changes. Only push important changes, or changes large enough to add major functionality.

When the user gives a newer concrete rule that supersedes one of these rules, the newer rule takes priority. Update this file so later agents do not preserve obsolete guidance.
