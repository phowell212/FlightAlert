# Task 2 — Final Real-Fold Temporal Acceptance

## Status

**ACCEPTED on the real-device, marker-window, pacing, latency, continuity,
blank-sector, and cleanup criteria below.**

No screenshot or settings-screen evidence was created or used. The accepted
evidence is three full MP4 action recordings. Local-file playback was rejected
by the in-app browser's URL security policy in both the worker and root
sessions, so no unsupported human-playback claim is made. The temporal findings
below come from decoding every video frame, action-window PTS, aligned app
framestats, color/edge continuity, affine motion, blank-sector, and settle
window analysis. The delivered MP4s remain directly human-viewable.

## Exact Inputs

- Worktree:
  `C:\Users\h\AndroidStudioProjects\FlightAlert-readable-rebase`
- HEAD:
  `22adbdde050c2f0e8daafc335a1c9d048012efc1`
- APK:
  `C:\Users\h\AndroidStudioProjects\FlightAlert-readable-rebase\build\outputs\apk\debug\Flight Alert-debug.apk`
- Local and installed APK SHA-256:
  `064965B11045C300EBEB5FCB3C82E798537BB7D9948678A441E4A38246F6FE61`
- Device: physical Samsung SM-F946U, serial `RFCX40KPN3B`
- Emulator use: none
- Mock location: Morocco, `31.7917,-7.0926`
- Timed merged harness:
  `C:\Users\h\OneDrive\Documents\Flight Alert\artifact-work\readable-rebase\google-polish\harness\reference-gestures-timed-v2.jar`
- Harness SHA-256:
  `797A5642FF79B0F2D23A112A99054E4780BA75D9DF1C73A0482F9A5009DF2013`

The harness smoke and every accepted band completed the real test method with
`OK (1 test)` and exactly 16 ordered ACTION START/END markers for all eight
pinch/pan actions.

## Intentional Zoom Coverage

| Band | Exact prelaunch readback | Exact prerun readback | Markers |
|---|---:|---:|---:|
| z3–5 | 3.5 | 3.5 | 16 |
| z7–9 | 7.5 | 7.5 | 16 |
| z10–12 | 10.5 | 10.5 | 16 |

The z3.5 band intentionally exercises the low-zoom policy where z0–z3 use z4
reference data. Every run was centered on dense Morocco/Spain/Algeria land
content, not ocean-only or reference-empty content.

## Baseline-Comparable Video Pacing

Frame deltas include only consecutive MP4 presentation timestamps whose two
endpoints are inside the same marker action window. Encoder frame zero was
aligned to the `PipelineWatcher frameIndex 0` log timestamp; marker timestamps
have approximately 1 ms precision.

| Band | Final action frames | p50 / p95 / p99 / max | >33 ms | Prior accepted baseline |
|---|---:|---|---:|---|
| z3–5 | 846 | 8.322 / 9.344 / 17.112 / 42.200 ms | 3 | 8.4 / 9.2 / 16.0 / 41.5; 4/832 |
| z7–9 clean | 832 | 8.350 / 9.472 / 16.837 / 33.544 ms | 2 | 8.3 / 9.5 / 15.3 / 40.9; 3/825 |
| z10–12 | 848 | 8.323 / 9.429 / 16.672 / 41.500 ms | 3 | 8.3 / 9.2 / 10.0 / 32.8; 0/857 |

The central pacing is stable across all three bands. The z10 tail is not as
unusually clean as its prior single run, but its three >33 ms intervals are
isolated to the two pinch-out transitions; every pan and pinch-in interval is
at or below 17.034 ms in the video. There is no low-zoom-only cost and no broad
high-zoom slowdown.

## Visible Response

Visible response is measured from each action marker to the first encoded frame
where at least 10% of the cropped map changes by more than 10 grayscale levels.

| Band | Final response range | Prior accepted range |
|---|---:|---:|
| z3–5 | 71–164 ms | 63–152 ms |
| z7–9 clean | 82–151 ms | 76–159 ms |
| z10–12 | 77–144 ms | 66–143 ms |

