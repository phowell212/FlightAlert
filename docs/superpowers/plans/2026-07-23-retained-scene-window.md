# Continuous Reference Scene Window Plan

**Goal:** Keep borders and labels ready across repeated pan/zoom motion without
label relocation, blank regions, or new gesture-time work.

## Constraints

- No try/catch, I/O, parsing, measurement, collision planning, or collection
  creation in the gesture hot path.
- Continuing labels retain their transformed visual anchor.
- Normal bands use a 160-tile / 8,192 px target limit so an integer-scale Fold
  scene covers the accepted 660 px pan; z0 through z4 use their already-bounded
  256-tile z4 world cache.
- Temporal acceptance uses dense-land real-phone video, never screenshots.

## Task 1: Stable and replenished labels

- Prove same-scale coverage, zoom/LOD invalidation, half-padding staging, and
  identity-based history promotion with failing tests.
- Increase desired retained overscan and add the z0-through-z4 world-aware rule.
- Keep the active scene until it loses coverage; stage its exact successor in
  bounded history while idle.
- Treat an ID with a moved transformed anchor as leave/enter rather than
  continuing.
- Skip target-tile enumeration on ordinary settled draws.

## Task 2: Matching border coverage

- Prove idle raster-ring sizing and one-ring interaction behavior with failing
  tests.
- Warm enough idle rings to match retained-scene overscan.
- Keep raster creation suspended and avoid relevance-set cloning during motion.

## Task 3: Continuous multi-band zoom

- Prove that zoom-ahead scenes are bounded, complete, visible in the viewport,
  and eligible before their nominal presentation zoom.
- Preserve completed future scenes across gesture entry while cancelling stale
  desired tile work.
- Keep the dominant participant and its opacity when a fade is interrupted.
- Keep stable label IDs fully opaque; fade only entering and leaving membership
  with full-duration curves.
- Keep a complete resident boundary scene and lower safety scene available
  through reverse zoom.

## Task 4: Verify the actual outcome

- Force-run the injected reference tests, `assembleDebug`, and `lintDebug`.
- Preserve device preferences byte-for-byte, install the exact APK, and use the
  exact Morocco zoom fixture.
- Record three progressive 660 px / 900 ms east pans followed by three returns,
  each with a 1.5 second post-`ACTION_UP` tail. This crosses staged scene
  boundaries instead of hiding inside one scene.
- Analyze every video frame for blank sectors, geographic attachment, pacing,
  and post-release reference-mask change.
- Recheck meaningful low, middle, and high active bands, including cold z3/z4
  latency if the warm videos pass.
- Review the scoped diff, remove tracked `.superpowers` files while preserving
  the ignored local test workspace, and publish the verified checkpoint to
  `origin/master`.

## Verified temporal result

- Centered z4 multi-band pinch: no frame below 10 percent reference density,
  14.38 percent floor, and one approximately 92 ms sub-25-percent interval.
- Off-center reversal: no doubled-label cohort, loaded-section cutoff, or
  demonstrated anchor relocation.
- z3 world pan and z7.5 dense pan: zero blank grid cells and geographically
  attached reference motion. Dense-pan steady motion has no gap above 33 ms.
- The experimental early coarse-border takeover was rejected because it did not
  remove the remaining rare composition stalls and introduced a sharpness risk.
