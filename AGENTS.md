# Flight Alert Agent Guardrails

Flight Alert is an Android drone situational-awareness app. Preserve its established behavior and visual identity unless the user requests a change or a real bug, safety issue, or honesty problem requires one.

This file contains durable repository constraints. It is not a backlog, roadmap, release checklist, or source of work. Current, concrete user instructions take precedence.

## Scope and priorities

- Work only on the current request and the changes needed to complete or verify it. Do not infer tasks from this file.
- Do not start unrelated cleanup, refactors, experiments, profiling, releases, pushes, or broad verification passes.
- Prioritize user functionality, visual fidelity, frame timing, then implementation elegance.
- Use the current working tree as the baseline unless the user names another one. Preserve unrelated user changes.
- A performance change is a regression if it causes missing information, flicker, popping, incorrect motion, altered styling, reduced honesty, or broken interaction.

## Truth, sources, and safety

- Never fabricate aircraft, map, route, photo, alert, altitude, location, freshness, or provider data.
- When real data is unavailable, show `Unavailable` or `Loading`, hide or disable the feature, or label the uncertainty clearly.
- Use real, documented sources. A session-built position history is not a real flight trace, and representative media must not be presented as an exact match.
- Claims about identity, ownership, origin, military status, location, or safety must be supported by the available source data.
- Alert decisions use both horizontal distance and vertical separation. Missing or stale altitude, location, or feed data must not produce an unsupported safe/unsafe or inside/outside claim.
- Anything presented as a control must work. Unavailable controls and provider attribution must be represented honestly.

## UI and rendering invariants

- Preserve accepted content, styling, information density, layers, labels, borders, aircraft presentation, paths, controls, alerts, and loading/error states unless the request explicitly changes them.
- Avoid flicker, popping, missing layers, abrupt sprite-size changes, or discontinuous transitions while panning, zooming, selecting, or crossing screen edges.
- Keep animation and zoom-dependent rendering continuous. Do not turn a frame-rendering path into network polling.
- Prevent clipped text, hidden labels, and unintended overlaps. Support portrait, landscape, resizing, and folding layouts where the touched UI applies.
- Back navigation should dismiss the active overlay or screen before leaving the app.

## Code and diagnostics

- Follow the existing Kotlin/Gradle Android project structure and reuse established code before adding another abstraction or dependency.
- Fix root causes at the shared path used by all affected callers.
- `FlightMapView` coordinates map state, draw order, selection, and input; feature-specific objects should own parsing, validation, lookup, scoring, settings math, projection, and feature rules.
- Prefer small files and narrow public APIs. In hot paths, prefer typed fields, numeric keys, collections, and cached normalized values over repeated string parsing or composition.
- Keep temporary diagnostics, per-frame logging, counters, profiling hooks, debug gestures, and measurement overlays out of production runtime code. Prefer external profiling and existing logs.

## Repository, documentation, and privacy

- Maintain one buildable Android project. Do not create duplicate source trees, manifests, or Gradle projects.
- Gradle and Android Studio project configuration is intentionally local-only. Do not add those files to Git.
- Do not modify, delete, or commit user-local Android Studio state. Keep IDE files, `local.properties`, build outputs, generated files, temporary media, comparison artifacts, agent scratch files, and secrets out of Git.
- `references/` contains large, immutable runtime dictionary data, not experiment tooling. Do not copy, rewrite, hash, or include it in broad text scans unless the task requires it. If the assets move or change names, update every corresponding Kotlin path and format expectation.
- Keep `README.md` public-facing and accurate. Keep only reusable agent constraints here; do not add task prompts, plans, run logs, generated evidence, or stale experiment notes.
- Prefer HTTPS. Never hardcode credentials, API keys, tokens, personal device IDs, or private test artifacts. Avoid exposing sensitive details in lock-screen notifications.

## Verification and release discipline

- Use the narrowest check that can disprove the change: targeted tests, compilation, lint, a host check, or an emulator as appropriate.
- Run `./gradlew assembleDebug` (or `gradlew.bat assembleDebug` on Windows) after meaningful source or build changes and before a release.
- Do not use a physical device unless the current user request permits it. Never claim unobserved visual or timing behavior was verified; state the remaining gap.
- Before an explicitly requested push or release, inspect Git status, confirm the diff is scoped, and check for secrets, generated junk, and duplicate project files.
- Push only intentional changes and only when the user asks.