The first z7 continuous-collector run reached 187 ms and had an encoder-only
58.8 ms gap with no matching pan stall in app framestats. Per the failure rule,
only z7 was rerun without the collector. Its response returned to 82–151 ms,
identifying continuous dumpsys sampling as the measurement perturbation rather
than reference planner latency. The clean z7 MP4 is the accepted temporal and
baseline-comparison artifact; the continuous z7 capture is secondary app-frame
coverage only.

## Full Action-Window App Framestats

A single post-run `gfxinfo` dump was rejected because this Fold retains only the
last 120 rows, covering only the tail of the last gesture. The documented
`debug.hwui.profile.maxframes` property accepted `2000` but the OEM renderer
still returned 120 rows; the property was restored to its original empty value.

The corrected collector sampled during the run and merged rows by unique
`FrameTimelineVsyncId`. `FrameCompleted - IntendedVsync` was then calculated
only inside the eight monotonic marker intervals.

| Band | Action rows | p50 / p95 / p99 / max | >33 ms |
|---|---:|---|---:|
| z3–5 | 871 | 8.747 / 15.005 / 36.908 / 53.184 ms | 12 |
| z7–9 | 874 | 9.659 / 16.912 / 31.940 / 42.073 ms | 7 |
| z10–12 | 870 | 9.615 / 16.155 / 34.228 / 47.326 ms | 12 |

Every action is covered: roughly 70–73 app frames per pan and 143–148 per
pinch. These app-completion figures are deliberately reported separately and
are not compared directly with the MP4-presentation baseline.

## Machine-Supported Temporal Findings

All frames in the accepted videos were decoded: 1,068 at z3–5, 1,020 in the
clean z7–9 run, and 1,173 at z10–12.

- Zero uniform blank sectors were found across 30,600 action-time map grid
  cells. Reference-like bright/cyan/green edge signal remained nonzero in every
  action at all bands.
- After persistent screen-space UI pixels were excluded, pan-time reference
  signal followed map motion:
  - z3–5: aligned/static F1 `0.917/0.185`, alignment better in 12/12 samples.
  - clean z7–9: `0.741/0.145`, alignment better in 11/12 samples.
  - z10–12: `0.606/0.092`, alignment better in 12/12 samples.
- Final post-action settle windows stayed stable for 1.8–2.6 seconds:
  reference-signal count range was 5.6% at z3, 5.7% at clean z7, and 3.8% at
  z10. No post-settle wholesale label replacement or overlay disappearance was
  detected.
- No crash, ANR, out-of-memory event, reference failure, dictionary failure,
  missing-package error, or timeout appears in any accepted run log.
- The one large z3 bright-edge discontinuity was spatially confined to a
  108-pixel-wide central strip spanning the satellite crop. Its lost pixels
  were brown satellite imagery (mean BGR approximately `182/175/157`,
  saturation 37), not the near-white/cyan reference paint. It is a raster tile
  resolution change, not a border/label blank or pop.

These checks support geographically attached references, continuous screen
coverage, expected hierarchy changes during zoom, and stable post-settle
placement. They do not substitute a claim that a person watched the local MP4
inside the unavailable in-app browser.

## Accepted Videos

- z3–5:
  `C:\Users\h\OneDrive\Documents\Flight Alert\artifact-work\readable-rebase\google-polish\final-22adbdd\z3.5-z3-5.mp4`
- z7–9 clean:
  `C:\Users\h\OneDrive\Documents\Flight Alert\artifact-work\readable-rebase\google-polish\final-22adbdd\z7.5-z7-9-clean.mp4`
- z10–12:
  `C:\Users\h\OneDrive\Documents\Flight Alert\artifact-work\readable-rebase\google-polish\final-22adbdd\z10.5-z10-12.mp4`

Reproducible metrics and raw evidence are in:

`C:\Users\h\OneDrive\Documents\Flight Alert\artifact-work\readable-rebase\google-polish\final-22adbdd`

Notable files:

- `action-window-metrics.json`
- `temporal-health-metrics.json`
- `z7.5-z7-9-clean-metrics.json`
- `z7.5-z7-9-clean-temporal.json`
- `*-markers-monotonic.txt`
- `*-gfxinfo-continuous.txt`
- `*-logcat.txt`
- `cleanup-evidence-final.txt`

