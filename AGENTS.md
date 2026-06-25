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

## 4. Safety Bias

Accuracy matters more than impressive visuals. Vertical separation is critical.

If altitude, location, source freshness, or feed data is missing, do not claim an aircraft is safe or inside/outside an alert volume unless the calculation is supported.

## 5. Alerts

Alerts are based on a 3D volume around the user: horizontal distance plus vertical separation.

Notify when an aircraft enters or leaves the configured alert volume. Do not spam repeat notifications. The persistent extreme-priority notification is non-clearable while active, self-clearing when empty, and should only beep or buzz on the first transition from no extreme-priority aircraft to at least one.

## 6. UI Honesty

Anything that looks like a button must be interactable.

Unavailable or unverified features must be hidden, disabled, or labeled honestly. Do not show fake radar graphics or fake aviation overlays. Attribution can move later, but do not misrepresent providers.

## 7. Layout Quality

No text clipping, hidden labels, or strange overlaps.

Support portrait, landscape, folding devices, resizing, and emulator hardware input. Back should close overlays/screens before leaving the app.

## 8. Map Interaction

Panning, pinch zoom, wheel zoom, and keyboard zoom should feel smooth.

Aircraft sprites may move smoothly every frame using interpolation from speed, heading, and last report. Do not request aircraft positions every frame.

No aircraft sprite, dot, outline, or symbol gate may create a sudden apparent size jump anywhere in the zoom range. Transitions must be continuous curves tied to zoom/appearance state.

## 9. Flight Paths

Only show the path button when a real usable path was retrieved.

The path must represent the selected aircraft's actual current flight/trace, not stale old legs. If the trace endpoint stops before the live sprite position, extend the visible trail to the current live position only when the live report is fresh.

## 10. Aircraft Details And Photos

Try exact aircraft photos first from real aircraft-photo sources.

Representative same make/model photos are acceptable only with a clear "not this exact aircraft" note. Search-engine fallback photos must be labeled investigable and include source/proof view buttons. Claimed make/model/owner data must come from official or documented sources.

## 11. Military Handling

Only show military-specific stats if the aircraft is actually tagged military.

Military origin/base claims require real flight-origin and aerodrome/source data. Registry-country fallback may use real ICAO 24-bit allocation ranges when registration prefix data is unavailable, but it must be labeled allocation-derived.

## 12. Codebase / Repo Discipline

Use Kotlin and work directly in the Android Studio project structure.

Keep one real project: one manifest, one root Gradle setup, and no duplicate generated/source projects. Do not commit Android Studio machine state, build outputs, temporary screenshots/videos, secrets, `local.properties`, generated junk, stale comparison artifacts, or agent scratch output.

## 13. Testing

Build before release. Use lint and unit/instrumented tests when the touched area warrants them.

Use emulators for layout/aspect/theme checks. Use physical-device video for optimization, timing, responsiveness, smoothness, flicker, popping, aircraft continuity, border behavior, and road/reference-layer motion. Screenshots are scouting evidence only for temporal rendering bugs.

For optimization work, follow `docs/flightalert-performance-metrics.xlsx` `Performance Notes`, including timetable-selected traffic regions, comparable 60-second apples-to-apples multi-zoom runs, frame-time/FPS metrics, thermal notes, and workbook graph updates.

## 14. Code Organization

Flight Alert code should read like objects doing jobs.

`FlightMapView` coordinates map state, selection, draw order, and user input. Feature objects own feed parsing, route validation, photo lookup, impact scoring, settings math, motion projection, and feature-specific rules.

Prefer small objects/files with narrow public methods. Keep settings and tuning values in explicit settings files. See `docs/code-organization.md`.

## 15. Agent Workflow

Before changing code, read this file and `docs/code-organization.md`.

For optimization work, also read `docs/flightalert-performance-metrics.xlsx`, especially `Performance Notes`. Keep detailed optimization procedure in that workbook, not in `AGENTS.md`.

If Pro Agent Bridge or Android Studio MCP usage fails, report diagnostics to the `Use advisory bridge` thread as described in the workbook.

Use subagents only for low-conflict, bounded work. Do not touch files owned by an active editing subagent unless the user redirects ownership.

After meaningful non-optimization changes, run `.\gradlew.bat assembleDebug`. For optimization build/test/logging requirements, follow the workbook.

## 16. Performance Workbook And Optimization Ledger

The durable performance notebook is `docs/flightalert-performance-metrics.xlsx`.

Use `tools/perf/BuildFlightAlertPerformanceWorkbook.mjs` to rebuild it. Do not create a second durable performance source of truth.

Every accepted optimization iteration must be recorded in the workbook with comparable run parameters, target region/city, map mode, layer state, duration, thermal state, frame metrics, and graph updates.

## 17. Security / Privacy

Prefer HTTPS-only APIs and assets. Do not hardcode API keys, tokens, secrets, credentials, personal device IDs, or private test artifacts.

Avoid cleartext traffic unless documented. Lock-screen notifications should avoid exposing sensitive aircraft details unnecessarily.

## 18. Release Behavior

Before pushing: build, inspect git status, check for duplicate project files, scan for obvious secrets, update README accurately, and clean generated junk.

Push only scoped, intentional app/source/docs changes.
