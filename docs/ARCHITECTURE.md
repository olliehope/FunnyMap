# Architecture

FunnyMap separates live Minecraft access from deterministic dungeon logic. The
dependency direction is intentionally one-way:

```text
Minecraft client adapters -> observations -> matcher -> dungeon snapshots
Room JSON ---------------------> database -----^             |
                                                              v
                                                     pure map layout -> HUD
```

## Detection

`DungeonDetector` consumes immutable connection and scoreboard signals. The
Minecraft adapter reads only public client state and treats absent evidence as
`Unknown`. Detection does not inspect chunks, identify rooms, or render the map.
The live scanner runs only when Catacombs detection is positive.

## Dungeon Model

`DungeonGrid` is a Minecraft-independent logical topology. It owns typed room
cells, explicit connection cells, multi-cell footprints, room recognition,
orientation, and completion values. It supports 1x1, 1x2, 1x3, 1x4, 2x2, and
three-cell L footprints. Adjacency never invents a door.

## Observation Boundary

Client adapters are the only code allowed to retain Minecraft types while
reading live state. World, chunk, entity, and block-state reads occur on the
client thread. A no-create chunk lookup must prove availability before any room
read. The adapter immediately converts registry identifiers, allow-listed
properties, coordinates, coverage, provenance, session generation, and
revision into an immutable `RoomObservation`.

Common normalization, hashing, rotation, indexing, comparison, matching, and
rendering packages contain no Minecraft or Fabric imports. Missing chunks are
unavailable coverage rather than air.

## Room Database

`RoomDatabase` loads versioned JSON from
`assets/funnymap/rooms.json`. Definitions contain stable ids, metadata,
canonical footprints, and one or more genuine structural variants. Fingerprints
are stored at canonical rotation zero; four runtime views are precomputed in
memory. The strict loader validates identifiers, coordinates, properties,
digests, sample floors, duplicates, and detectable rotation-equivalent variants.

The bundled corpus is independently captured. Raw captures and review reports
are not runtime resources. `validateRoomDatabase` invokes the production loader
headlessly during local builds and CI.

## Matcher

`RoomCandidateIndex` narrows partial observations using structural tokens.
`RoomMatcher` then explicitly verifies shape-compatible candidates and legal
rotations. It compares observed-to-definition and definition-to-observed
coverage, counts conflicts and unavailable expected samples, applies absolute
evidence and score thresholds, and requires a margin over another room
identity. A symmetric room may have known identity and unknown orientation.

SHA-256 is used for integrity and complete-observation cache identity, never as
sole match proof. Typed failures distinguish unavailable chunks, insufficient
evidence, no match, ambiguity, and unsupported shape.

## Scanner

`RoomScannerService` is the Minecraft-side scheduler. Chunk events and a bounded
fallback queue drive position-independent structural anchor discovery. Candidate
rooms are observed incrementally within a per-tick block-read budget and passed
to the common matcher as immutable values. Session, database, policy, content,
and chunk dependency keys guard caches and stale results.

Verified world origins are projected through the replaceable
`CatacombsLatticeProfile`. The profile does not grant access to blocks and an
off-lattice proposal is omitted. See [SCANNER.md](SCANNER.md) for the algorithm
and current thresholds.

## Snapshot Exchange

`DungeonSnapshotAssembler` converts located match results into a complete
immutable `DungeonSnapshot`. `DungeonSnapshotExchange` publishes snapshots
atomically and rejects old sessions or non-increasing revisions. Scanner debug
data is copied into the snapshot; consumers do not read scanner internals.

## Renderer

`DungeonMapLayoutEngine` is a pure projection from a snapshot into ordered
rectangles and text commands. It contains no detection or world logic. The
client-only painter translates those commands into the Minecraft 26.1.2 HUD
API. This permits headless geometry, labeling, and debug-layout tests.

## Related Documents

- [Development](DEVELOPMENT.md)
- [Room Data](ROOM_DATA.md)
- [Scanner](SCANNER.md)
- [Migration and Design](MIGRATION_DESIGN.md), retained as detailed design
  history and milestone rationale