## Final Device Restoration

Fresh cleanup verification passed at `2026-07-23T09:40:36.5957340Z`.

- Flight Alert was force-stopped.
- GPS and network test-provider removal both exited 0.
- Original preferences were restored byte-for-byte:
  - `flight_alert.xml`:
    `46e4efedb09a7871cc6f2694d8ae54a00aabc54e47e360682fc518324eb451af`
  - `android.app.ActivityThread.IDS.xml`:
    `dec264c49b5f4f8b1526a8d84d803fc55102be8b02c3b4a7f0ee6ac973ca9ece`
- Only those original two shared-preference files remain.
- Shell mock-location authorization is restored to deny.
- Flight Alert mock-location state remains untouched at no-operation/default
  deny.
- The temporary HWUI property is empty again.
- No screenrecord process, device MP4, harness JAR, test XML, or private backup
  remains.
- Location remains enabled, thermal status is 0, and Nova Launcher is the
  top-resumed activity.

No app source, commit, remote, or branch was modified or pushed by Task 2.

## Targeted Clean z3.5 Reproduction

A final z3.5-only reproduction was recorded without the continuous `gfxinfo`
collector to resolve the earlier loose-mask ambiguity. The real Fold, Morocco
location, exact `3.5` prelaunch/prerun readbacks, exact APK/harness hashes, and
all 16 ordered action markers were retained.

Video:

`C:\Users\h\OneDrive\Documents\Flight Alert\artifact-work\readable-rebase\google-polish\final-22adbdd\z3.5-z3-5-clean.mp4`

SHA-256:

`9D1C3FB1EBC73B921E8D99AA77B97A147180D98253FD0F029C9AC25AB2DF51BD`

The focused audit decoded the full action windows and used two independent
masks:

1. A strict renderer-paint mask required a near-white/pale-blue core, the
   renderer's channel ordering, and an immediately adjacent dark blue-black
   halo. The minimum brightness gate excludes the brown terrain previously
   mistaken for reference paint.
2. The prior loose bright/cyan/pale-green edge mask was retained unchanged as
   a control.

Satellite features were aligned from each pre-action baseline with
Shi-Tomasi features, pyramidal LK flow, and a RANSAC partial-affine transform.
The first repeatable scale change above a `0.0025` threshold occurred at
`81.367 ms` in `pinch_in_1` and `63.933 ms` in `pinch_in_2`; pre-action scale
noise p99 was below `0.0000024`.

Before those first measurable zoom frames:

| Cohort | pinch_in_1 minimum count / aligned retention | pinch_in_2 minimum count / aligned retention | Persistent loss |
|---|---:|---:|---:|
| Strict reference paint | 99.1% / 98.7% | 90.5% / 93.7% | No |
| Glyph-like paint | 100.0% / 98.2% | 101.6% / 100.0% | No |
| Boundary-line paint | 88.3% / 95.6% | 59.1% / 68.8% | No |

The boundary classifier joins only nearby fragments already admitted by the
strict paint gate, which is necessary because a z3.5 country-border core is
sub-pixel after video encoding. The detected border remained one
basemap-aligned component in both pinch-ins. The lower one-frame boundary
coverage in `pinch_in_2` did not remove the component and did not persist for
the required three consecutive frames.

The earlier loose-mask collapse did not reproduce. Its largest adjacent drops
were only `1.997%` and `1.987%`. Pixels lost at those control-mask transitions
were brown satellite raster (mean BGR approximately `183/182/166` and
`181/180/161`), with only `3.7%` and `3.3%` overlap with strict reference
paint.

Decision: the clean exact-z3.5 capture does not show persistent label or border
cohort loss before map zoom begins. The conflicting earlier loose-mask event
was a raster-tile/color-threshold artifact, not a reference-overlay blank.

Reproducible audit:

- `z3_strict_reference_audit.py`
- `z3.5-z3-5-clean-strict-reference-audit.json`
- `z3.5-z3-5-clean-cleanup-evidence.txt`

The device was restored again after this targeted run: original preference
hashes and mode `600`, mock-location deny state, enabled location, thermal
status 0, no app/screenrecord process, no test providers or temporary device
files, and Nova Launcher top-resumed.
