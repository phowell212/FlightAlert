# Flight Alert Agent Rules

Flight Alert is a drone situational-awareness app. Preserve the current user experience and styling unless a visible difference fixes a real bug or honesty issue.

Rules are priority ordered. If rules conflict, follow the higher-priority rule. Newer concrete user instructions supersede older notes.

## 0. Optimization Priorities And Baseline

Optimize in this order: functionality for the end user, visuals for the end user, frame timing, then implementation elegance.

Performance wins that introduce flicker, pop, missing layers, wrong motion, changed styling, reduced information, or source dishonesty are failures.

Use the current branch/release as the normal baseline unless the user explicitly requests another comparison. The detailed optimization workflow, benchmark lanes, rejected-attempt history, and ledger live in `docs/flightalert-performance-metrics.xlsx`.

## 1. No Pretending

Never fake aircraft, map, route, photo, alert, altitude, location, or source data.

If real data is unavailable, show `Unavailable`, show `Loading`, hide the feature, disable the control, or label uncertainty clearly.

## 2. Creative Reinvention, Never The Axe

Preserve the visible/user-facing result first, then reinvent the implementation underneath until it is fast.

Do not win frame time by removing, hiding, delaying, shrinking, darkening, simplifying, skipping, or approximating accepted visuals, features, information density, aircraft morphing, map labels, tile transitions, details, photos, or alerts unless the user explicitly asks for that tradeoff.

Protected behavior includes live aircraft identity/order/presence, smooth aircraft entry/exit at screen edges, aircraft dots/symbols/outlines/shadows/colors/scales/rotations/selection rings, aircraft morphing, rotorcraft blade animation, satellite/street imagery, tile/road/reference fades, map labels and borders, county-label zoom fades, selected paths, ownship display, controls/chrome, modal/details/photo/loading states, alerts, and source-freshness honesty.

## 3. Real Sources Only

Map tiles must come from real map providers such as OpenStreetMap, CARTO, or real satellite imagery providers.

Aircraft traffic must come from live aircraft APIs or documented live feed formats. Flight paths must come from real trace/path APIs after an aircraft is selected. Do not accumulate app-session aircraft positions and call that a real flight path.

## 4. Debugging Stays Off App

Keep debugging, profiling, and measurement code out of the app runtime. Do not add app-side counters, timing wrappers, debug flags, perf intents, diagnostic overlays, hidden debug gestures, or per-frame log strings to collect stats. Gather stats through external probing and analysis: Android Studio Profiler, Perfetto/System Trace, Android Studio MCP/IDE debugger inspection, Logcat capture of existing user-facing/status logs, tests, scripts, and workbook/tool analysis. If a measurement requires editing production app code, stop and choose an external probe instead.

## 5. Safety Bias

Accuracy matters more than impressive visuals. Vertical separation is critical.

If altitude, location, source freshness, or feed data is missing, do not claim an aircraft is safe or inside/outside an alert volume unless the calculation is supported.

## 6. Alerts

Alerts are based on a 3D volume around the user: horizontal distance plus vertical separation.

Notify when an aircraft enters or leaves the configured alert volume. Do not spam repeat notifications. The persistent extreme-priority notification is non-clearable while active, self-clearing when empty, and should only beep or buzz on the first transition from no extreme-priority aircraft to at least one.

## 7. UI Honesty

Anything that looks like a button must be interactable.

Unavailable or unverified features must be hidden, disabled, or labeled honestly. Do not show fake radar graphics or fake aviation overlays. Attribution can move later, but do not misrepresent providers.

## 8. Layout Quality

No text clipping, hidden labels, or strange overlaps.

Support portrait, landscape, folding devices, resizing, and emulator hardware input. Back should close overlays/screens before leaving the app.

## 9. Map Interaction

Panning, pinch zoom, wheel zoom, and keyboard zoom should feel smooth.

Aircraft sprites may move smoothly every frame using interpolation from speed, heading, and last report. Do not request aircraft positions every frame.

No aircraft sprite, dot, outline, or symbol gate may create a sudden apparent size jump anywhere in the zoom range. Transitions must be continuous curves tied to zoom/appearance state.

## 10. Flight Paths

Only show the path button when a real usable path was retrieved.

The path must represent the selected aircraft's actual current flight/trace, not stale old legs. If the trace endpoint stops before the live sprite position, extend the visible trail to the current live position only when the live report is fresh.

## 11. Aircraft Details And Photos

Try exact aircraft photos first from real aircraft-photo sources.

Representative same make/model photos are acceptable only with a clear "not this exact aircraft" note. Search-engine fallback photos must be labeled investigable and include source/proof view buttons. Claimed make/model/owner data must come from official or documented sources.

## 12. Military Handling

Only show military-specific stats if the aircraft is actually tagged military.

Military origin/base claims require real flight-origin and aerodrome/source data. Registry-country fallback may use real ICAO 24-bit allocation ranges when registration prefix data is unavailable, but it must be labeled allocation-derived.

## 13. Codebase / Repo Discipline

Use Kotlin and work directly in the Android Studio project structure.

Keep one real project: one manifest, one root Gradle setup, and no duplicate generated/source projects. Do not commit Android Studio machine state, build outputs, temporary screenshots/videos, secrets, `local.properties`, generated junk, stale comparison artifacts, or agent scratch output.

## 14. Testing

Build before release. Use lint and unit/instrumented tests when the touched area warrants them.

Use emulators for layout/aspect/theme checks. Use physical-device video for optimization, timing, responsiveness, smoothness, flicker, popping, aircraft continuity, border behavior, and road/reference-layer motion. Screenshots are scouting evidence only for temporal rendering bugs.

