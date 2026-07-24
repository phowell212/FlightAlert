# Continuous Reference Scene Window

## Goal

Keep borders and labels attached, complete, and immediately available through
meaningful pan and multi-band zoom motion without moving a continuing label or
adding interaction-time I/O, parsing, measurement, or collision planning.

## Measured Causes

The renderer previously replaced a padded immutable label scene after every
settled pan, even while the old scene still covered the viewport. That produced
the measured late post-release scene change.

The old 20 percent / 512 px padding was shorter than the accepted 660 px Fold
pan. At z0 through z3 the 88-tile estimate was already exceeded by the unpadded
z4 world data, so it incorrectly reduced padding to zero. The first pan pixel
then left the retained scene.

Borders had a separate limit: only one offscreen raster-cell ring was prepared.
At z3 through z4 a 660 px pan crosses several cells, while raster production is
intentionally suspended during interaction.

Zoom had a different failure. Gesture entry cancelled the unfinished target
planner, and the interaction path deliberately avoided tile enumeration. A
scene could therefore remain geometrically large enough to draw while its
useful labels became sparse before the exact successor was ready.

The first zoom-ahead implementation prepared the needed scenes, but an
interrupted 220 ms fade restarted from the last formally completed scene. A
second handoff arriving at 218 ms could reset a nearly complete `z7 -> z8`
transition to `z7 -> z9`, even though z8 through z11 were already resident.

After preserving the correct predecessor, new label memberships still became
readable late in the smoothstep fade. Compressing that fade improved density
but made hierarchy arrivals visibly stronger. The selected full-duration curve
makes entering labels readable early and slows as they become opaque.

## Selected Design

### Stable active labels

- Keep drawing the active target scene while it covers the viewport at the same
  zoom and exact target LOD.
- Use 35 percent of the Fold's long side as desired overscan, capped at 1,024 px.
- Above z4, retain the 8,192 px dimension limit and use at most 160 target
  tiles. This is the smallest cap that covers the accepted 660 px Fold pan at
  an integer 256 px tile scale and remains below the 256-entry memory cache.
- For z0 through z4 using source z4, use the smaller of desired overscan and half
  a wrapped world: 128, 256, 512, 811, and 811 px.
- A zoom, LOD, options, dimensions, or package-generation change still requires
  an exact scene.

### Staged replenishment

- Once more than half an active scene's padding is consumed, prepare one exact
  successor on the existing background label executor.
- Store the completed successor in the bounded retained-scene history. Do not
  replace or fade the active scene while it still covers.
- Promote an exact historical successor only after the active scene no longer
  covers. Returning to a prior area promotes the previous scene from the same
  bounded history.
- Preferred occurrence IDs keep the same candidates during successor planning.

### Zoom-ahead presentation window

- While settled, prepare four presentation scenes at one-zoom intervals using
  the current parsed source LOD. At most two missing tiles are requested per
  draw, and all collision planning remains on the existing background label
  executor.
- Keep the active scene and four future scenes in a five-entry bounded history.
  A future scene can be promoted before its nominal zoom only after it covers
  the viewport and contains visible content there.
- Cancel only the zoom-ahead desire set when interaction begins. Complete
  resident scenes remain available; no database read, parse, or label plan is
  started by the gesture path.
- Exact post-gesture source LOD still replaces the presentation scene after it
  is complete. The presentation window is continuity coverage, not a permanent
  substitute for source detail.

### Atomic handoffs

- Borders use complete resident raster scenes, a lower safety scene, and a
  220 ms underlay fade. An incomplete band never replaces the visible scene.
- Labels also switch only between complete retained frames.
- If a third frame interrupts `A -> B`, carry whichever of A or B is currently
  dominant and preserve its exact opacity in the new fade. Reversing to A
  reverses the existing fade instead of snapping A fully opaque.
- An empty or padding-only frame cannot become the visible label scene. A
  precomputed 128 by 128 occupancy bitset answers viewport-content checks
  without scanning labels during drawing.

### No label relocation

A shared occurrence is considered visually continuous only when its transformed
screen anchor agrees within 0.5 px. A candidate that reused an ID at a different
placement is treated as leaving and entering, so it cross-fades instead of
snapping to a new location.

Continuing labels remain fully opaque during a scene fade. Leaving memberships
use the original smoothstep across the full 220 ms. Entering memberships use a
full-duration cubic ease-out, so their strongest alpha change happens before
the label becomes visually dominant rather than at the end of the handoff.

### Border coverage

While idle, prepare enough raster rings to cover the retained label overscan:
`ceil(retained padding / raster-cell screen size)`. Interaction still enumerates
one ring, performs no raster production, and reuses the settled relevance set
instead of cloning the larger working set each frame.

Country borders below z4 use z4 source data, matching the label policy. The
resident lower raster scene remains available throughout reverse zoom; an
experiment that switched to it early was rejected because it did not remove
the measured composition stalls and could soften boundaries.

### Settled fast path

When the active scene covers, its successor is already prepared or unnecessary,
and the fallback LOD is ready, draw it before target-tile enumeration. Low zoom
therefore does not repeatedly scan hundreds of wrapped z4 draw references on
ordinary idle redraws.

## Hot-Path Boundary

No database read, tile request, executor submission, label measurement,
collision plan, or new collection was added to a normal gesture frame.
Promotion performs only bounded scene-history work. Fade progression is scalar
arithmetic. Larger label and raster windows are populated while idle on
existing background executors.

## Acceptance

- Pure tests cover same-scale reuse, half-padding staging, bounded history
  promotion, transformed-anchor continuity, low-zoom padding, and idle raster
  rings.
- The injected reference suite, debug build, and lint pass.
- Real-phone video starts at z4 over Morocco, crosses approximately z4 through
  z10 and back with centered and off-center pinch/reversal gestures.
- The accepted centered run has no frame below 10 percent of its pre-motion
  reference density. Its floor is 14.38 percent and its only sub-25-percent
  interval is about 92 ms. The pre-window baseline reached zero and spent 79
  frames below 10 percent.
- The accepted off-center run has no doubled-label cohort, loaded-rectangle
  edge, or measured anchor jump. Map-aligned glyph motion beats screen-static
  motion in 96 percent of the final zoom-in samples.
- The z3 world-pan run has zero blank grid cells and map-aligned reference
  motion in every measured pair. The dense z7.5 pan run also has zero blank
  cells and no frame gap above 33 ms after the 150 ms gesture-injection startup.
- Background work returns to quiescence after the final action, and the videos
  contain no app crash, ANR, or out-of-memory event.
