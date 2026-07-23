# Google-Style Label Continuity Design

## Goal

Keep a still-valid label occurrence stable across a fresh retained-frame layout without reducing semantic priority, preserving stale geometry, or adding work to draw and gesture hot paths.

## Evidence

The captured Google Maps and Google Earth Morocco pan/zoom videos show the same behavior:

- borders and persistent labels remain attached to geography;
- label membership changes quickly instead of using a long scene-wide fade;
- coarse and detailed labels overlap during hierarchy changes, so the viewport never empties;
- labels do not rearrange after motion settles.

Flight Alert already has retained padded frames, stable occurrence identities, z4 fallback below z4, and selective 220 ms membership fades. The remaining gap is that each background label layout starts from the deterministic semantic ordering with no knowledge of the labels that were just visible. Two colliding, equally important occurrences can therefore exchange places after a small camera change even when the previous occurrence is still valid.

## Options Considered

### Prefer still-valid prior occurrences

Pass the previously displayed occurrence identities into retained-frame planning. Within equal effective semantic priority, examine a previous occurrence before a new occurrence. All current containment, budget, water-repeat, chrome-avoidance, and collision checks still apply.

This is the selected approach. It addresses the observed stability gap, runs only during background planning, and uses the stable identity already present in the database and renderer.

### Crossfade old and new boundary raster bands

This could mask a border-style change after a zoom-band promotion, but the Google evidence shows fast hierarchy changes rather than long crossfades. Flight Alert's verified reference videos did not show a reference-border discontinuity that justifies adding another draw-time blend.

### Replan retained coverage during a gesture

This could extend coverage for multi-screen pans, but it adds scheduling and publication work while interacting. The current renderer already passed meaningful pan tests without blank reference sections. It should be reconsidered only if a targeted sustained-pan video demonstrates a real coverage failure.

## Design

`ReferenceLabelLayoutSelector.select` receives a defaulted `preferredOccurrences` set. Its current comparator remains byte-for-byte equivalent when the set is empty. When the set is nonempty, ordering remains:

1. effective semantic priority;
2. previous occurrence before new occurrence;
3. feature ID;
4. candidate ID;
5. repeat ordinal;
6. rendered world copy.

Because preference follows semantic priority, a lower-priority previous label can never displace a more important label. Preference only changes examination order; a previous occurrence must still be fully contained and pass every current admission rule.

The renderer captures the immutable label list from the displayed compatible frame only after the retained coordinator accepts a new request. The retained-label worker converts that list into full `ReferenceLabelOccurrenceId` values and passes the set through retained-frame planning. Direct UI planning uses the default empty set, so neither ordinary drawing nor gesture rendering gains a set traversal, lookup, or new sort branch.

The full identity triple is required. Candidate ID alone would incorrectly favor every repeated line label or adjacent rendered world copy. Prior geometry is never placed into `fixedCandidates`, and prior anchors or placement ranks are never reused; current geometry and current validity stay authoritative.

## Acceptance

- An equal-priority, colliding prior occurrence remains selected while valid.
- A stronger current occurrence still displaces a weaker prior occurrence.
- Empty preference preserves existing deterministic selection.
- Injected reference tests, debug assembly, and lint pass.
- Real-phone Morocco z7-z9 and z10-z12 slow-pan/pinch video shows no post-settle label swap, blank reference section, border loss, or new visible stall.
- Frame timing is reported for the action intervals; screenshots are not used as temporal proof.
