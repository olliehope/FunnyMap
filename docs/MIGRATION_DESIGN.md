# FunnyMap Migration and Design

> This document records the original migration decisions and milestone detail.
> Current contributor-facing architecture, scanner, development, and room-data
> rules live in `ARCHITECTURE.md`, `SCANNER.md`, `DEVELOPMENT.md`, and
> `ROOM_DATA.md`. When wording differs, those focused documents describe the
> current repository workflow.

## Scope

This project is a clean native Fabric implementation for Minecraft Java 26.1.2.
Reference projects were studied for architecture only. Their source and room
databases are not copied into this CC0 project.

The hard boundary is client knowledge: the mod may consume server metadata,
scoreboards, map items, entities, and block states already delivered to the
normal client. It must never force-load chunks, probe unavailable world data,
spoof packets, automate play, or replace missing evidence with guesses.

## Reference audit

The workspace began as a Fabric template, so the requested legacy files were
audited upstream at pinned revisions:

- [FunnyMap `DungeonScan`](https://github.com/Harry282/FunnyMap/blob/ba49c64ae8274e92b8b9e9d4dc60576fece5be4b/src/main/kotlin/funnymap/features/dungeon/DungeonScan.kt),
  [`ScanUtils`](https://github.com/Harry282/FunnyMap/blob/ba49c64ae8274e92b8b9e9d4dc60576fece5be4b/src/main/kotlin/funnymap/features/dungeon/ScanUtils.kt),
  [`RoomData`](https://github.com/Harry282/FunnyMap/blob/ba49c64ae8274e92b8b9e9d4dc60576fece5be4b/src/main/kotlin/funnymap/core/RoomData.kt), and
  [`rooms.json`](https://github.com/Harry282/FunnyMap/blob/ba49c64ae8274e92b8b9e9d4dc60576fece5be4b/src/main/resources/assets/funnymap/rooms.json).
- [BetterMap `DungeonMap`](https://github.com/BetterMap/BetterMap/blob/b4b24512f99cbe3e8febfc2e30e6d7f793b4663f/Components/DungeonMap.js),
  [`Room`](https://github.com/BetterMap/BetterMap/blob/b4b24512f99cbe3e8febfc2e30e6d7f793b4663f/Components/Room.js), and
  [`DungeonRoomData`](https://github.com/BetterMap/BetterMap/blob/b4b24512f99cbe3e8febfc2e30e6d7f793b4663f/Data/DungeonRoomData.js).
- [CryptKit `RoomMatch`](https://github.com/Froggy-Lord/cryptkit/blob/34ec9945aafb75095e3ce7a9a7651aa06c44a1c2/src/main/java/dev/froggylord/cryptkit/core/RoomMatch.java),
  [`RoomTransform`](https://github.com/Froggy-Lord/cryptkit/blob/34ec9945aafb75095e3ce7a9a7651aa06c44a1c2/src/main/java/dev/froggylord/cryptkit/core/RoomTransform.java), and
  [`RoomMatchTest`](https://github.com/Froggy-Lord/cryptkit/blob/34ec9945aafb75095e3ce7a9a7651aa06c44a1c2/src/test/java/dev/froggylord/cryptkit/core/RoomMatchTest.java).
- Modern cross-checks: [Skyblocker](https://github.com/SkyblockerMod/Skyblocker/tree/e364eb8f7cfe/src/main/java/de/hysky/skyblocker/skyblock/dungeon/secrets)
  and [OdinFabric WorldScan](https://github.com/odtheking/OdinFabric/blob/38ddc1b5dacd/src/main/kotlin/com/odtheking/odin/features/impl/dungeon/map/WorldScan.kt).

Useful concepts are the logical room/connection lattice, loaded-only incremental
scanning, immutable room metadata, flood-filled multi-cell ownership, cached
positive matches, room-local transforms, and a renderer isolated from detection.

BetterMap adds explicit shape/secret/crypt metadata and uses a stable roof-corner
marker for orientation; the metadata model is useful, but one marker should be
only one piece of orientation evidence. CryptKit evaluates rotated room-local
transforms and verifies both observed-to-definition and definition-to-observed
coverage. The new matcher will retain that bidirectional idea while rejecting
ties explicitly and proving chunk availability for every sampled position.

Legacy assumptions that are intentionally rejected include numeric block IDs,
fixed world origins and Y levels, a single vertical sample, 32-bit string hashes,
silent first-match collision handling, synchronous whole-dungeon scans, and
hardcoded fallback orientations.

FunnyMap is AGPL-3.0, CryptKit is GPL-3.0-only, Skyblocker is LGPL-3.0,
and OdinFabric is BSD-3-Clause. BetterMap does not publish a license in the
audited revision. Any future import of their code or data requires a separate
license decision; independently captured room data is the default plan.

## Target architecture

Dependencies point inward from Minecraft adapters and HUD code to plain models:

```text
Minecraft client adapters -> detector/scanner -> immutable dungeon model
                                                    |
Room JSON -> database -> matcher -------------------+
                                                    |
                                                    v
                                               map renderer
```

- `DungeonDetector` classifies explicit connection and sidebar signals. It does
  not inspect blocks or render anything.
- `DungeonGrid` owns logical cells, occupied-cell sets, and typed edges.
  Shape is derived from normalized occupied cells and supports 1x1, 1x2, 1x3,
  1x4, 2x2, and L without merging rooms merely because names match.
- `RoomDatabase` loads and validates versioned resource JSON into immutable
  `RoomDefinition` values and constructs its inverted candidate index once at
  load time.
- `RoomScanner` snapshots only proven-available client chunks, spends a fixed
  budget per tick, reacts to chunk lifecycle events, and caches verified
  results.
- `RoomMatcher` and orientation transforms are pure and headlessly tested.
- `DungeonMapRenderer` consumes snapshots only. It has no server,
  scoreboard, chunk, scanner, or matcher access.

## Client-thread observation boundary

All Minecraft object access belongs in client adapters. Reads of `Minecraft`,
`ClientLevel`, chunks, entities, block entities, and block states happen on the
client thread and are copied immediately into immutable values. The boundary
type for room structure is `RoomObservation`: room-local sample values, explicit
coverage, unavailable regions, the candidate footprint, and capture provenance.

Past that boundary, fingerprint normalization, property filtering, rotations,
hashing, candidate indexing, verification, scoring, and winner selection are
pure operations. They accept immutable observations and database definitions;
they cannot import or retain live Minecraft objects. In particular,
`RoomMatcher` has no access to `Minecraft`, `ClientLevel`, chunks, block
entities, or registries. Registry identifiers and canonical property strings
are resolved before the snapshot is published.

This permits headless tests and optional background matching. Snapshot creation
itself remains on the client thread. A background result is accepted only if
its world/session generation and observation revision still match the current
client-thread state.

## Room capture tool

`RoomCaptureTool` is a first-class developer workflow, not a side effect of
successful recognition. It can capture a loaded room whose identity is wholly
unknown to the matcher. The developer supplies or edits the stable metadata:
room id/name, type, occupied-cell footprint/shape, secrets, crypts, canonical
origin, and the observed rotation needed to transform the capture back to 0
degrees. The tool never guesses missing metadata or orientation.

A capture session performs the same no-create chunk checks as the runtime
scanner, snapshots available structural block states on the client thread, and
writes a raw capture artifact. Coverage and unavailable chunks are recorded in
the artifact. An incomplete capture stays useful for comparison but cannot be
mislabelled as complete.

Repeated captures are grouped by room id and game/data version. The comparison
tool aligns every capture into canonical room coordinates and reports:

- stable block identifiers and stable allow-listed properties;
- positions whose block identifier changed;
- individual properties that changed while the block stayed stable;
- presence/absence changes and positions missing because capture coverage was
  unavailable;
- samples seen too few times to be considered stable.

Finalization keeps only samples supported by the configured minimum number and
coverage of captures. A varying property can be removed without discarding an
otherwise stable block sample. Both the machine-readable difference report and
the exclusions are retained as provenance. Developers may create separate
variants for real structural or Minecraft-version differences, but not for
rotations.

The raw capture schema and final database schema are versioned separately.
`RoomCaptureTool export` produces a candidate room entry accepted by
`RoomDatabase`; `compare/finalize` replaces its draft samples with the stable
canonical sample set and records the contributing capture ids. Capture files
remain outside the production database so later comparisons can be repeated
without information loss.

Each raw artifact includes a capture id, game/data version, fingerprint-policy
version, developer notes, canonical metadata, observed transform, immutable
sample values, an availability mask, and a SHA-256 integrity digest. Comparison
is explicit: draft exports cannot silently become production definitions, and
the finalizer emits the accepted samples, exclusions, and source capture ids for
human review before a database resource is updated.

The implemented tool is available by default only in development builds. Set
`FUNNYMAP_DEV_TOOLS=false` or JVM property `funnymap.devTools=false` to disable
it there. Release builds never register these client-side commands:

```text
/fmapdev capture <roomId> <displayName> <type> <footprint> <secrets> <crypts> <rotation> <minX> <minY> <minZ> <maxX> <maxY> <maxZ> [notes]
/fmapdev captures [roomId]
/fmapdev compare <roomId>
/fmapdev finalize <roomId> <fingerprintId> [minCaptures minCoveragePercent minStableSamples minPositionObservations]
/fmapdev export <roomId>
```

Footprints use canonical `column,row` cells separated by semicolons, for example
`"0,0;1,0;0,1"`. Capture bounds are explicit inclusive world coordinates; no
dungeon origin, room size, or Y range is guessed. Artifacts are written under
`funnymap-room-captures/` in the game directory: versioned raw JSON in `raw/`,
paired human-readable and machine-readable comparison/finalization reports in
`reports/`, and review-ready candidate database JSON in `exports/`. Export is a
separate command after finalization, so finalization never modifies bundled
production resources.

## Fingerprint format

The implemented database stores metadata and genuine fingerprint variants per
room. Each fingerprint has explicit inclusive bounds and a policy version:

```json
{
  "schemaVersion": 1,
  "fingerprintPolicy": "structural-blockstates-v1",
  "rooms": [{
    "id": "catacombs/mushroom-1",
    "name": "Mushroom",
    "type": "NORMAL",
    "shape": { "kind": "1x1", "cells": [[0, 0]] },
    "secrets": 1,
    "crypts": 0,
    "fingerprints": [{
      "id": "base",
      "origin": [0, 0, 0],
      "bounds": { "min": [0, 0, 0], "max": [31, 15, 31] },
      "samples": [
        { "pos": [3, 5, 7], "block": "minecraft:stone_bricks", "properties": {} }
      ],
      "digest": "sha256:...",
      "policyVersion": "structural-blockstates-v1"
    }]
  }]
}
```

Samples use room-local coordinates and registry identifiers. Only explicitly
stable, allow-listed properties participate. Entries are sorted and encoded
with lengths before SHA-256 hashing; the digest is an index, while the samples
remain available for verification. Runtime-variable blocks are excluded by a
versioned policy rather than legacy ID lists.

Definitions normally store one canonical 0-degree sample set. Runtime code
transforms the observation through the legal 0, 90, 180, and 270 degree room
rotations, including allow-listed directional properties. Shape symmetry may
deduplicate equivalent work, but rotated copies are never serialized as
fingerprint variants. Multiple variants mean genuine structural or version
differences, and the loader can flag rotation-equivalent duplicates.

Canonical footprint cells are translated so their minimum column and row are
zero. A structural sample uses the same north-west footprint boundary as its
horizontal origin and a declared vertical datum. For a bounded local plane with
inclusive maxima `maxX` and `maxZ`, clockwise transforms are `0: (x,z)`,
`90: (maxZ-z,x)`, `180: (maxX-x,maxZ-z)`, and
`270: (z,maxX-x)`. The transformed footprint and samples are then translated
back to a zero minimum. Width and depth therefore swap at 90/270 degrees, which
also covers non-square and L footprints. Directional property values are
rotated by the same transform. If structurally symmetric rotations remain tied,
the room may be known while its orientation stays Unknown unless independent
stable evidence resolves it.

SHA-256 identifies a normalized sample set, verifies resource integrity, and
keys complete-observation caches. It is not match proof. Partial observations
are expected when some chunks or sample regions are unavailable.

At database load time, an inverted index maps discriminating structural tokens
(canonical position, registry id, and stable properties) to definition/variant
candidates. Rotation views can be precomputed in memory without changing the
JSON. Matching uses available observed tokens to accumulate a shape-compatible
shortlist, then explicitly verifies every shortlisted definition and rotation.

Verification records at least:

- matched, conflicting, and comparable observed sample counts;
- observed-to-definition coverage (precision);
- definition-to-observed coverage over positions that were actually available
  (recall), plus total definition coverage;
- absolute evidence count, evaluated rotation, score, runner-up score, and
  winner margin.

A candidate must pass minimum absolute evidence, both directional coverage
thresholds, total-definition coverage, and the ambiguity margin. A digest hit,
a collision, or a small matching subset is never sufficient. If more than one
candidate remains tied or within the configured margin, no room is accepted.

Matcher failures are typed rather than collapsed to a generic value:

- `CHUNK_UNAVAILABLE`: unavailable coverage prevents the required evidence;
- `INSUFFICIENT_EVIDENCE`: available samples are below acceptance thresholds;
- `NO_DATABASE_MATCH`: enough evidence exists but no candidate verifies;
- `AMBIGUOUS_MATCH`: multiple candidates pass without a safe winner margin;
- `UNSUPPORTED_SHAPE`: the observed footprint is outside supported shapes.

Successful and failed results both carry evidence counts and confidence data.
An opt-in developer HUD may show the reason, coverage, candidates, rotation,
and score margin. Normal rendering displays `Unknown` and does not turn debug
information into a guessed room identity.

For Minecraft 26.1.2, `ClientLevel.hasChunk(x, z)` is not a valid availability
test because its implementation always returns true. A scanner must request
`level.chunkSource.getChunk(cx, cz, ChunkStatus.FULL, false)` and read blocks
only when that no-create lookup returns a chunk. Client chunk-load/unload events
will maintain the work queue and invalidate affected observations.

## Milestones

- Milestone 1: implemented. Fabric setup, conservative Hypixel/SkyBlock/
  Catacombs detection, floor parsing, and a basic status HUD.
- Milestone 2: logical grid, typed room/connection cells, multi-cell footprints,
  immutable dungeon snapshots, and a custom snapshot-only renderer. Implemented.
- Milestone 3: immutable `RoomObservation`, validated room/capture JSON,
  `RoomCaptureTool` capture/compare/finalize workflow, structural fingerprint
  normalization, canonical rotations, and candidate-index construction.
  Implemented.
- Milestone 4: loaded-only incremental runtime scanning, two-way verification,
  typed match results, caching, and unambiguous room names. Implemented.
- Milestone 5: integrate orientation and multi-cell recognition across the
  independently captured room corpus, including symmetric-orientation cases.
- Milestone 6: server-sent/local player positions, observable doors and room
  completion states, plus visual polish.

## Milestone 1 limitations

Detection currently recognizes official `hypixel.net` or `hypixel.io` hosts,
then requires visible sidebar evidence for SkyBlock and Catacombs. The received
server brand is diagnostic evidence only and cannot turn a custom address into
a positive Hypixel match. Server metadata is still not authentication.

The Catacombs parser supports Entrance, F1-F7, and M1-M7. A recognizable
Catacombs line with any new token reports a detected dungeon with floor
`Unknown`. Sidebar wording changes may require parser updates. The detector does
not infer rooms, doors, completion state, players, fingerprints, or orientation.

The mapped HUD implementation follows the official Fabric 26.1.2
[HUD rendering](https://github.com/FabricMC/fabric-docs/blob/main/versions/26.1.2/develop/rendering/hud.md)
and [GUI graphics](https://github.com/FabricMC/fabric-docs/blob/main/versions/26.1.2/develop/rendering/gui-graphics.md)
APIs: `HudElementRegistry`, `GuiGraphicsExtractor`, and explicit ARGB colors.

## Milestone 2 limitations

The renderer still never reads blocks, chunks, entities, map items, room
databases, or scanner internals. Milestone 4 now publishes recognised room
snapshots through the same exchange used by the headless tests. The renderer
can draw supported room footprints, names, orientations, typed/open doors, and
completion marks when a producer supplies them. Observable door/completion
producers and player positions remain later-milestone data.

The implemented common layer contains no Fabric or Minecraft imports. A
`DungeonSnapshotExchange` atomically publishes whole snapshots and orders them
by both session generation and strictly increasing revision; a world change or
disconnect invalidates outstanding producer tokens. The headless layout engine
projects snapshots into immutable fill/outline/text commands. Only the
client-side painter knows `GuiGraphicsExtractor`.

The custom map is shown only while Catacombs detection is positive. Its empty
snapshot text is intentionally `No room data`. Set environment variable
`FUNNYMAP_DEBUG_HUD=true` or JVM property `funnymap.debugHud=true` to expose a
typed failure reason in the footer.

## Milestone 3 implementation and limitations

The common layer now provides immutable room observations, structural samples,
coverage, canonical transforms, a versioned fingerprint policy, strict room and
raw-capture JSON codecs, SHA-256 integrity identifiers, repeated-capture
comparison, finalization, typed match evidence, and a load-time inverted
candidate index. These packages contain no Minecraft or Fabric references.

The capture-side live block reader is the development-only capture adapter.
Fabric client commands execute on the client thread, and the adapter
additionally checks `Minecraft.isSameThread`. It partitions requested bounds
by chunk,
uses `level.chunkSource.getChunk(cx, cz, ChunkStatus.FULL, false)`, and reads
`LevelChunk.getBlockState` only from a returned chunk. A missing chunk or failed
read becomes explicit unavailable coverage. `ClientLevel.hasChunk` is not used
because its 26.1.2 implementation does not establish availability. Block states
are immediately reduced to registry-id strings and allow-listed property values.

The bundled production database is intentionally empty until independently
captured room definitions have been reviewed. Capture artifacts and candidate
exports remain developer data until a human adds reviewed definitions to the
bundled database; the capture tool never edits production resources itself.

## Milestone 4 implementation and limitations

The client now has a live `RoomScannerService`, active only when Catacombs
detection is positive. Chunk load events enqueue discovery, chunk unload events
cancel dependent work and visible results, and a 40-tick fallback discovers
already-loaded chunks within six chunks of the player. Every availability
check uses `ClientChunkCache.getChunk(cx, cz, ChunkStatus.FULL, false)`. The
scanner never calls `ClientLevel.hasChunk`, never asks for a creating lookup,
and retains no `LevelChunk`, `BlockState`, or `BlockPos` beyond the current
client-thread read.

Discovery is database-driven rather than origin-driven. A position-independent
anchor index finds uncommon structural block/property signatures in loaded
chunk section palettes. Four distinct room-local sample positions must agree
on the same candidate view and world origin before an observation is queued.
This requires no fixed world origin or fixed Y range. A candidate observation
is partitioned by client chunk; missing chunks are explicit unavailable
regions, a failed read discards that whole staged region, and missing data is
never represented as air.

The configured scheduler limits all room block reads to 4,096 per client tick,
reserves half for observation work while discovery is pending, performs at most
two pure matches per tick, and bounds each queue at 512 entries. It rejects
candidate observations larger than 524,288 blocks. Common signatures appearing
in more than 64 rotated candidate views are excluded as anchors, each observed
block considers at most 64 anchor references, successful rooms are revalidated
after 200 ticks, and failed rooms after 100 ticks. Empty sections and section
palettes containing no indexed anchor block are skipped without walking their
4,096 positions.

`RoomMatcher` first takes at most 64 candidates from the inverted index, then
verifies their samples explicitly. The default acceptance configuration is:

- at least 8 observed policy-eligible and 8 matched samples;
- observation coverage at least 0.50;
- available definition coverage at least 0.60;
- observed-to-definition coverage at least 0.70;
- definition-to-observed coverage at least 0.85;
- total definition coverage at least 0.55;
- conflict ratio at most 0.15;
- final score at least 0.72 and a different-room winner margin of 0.08.

The score weights observed precision 0.35, available-definition recall 0.35,
total definition coverage 0.20, and conflict cleanliness 0.10. Candidate
rotations or genuine variants of one room identity do not create a false
different-room tie. If tied accepted rotations are structurally symmetric, the
identity is known and orientation remains `Unknown`. Digest equality never
selects a winner.

The match cache keys every result by session, database content identity,
fingerprint-policy version, observation revision, and dependent chunk
revisions. Complete observations can additionally reuse a SHA-256 content key;
partial observations remain revision-exact. Chunk replacement/unload removes
dependent queued observations, queued matches, cached results, and published
rooms. A world/session change clears all work. No general client block-change
event is exposed by the selected Fabric API, so bounded 100/200-tick direct
observation revalidation detects in-place changes; changed complete content
cannot hit the old content key.

Logical publication uses `CatacombsLatticeProfile`. Its 32-block room spacing
is isolated as a replaceable profile assumption and is used only after a room
origin has been structurally verified; exact divisibility is required and an
off-lattice proposal is omitted. The evidence is agreement among the audited
Catacombs map architectures, not private server state. Because this repository
does not yet bundle an independent 26.1.2 room corpus, the spacing remains a
documented provisional compatibility assumption. No absolute X/Z origin or Y
level is assumed.

Normal users see only the map. The opt-in debug footer exposes lifecycle,
logical cell, coverage, shortlist size, best and runner-up candidate, score,
margin, rotation, typed failure, queue totals, and cache status. Scanner state
is copied into the immutable snapshot; the renderer never reads the scanner.

The bundled database is still empty, so this release does not claim out-of-box
recognition of real Hypixel rooms. A reviewed `/fmapdev export` can be merged
into `assets/funnymap/rooms.json`, rebuilt, and then recognised automatically.
Milestone 5 remains responsible for validating orientation and multi-cell
behaviour across the eventual independent room corpus. Player positions,
doors, and completion state remain Milestone 6.

## Corpus Alpha live-validation phase

Milestone 5 remains blocked while approximately five independently captured
normal 1x1 rooms exercise the Milestone 4 pipeline in live Catacombs. The
development tooling now provides explicit corner marks, a non-writing capture
preview and confirmation step, current-room evidence inspection, sanitized
report export, structured scanner logging, and a strictly validated local
development database overlay. None of these mechanisms changes matcher
thresholds, guesses unavailable geometry, or extends Milestone 5 behavior.

The field procedure and honest zero-room baseline are maintained in
`docs/LIVE_VALIDATION.md`; real outcomes belong in
`room-data/ALPHA_STATUS.md`. Corpus Alpha is not marked validated until actual
fresh-dungeon recognition results exist.