For optimization work, follow `docs/flightalert-performance-metrics.xlsx` `Performance Notes`, including timetable-selected traffic regions, comparable 60-second apples-to-apples multi-zoom runs, frame-time/FPS metrics, thermal notes, and workbook graph updates. Keep test code, perf scripts, videos, traces, and run output in `../FlightAlert-test-artifacts/`, not in this app repo.

For whole-world reference-dictionary baking, keep scripts, raw downloads, reference caches, shard outputs, package outputs, validation reports, workbooks, and videos outside the repo. Keep active Phase 1 Experiment 3 work under `D:\FlightAlert-test-artifacts\experiment 3`. Treat `D:\FlightAlert-test-artifacts\experiment 1` as the first historical cook/archive and `D:\FlightAlert-test-artifacts\experiment 2` as a stopped/dud follow-up reference, not as implementation input. The old folders `C:\Users\Phineas\Documents\FlightAlert-test-artifacts` and `E:\FlightAlert-test-artifacts` may be actively deleted or recycled; do not work inside them. If fast E-side scratch is needed, use a separate temporary sibling such as `E:\FlightAlert-experiment3-scratch`, then verify and drain outputs back to the D Experiment 3 root. If small C-side Phase 2 probe scripts are needed, use a separate sibling such as `C:\Users\Phineas\Documents\FlightAlert-experiment3-probes`, and write durable outputs back to D. Only the final validated phone-consumable package/artifact should be copied out of the Experiment 3 D-root. Keep the app repo limited to source/docs and small manifest-style guidance.

## 15. Code Organization

Flight Alert code should read like objects doing jobs.

`FlightMapView` coordinates map state, selection, draw order, and user input. Feature objects own feed parsing, route validation, photo lookup, impact scoring, settings math, motion projection, and feature-specific rules.

Prefer small objects/files with narrow public methods. Keep settings and tuning values in explicit settings files. See `docs/code-organization.md`.

In hot paths, prefer typed fields, numeric keys, sets/maps, arrays, or cached normalized values over parsing/composing strings. Strings are fine at source boundaries such as binCraft/feed parsing, for user-facing text/status labels, or where design notes explicitly call strings the right representation; otherwise do not organize runtime state around fresh string processing.

## 16. Agent Workflow

Before changing code, read this file and `docs/code-organization.md`.

For optimization work, also read `docs/flightalert-performance-metrics.xlsx`, especially `Performance Notes`. Keep detailed optimization procedure in that workbook, not in `AGENTS.md`.

For the Esri-derived whole-world reference dictionary effort, treat Phase 1 as an external bake/schema/package task before phone renderer work. Experiment 3 starts from the current raster-only app state: there is no user-facing or code-level vector reference mode for borders or labels. Phase 1 must lock onto the same Esri reference source/path used by Flight Alert's accepted satellite/reference baseline before large data generation begins. Do not silently substitute a non-Esri structured map provider for the Experiment 3 dictionary source; if Esri structured data is unavailable, design and prove an Esri raster-tile analysis/extraction path instead. Phase 1 must produce a globally scalable dictionary plan and coherent proof slice; phone code changes belong to Phase 2 after the schema, LOD hierarchy, storage layout, and validation approach are documented. Phase 2 must replace superseded raster-reference work with the accepted dictionary path as an optimization; do not add a second reference-rendering layer. Phase 1 builder jobs should not target a fixed builder count. Adapt builder/shard concurrency until sustained average CPU utilization is above 85% when enough work is available, while avoiding disk stalls, memory pressure, provider throttling, and low-space conditions. Report percent complete at least every 5 minutes while running, including active builder count and each builder's own percent complete. Save those metrics to an Experiment 3 workbook under `D:\FlightAlert-test-artifacts\experiment 3` and update its graphs as the run progresses.

Experiment 3 agents must keep one explicit active goal and continue working until that goal is complete, spanning Phase 0 source recovery/design reset, Phase 1 Esri-derived dictionary construction/validation, and Phase 2 phone implementation/validation. Do not mark the goal complete after a broad context pass, partial report, proof slice, or build. Complete it only when all three phases are validated, or mark it blocked only for a concrete blocker that prevents meaningful progress.

If Pro Agent Bridge or Android Studio MCP usage fails, report diagnostics to the `Use advisory bridge` thread as described in the workbook.

Use subagents only for low-conflict, bounded work. Do not touch files owned by an active editing subagent unless the user redirects ownership.

After meaningful non-optimization changes, run `.\gradlew.bat assembleDebug`. For optimization build/test/logging requirements, follow the workbook.

## 17. Performance Workbook And Optimization Ledger

The durable performance notebook is `docs/flightalert-performance-metrics.xlsx`.

Use `../FlightAlert-test-artifacts/perf/BuildFlightAlertPerformanceWorkbook.mjs` to rebuild it. Do not create a second durable performance source of truth.

Every accepted optimization iteration must be recorded in the workbook with comparable run parameters, target region/city, map mode, layer state, duration, thermal state, frame metrics, and graph updates.

## 18. Security / Privacy

Prefer HTTPS-only APIs and assets. Do not hardcode API keys, tokens, secrets, credentials, personal device IDs, or private test artifacts.

Avoid cleartext traffic unless documented. Lock-screen notifications should avoid exposing sensitive aircraft details unnecessarily.

## 19. Release Behavior

Before pushing: build, inspect git status, check for duplicate project files, scan for obvious secrets, update README accurately, and clean generated junk.

Push only scoped, intentional app/source/docs changes.
