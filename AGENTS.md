# Flight Alert Agent Guardrails

Flight Alert is a drone situational-awareness app. Preserve the current user experience and styling unless a visible difference fixes a real bug or honesty issue.

This file is durable guidance for agents working in the repo. It is not a backlog, task prompt, roadmap, or active goal source. Use it only as constraints for the user's current request.

Rules are priority ordered. If rules conflict, follow the higher-priority rule. Newer concrete user instructions supersede older notes.

## Scope Guardrails

Do only the work requested in the current chat unless additional work is required to complete or verify that request.

Do not infer a task from protected behaviors, examples, release checks, source names, file paths, documentation rules, or testing guidance in this file.

Do not start unrelated cleanup, refactors, release work, artifact-generation work, scans, performance investigations, pushes, or broad verification passes unless the user asks for them or they are directly necessary for the current request.

When a specific task needs detailed instructions, keep those instructions outside durable repo guidance unless the details are included only to explain a reusable method.

## 0. Priorities And Baseline

Prioritize in this order: functionality for the end user, visuals for the end user, frame timing, then implementation elegance.

Performance wins that introduce flicker, pop, missing layers, wrong motion, changed styling, reduced information, or source dishonesty are failures.

Use the current branch/release as the normal baseline unless the user explicitly requests another comparison.

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

Keep debugging, profiling, and measurement code out of the app runtime. Do not add app-side counters, timing wrappers, debug flags, perf intents, diagnostic overlays, hidden debug gestures, or per-frame log strings to collect stats. Gather stats through external probing and analysis: Android Studio Profiler, Perfetto/System Trace, Android Studio MCP/IDE debugger inspection, Logcat capture of existing user-facing/status logs, tests, scripts, and external tool analysis. If a measurement requires editing production app code, stop and choose an external probe instead.

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

Use emulators for layout/aspect/theme checks. Use physical-device video for timing, responsiveness, smoothness, flicker, popping, aircraft continuity, border behavior, and road/reference-layer motion when the requested work warrants it. Screenshots are scouting evidence only for temporal rendering bugs.

## 15. Code Organization

Flight Alert code should read like objects doing jobs.

`FlightMapView` coordinates map state, selection, draw order, and user input. Feature objects own feed parsing, route validation, photo lookup, impact scoring, settings math, motion projection, and feature-specific rules.

Prefer small objects/files with narrow public methods. Keep settings and tuning values in explicit settings files. See `docs/code-organization.md`.

In hot paths, prefer typed fields, numeric keys, sets/maps, arrays, or cached normalized values over parsing/composing strings. Strings are fine at source boundaries such as binCraft/feed parsing, for user-facing text/status labels, or where design notes explicitly call strings the right representation; otherwise do not organize runtime state around fresh string processing.

## 16. Agent Workflow

Before changing code, read this file and `docs/code-organization.md`.

Use subagents only for low-conflict, bounded work. Do not touch files owned by an active editing subagent unless the user redirects ownership.

After meaningful code changes, run `.\gradlew.bat assembleDebug` when the touched area warrants it or before release.

## 17. Documentation

Keep `README.md` public-facing and user-friendly. Keep methodology, agent workflow, and repeatable contributor rules in this file or durable docs under `docs/`.

Do not store one-off task prompts, run logs, generated evidence, temporary instructions, stale experiment notes, or scratch plans in durable docs unless they are intentionally included to explain methodology.

## 18. Security / Privacy

Prefer HTTPS-only APIs and assets. Do not hardcode API keys, tokens, secrets, credentials, personal device IDs, or private test artifacts.

Avoid cleartext traffic unless documented. Lock-screen notifications should avoid exposing sensitive aircraft details unnecessarily.

## 19. Release Behavior

Before pushing: build, inspect git status, check for duplicate project files, scan for obvious secrets, update README accurately, and clean generated junk.

Push only scoped, intentional app/source/docs changes.
