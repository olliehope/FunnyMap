# Scanner

The live scanner recognises rooms using only block states already present in
the normal client world. It does not inspect packets, force-load chunks, query
server-only data, or treat missing chunks as air.

## Activation and Session Safety

`RoomScannerService` runs only while Catacombs detection is positive. A client
world/session generation owns every queue item, observation, cache entry, match,
and published snapshot. World changes and disconnects clear work immediately.
The snapshot exchange independently rejects stale sessions and revisions.

## Loaded-Chunk Discovery

Fabric chunk-load events enqueue work and chunk-unload events cancel dependent
observations, matches, cache entries, and visible rooms. A 40-tick fallback
checks already-loaded chunks within six chunks of the player.

Availability is established only with:

```text
level.chunkSource.getChunk(chunkX, chunkZ, ChunkStatus.FULL, false)
```

The final `false` prevents creation or force-loading. `ClientLevel.hasChunk` is
not used because its Minecraft 26.1.2 implementation does not prove loaded
client data.

## Structural Anchors

At database load, `StructuralAnchorIndex` builds position-independent references
from precomputed room/fingerprint/rotation views. Signatures occurring in more
than 64 views are excluded from discovery, and one observed block considers at
most 64 references. Empty chunk sections and palettes with no indexed anchor
block are skipped.

An observed anchor proposes a world origin by subtracting its room-local
position. Four distinct local positions must vote for the same candidate view
and origin before room observation begins. This is candidate discovery, not a
match; the full matcher still verifies every accepted result.

## Incremental Observation

Candidate bounds are partitioned by chunk. Each tick permits at most 4,096 room
block reads. While discovery remains queued, half of that budget is reserved for
candidate observations and the rest for anchor work. Candidate volumes above
524,288 blocks are rejected.

Each region reacquires its chunk through the no-create lookup. A missing chunk
becomes `CHUNK_UNAVAILABLE`. If a read fails after partial staging, all samples
from that region are discarded and it becomes `CLIENT_READ_FAILED`. Available
states are immediately reduced to room-local coordinates, registry-id strings,
and policy-selected properties. No Minecraft object crosses into the common
matcher.

## Candidate Matching

The inverted token index returns at most 64 shape/bounds-compatible views.
`RoomMatcher` explicitly verifies each shortlist entry. Defaults are:

- minimum 8 observed policy-eligible samples;
- minimum 8 matched samples;
- minimum observation coverage 0.50;
- minimum available definition coverage 0.60;
- minimum observed-to-definition coverage 0.70;
- minimum definition-to-observed coverage 0.85;
- minimum total definition coverage 0.55;
- maximum conflict ratio 0.15;
- minimum score 0.72;
- minimum different-room winner margin 0.08.

The score weights observed precision 0.35, available-definition recall 0.35,
total-definition coverage 0.20, and conflict cleanliness 0.10. All four legal
rotations are considered in memory. Different rotations or variants of the same
identity do not create a false identity tie. Equal accepted symmetric rotations
produce known identity with unknown orientation.

## Cache and Revalidation

Cache keys include session generation, database content identity, fingerprint
policy, observation revision, and chunk dependency revisions. Complete
observations may use a SHA-256 structural content key; partial observations are
revision-exact. A digest is never match proof.

Successful rooms are re-observed after 200 ticks and failed rooms after 100.
This bounded fallback detects in-place block changes because the selected
Fabric API exposes chunk lifecycle events but no general client block-change
event. Changed complete content cannot reuse the prior content key.

## Logical Grid Publication

Only structurally verified room origins establish a logical lattice. The
replaceable `CatacombsLatticeProfile` currently uses a 32-block room spacing,
based on agreement across audited Catacombs map architectures. It is a
documented provisional compatibility assumption, not permission to read data.
Exact divisibility is required; off-lattice proposals are omitted. No absolute
world X/Z origin or fixed Y scan range is hardcoded.

Unknown proposals become visible only after a verified room anchors the grid.
No connection, door, completion state, or player marker is inferred by the
scanner in Milestone 4.

## Debugging

Set `FUNNYMAP_DEBUG_HUD=true` to inspect lifecycle, logical cell, coverage,
shortlist size, candidates, score, margin, rotation, typed failure, queue totals,
and cache state. Debug data is an immutable part of the published snapshot; the
renderer never reads live scanner state.

Development builds also provide `/fmapdev scanner`, `/fmapdev room`, and
`/fmapdev debugcopy`. The last command writes a compact issue-ready report with
build, floor, session, database, evidence, cache, and counter fields but no
UUID, token, chat, or credential data. The `funnymap/scanner` logger emits
structured session, database, discovery, observation, match, cache, and
invalidation events without per-block logging.

With an empty database, the scanner still tracks already delivered client
chunks and reports an explicit `EMPTY_DATABASE` lifecycle. It cannot discover
structural room origins without trusted definition anchors and does not
fabricate them. Developer capture remains available as the corpus bootstrap.
