# Single Android Application Project Cleanup Design

**Status:** Approved in chat on 2026-07-10

## Purpose

Flight Alert must have one authoritative Android application project, one source tree, one manifest, and one IDE launch identity before Experiment 8 begins.

## Proven Current State

- `build.gradle.kts` applies the Android application plugin at the repository root and maps the main source set to `app/src/main`.
- `settings.gradle.kts` declares no subprojects, and `gradlew projects` reports only root project `Flight Alert`.
- Git tracks one application manifest and one `MainActivity.kt`.
- No `app/build.gradle` or `app/build.gradle.kts` exists.
- The live root `build` tree is current. The ignored `app/build` tree is older, contains a differently hashed `app-debug.apk`, and cannot be produced by the current Gradle project graph.
- Ignored Android Studio state retains an obsolete `app` run configuration targeting module `Flight_Alert.app`, alongside the valid `Flight_Alert` configuration.

The apparent second app is therefore stale generated output and stale IDE state, not a second authoritative source app.

## Chosen Design

Keep the existing root Android application project and `app/src/main` source tree unchanged.

Remove only these stale local artifacts:

1. The ignored `app/build` directory.
2. The obsolete Android Studio `app` run configuration targeting `Flight_Alert.app`.
3. The obsolete `app` deployment-target selection state.

Run the root Gradle clean task and rebuild from the authoritative project. Do not convert the repository to a `:app` subproject, move source files, alter package identity, or touch the existing Experiment 7 working changes.

## Safety and Data Preservation

- Resolve and validate every recursively removed path before deletion; it must be inside the repository and equal the specifically approved generated directory.
- Do not remove `app/src`, any tracked file, the root Gradle setup, or the user's uncommitted Experiment 7 files.
- IDE changes are limited to the two stale ignored entries. Other workspace and run configuration state remains intact.
- Root build outputs are reproducible through Gradle and contain no authoritative source.

## Verification

The cleanup passes only when all of the following are freshly demonstrated:

1. `gradlew projects` succeeds and reports the root project with no subprojects.
2. A non-generated file scan finds exactly one `settings.gradle[.kts]`, one application `build.gradle[.kts]`, and one `AndroidManifest.xml`.
3. `app/build` remains absent after a fresh `gradlew assembleDebug`.
4. Android Studio's local state contains the valid `Flight_Alert` launch configuration and no `Flight_Alert.app` module or `app` deployment entry.
5. `gradlew assembleDebug` succeeds from the repository root.
6. Git status retains the pre-existing Experiment 7 modifications and introduces no generated files or unrelated tracked changes.

Gradle may maintain internal and published APK artifacts inside the one authoritative root `build` tree; those are outputs of one application project, not duplicate applications.

## Rejected Alternative

Converting the current root application into a conventional `:app` subproject would require moving Gradle configuration and retesting tooling paths without fixing any source-level duplication. It is outside this cleanup's scope.
