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

## 11. Codebase / Repo Discipline

Use Kotlin and work directly in the Android Studio project structure.

Keep one real project: one manifest, one root Gradle setup, and no duplicate generated/source projects. Do not commit Android Studio local machine-state churn, build outputs, temporary screenshots, secrets, `local.properties`, generated junk, or stale comparison artifacts.

## 12. Testing

Build and lint before release. Verify visual/UI changes on a connected device or emulator and use screenshots for comparison when useful.

For map performance work, automated and manual panning/zooming tests must exercise dense aircraft regions around a fresh random major city center or busy corridor in North America or Europe on every pass, not open ocean, empty polar regions, unintended rural/upstate drift, or the launcher/home screen. Include quick pinch gestures plus horizontal, vertical, and diagonal pans over visible aircraft at multiple zoom levels.

Delete temporary screenshots taken by agents afterward. Use bounded verify/fix loops: improve weak features, but do not get stuck forever and block pushing.

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
