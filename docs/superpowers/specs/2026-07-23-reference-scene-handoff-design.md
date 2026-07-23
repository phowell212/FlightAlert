# Reference Scene Handoff Design

## Goal

Keep geographically correct borders and labels visible through pan and zoom while
the exact destination scene loads. Labels may enter or leave through the existing
fade, but an individual label must remain attached to its geographic anchor and
must not jump between screen locations during a gesture.

## Cause

The renderer currently makes continuity and preparation mutually exclusive. When
a retained scene can cover an active gesture, it clears the desired dictionary
tiles and suspends boundary-raster production. That invalidates useful padded
work, discards unfinished raster cells, and makes the destination scene start
catching up only after the gesture.

## Options

1. **Retained handoff using the existing caches and lower-LOD scene — selected.**
   Preserve work started before interaction, prepare the existing lower-LOD label
   scene while idle, transform the displayed scene during motion, and publish only
   complete scenes.
2. **Continuous predictive rendering during gestures.** This can follow long
   flings more closely, but dictionary decode, raster allocation, and label
   planning can contend with frame production. It is not justified while the
   existing retained machinery can provide continuity.
3. **A new GPU/vector-tile renderer.** This duplicates working infrastructure and
   is out of scope unless profiling later proves Canvas submission is the limiting
   stage.

## Design

- Entering the retained interaction path must not clear the current desired tile
  set or advance its generation. Already queued padded and fallback tile work may
  finish, but no new dictionary decode or label plan is started per frame.
- Suspending boundary production must retain keys that were already relevant.
  Their workers may finish and publish; uncached keys newly exposed by the gesture
  remain unscheduled until idle.
- Once an exact target label scene is displayed and its lower-LOD tiles are ready,
  use the existing label executor to prepare the lower-LOD retained scene in the
  background. This scene provides a wider geographically correct cover for a long
  pan or LOD transition.
- Scene selection remains target first, then lower LOD, then retained history.
  Existing occurrence identities and transition logic keep continuing labels
  anchored; changed membership fades instead of relocating.
- Publication remains atomic and is rejected when viewport options, obstacles,
  package generation, or scene generation no longer match.
- No file I/O, inflate, parsing, path construction, text measurement, collision
  planning, or new per-frame collection is added to the gesture draw path.

The low-zoom z0–z3 policy has no lower source LOD because it intentionally uses z4
data. Its 256-entry low-zoom cache can hold the complete z4 tile set, so preserving
the existing desired work is the appropriate fallback there.

## Failure Escalation

The first implementation keeps the current one-cell boundary overscan. If a real
long-pan video still exposes an uncached border strip, add one idle-prepared parent
boundary band through the existing raster cache and history. Do not start raster
allocation during gesture motion.

## Acceptance

- Focused tests prove interaction preserves desired tile work, preserves unfinished
  relevant boundary work, and schedules an idle lower-LOD label cover only after
  the exact scene is available.
- The injected reference suite, debug build, and lint pass.
- Real-phone videos cover dense land at z2.0, z5.8 with a pan longer than retained
  padding, z7.5, and z10.5.
- Videos include pan, pinch, reversal, and settled tails. They show no blank
  reference strip, border loss, label relocation, post-gesture wholesale swap, or
  material frame-pacing regression.
- Screenshots and settings screens are not acceptance evidence.
