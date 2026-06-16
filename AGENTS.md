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

For map performance work, automated and manual panning/zooming tests must exercise dense aircraft regions around a major city center with major airports/corridors in the United States, Europe, or a Mexico City-class North American megacity. Do not test over open ocean, empty polar regions, unintended rural/upstate drift, upper Canada, smaller fallback metros, or the launcher/home screen.

Include quick anchored pinch gestures, smooth slow anchored pinch gestures, horizontal pans, vertical pans, diagonal pans, and combined pinch-while-panning gestures over visible aircraft at multiple zoom levels. Pinch tests must keep the target city/traffic focus anchored so zooming back in returns to the same target.

For visual completeness checks, compare against the latest valid feature-complete UI state. Capture or inspect the app at rest, right as movement starts, and during active movement so panning/zooming-only flashes, outline changes, tile flicker, or morph jumps are treated as regressions.

Every meaningful jank/frame-time test should report frame timing and jank evidence, not only subjective smoothness or screenshots. Capture or summarize `gfxinfo`/FrameTimeline data when available, including Android jank percentage, 120Hz present-drop or latency-miss percentage, p50/p95/p99 frame timing, and an FPS-equivalent or present cadence note.

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

## 16. Security / Privacy

Prefer HTTPS-only APIs and assets. Do not hardcode API keys, tokens, secrets, credentials, personal device IDs, or private test artifacts.

Avoid cleartext traffic unless there is a documented reason. Lock-screen notifications should avoid exposing sensitive aircraft details unnecessarily.

## 17. Release Behavior

Before pushing: build, inspect git status, check for duplicate project files, scan for obvious secrets, update README accurately, and clean generated junk.

Push only scoped, intentional app/source/docs changes. Only push important changes, or changes large enough to add major functionality.

When the user gives a newer concrete rule that supersedes one of these rules, the newer rule takes priority. Update this file so later agents do not preserve obsolete guidance.
