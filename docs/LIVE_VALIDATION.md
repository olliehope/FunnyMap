# Corpus Alpha Live Validation

This guide is the field procedure for collecting FunnyMap's first independent
room definitions and proving the complete live pipeline on Hypixel Catacombs.
It assumes no prior knowledge of the scanner architecture. Milestone 5 is not
part of this work.

No real room has passed this procedure yet. A room is not validated until it is
recognised automatically in a fresh dungeon after its reviewed definition has
been loaded.

## Command Audit Before Corpus Alpha Changes

The development command surface at the start of this phase was exactly:

```text
/fmapdev status
/fmapdev capture <roomId> <displayName> <type> <footprint> <secrets> <crypts> <rotation> <minX> <minY> <minZ> <maxX> <maxY> <maxZ> [notes]
/fmapdev captures [roomId]
/fmapdev compare <roomId>
/fmapdev finalize <roomId> <fingerprintId> [minCaptures minCoveragePercent minStableSamples minPositionObservations]
/fmapdev export <roomId>
```

There were no `scanner`, `room`, `capturehere`, `captureconfirm`, `debugcopy`,
`reload`, or `corpus` subcommands. The original explicit-bounds `capture`
command remains available and unchanged in meaning.

## Current Command Reference

```text
/fmapdev status
/fmapdev scanner
/fmapdev room
/fmapdev debugcopy
/fmapdev capturemark min|max|show|clear
/fmapdev capturehere <roomId> <displayName> <type> <footprint> <secrets> <crypts> <rotation> [notes]
/fmapdev captureconfirm
/fmapdev capturecancel
/fmapdev capture <roomId> <displayName> <type> <footprint> <secrets> <crypts> <rotation> <minX> <minY> <minZ> <maxX> <maxY> <maxZ> [notes]
/fmapdev captures [roomId]
/fmapdev compare <roomId>
/fmapdev finalize <roomId> <fingerprintId> [minCaptures minCoveragePercent minStableSamples minPositionObservations]
/fmapdev export <roomId>
/fmapdev reload
/fmapdev corpus
```

Quoted arguments are recommended for display names, footprints, and notes.
Room ids and fingerprint ids are single command words.

## Alpha Target

Collect approximately five simple `NORMAL` 1x1 rooms selected manually while
playing. For each room, prefer all of the following:

- at least three independent captures;
- captures from separate dungeon generations;
- complete or strong client-data coverage;
- a stable finalization with at least the default evidence floor;
- human review of the definition and finalization report;
- automatic recognition in a completely fresh dungeon;
- rotation testing only where that rotation is actually encountered.

Do not take names, secrets, crypts, coordinates, fingerprints, or definitions
from another project's corpus. Track only real observations in
[`room-data/ALPHA_STATUS.md`](../room-data/ALPHA_STATUS.md).

## Install The Development Build

1. Install Fabric Loader, Fabric API, and Fabric Language Kotlin versions listed
   in the root README for Minecraft Java 26.1.2.
2. Put the current development jar in the instance's `mods` directory.
3. Development builds enable `/fmapdev` automatically. If it was deliberately
   disabled previously, remove `FUNNYMAP_DEV_TOOLS=false` or
   `-Dfunnymap.devTools=false`.
4. Optionally enable the debug HUD with `FUNNYMAP_DEBUG_HUD=true` or JVM
   property `-Dfunnymap.debugHud=true`.
5. Start Minecraft. Confirm the small `DEV` marker is visible.
6. Run `/fmapdev status`. Confirm the version, commit, build mode, Minecraft
   version, database identity, room and fingerprint counts, policy, and scanner
   lifecycle match the jar being tested.

`/fmapdev` is registered automatically when the build itself is a development
build. Set `FUNNYMAP_DEV_TOOLS=false` or JVM property
`-Dfunnymap.devTools=false` to disable it. A release build cannot expose the
commands through either flag.

## Check The Empty Database First

Enter a Catacombs dungeon before installing any room definition, then run:

```text
/fmapdev status
/fmapdev scanner
/fmapdev room
/fmapdev debugcopy
```

Expected behavior:

- Catacombs detection is positive and the scanner lifecycle is
  `EMPTY_DATABASE`.
- Loaded-chunk and chunk-event counters move as client chunks arrive.
- The status explains that anchor discovery requires a reviewed definition.
- No room name is invented and the map keeps unknown rooms unknown.
- `/fmapdev room` explains why no candidate exists near the player.
- `debugcopy` writes a sanitized report below
  `funnymap-room-captures/reports/`.

An empty database has no trusted structural anchors, so automatic candidate
discovery cannot truthfully locate a room. That is expected. The capture tool
does not require recognition and is the bootstrap path for the first room.

## Capture The First Room

Choose a visually simple normal 1x1 room. Decide its metadata yourself:

- a stable lowercase id such as `catacombs/alpha-one`;
- a display name based on your own naming convention;
- `NORMAL` type and `"0,0"` footprint;
- observed secret and crypt counts;
- a repeatable inclusive block volume containing the structural room data.

The first capture establishes canonical rotation 0. For later encounters,
declare 90, 180, or 270 only when you can relate the observed structure to that
first canonical capture. FunnyMap will not guess shape, bounds, or rotation.

While looking directly at the blocks chosen as the inclusive minimum and
maximum corners, run:

```text
/fmapdev capturemark min
/fmapdev capturemark max
/fmapdev capturemark show
```

The marked minimum must be less than or equal to the maximum on every axis. If
the proposed room volume cannot be targeted reliably, use the original
explicit-bounds `capture` command instead. Keep the same room-local volume and
datum for every repeat capture.

Create a non-writing preview:

```text
/fmapdev capturehere catacombs/alpha-one "Alpha One" NORMAL "0,0" 2 1 0 first-independent-capture
```

Replace the id, name, secret count, crypt count, rotation, and notes with real
values. The preview reports:

- room identity and supplied metadata;
- footprint and observed rotation;
- exact inclusive bounds;
- available chunks versus total chunks;
- observation coverage;
- eligible structural samples;
- policy exclusions;
- unavailable chunks or client read failures.

Nothing is written yet. Read the preview. For incomplete or suspicious
coverage, run `/fmapdev capturecancel`, wait for the required client chunks, and
preview again. When the proposal is correct, run:

```text
/fmapdev captureconfirm
```

Raw artifacts are written under `funnymap-room-captures/raw/`, which is ignored
by Git.

## Repeat, Compare, Finalize

Encounter the same room in later, preferably separate, dungeon generations.
For each encounter:

1. mark the corresponding canonical bounds;
2. supply the actual observed rotation relative to capture one;
3. inspect coverage and sample counts in the preview;
4. confirm only a credible observation.

After at least three good captures:

```text
/fmapdev captures catacombs/alpha-one
/fmapdev compare catacombs/alpha-one
/fmapdev finalize catacombs/alpha-one base
/fmapdev export catacombs/alpha-one
/fmapdev corpus
```

Do not weaken the default finalization or matcher thresholds just to obtain a
pass. Review the comparison and finalization reports under
`funnymap-room-captures/reports/`. Check changed block ids, changed properties,
unavailable positions, exclusions, capture contributors, stable sample count,
policy version, and digest. The exported database-compatible candidate appears
under `funnymap-room-captures/exports/`.

## Fast Development Overlay Test

After human review, a development build can load one full validated database
document from:

```text
funnymap-room-captures/dev-rooms.json
```

An exported single-room candidate is already a full database document. For
multiple experimental rooms, merge their definitions into one `rooms` array.
Do not duplicate an id that exists in the bundled database. Then run:

```text
/fmapdev reload
/fmapdev status
```

Reload is transactional. The overlay uses the production loader and policy;
invalid JSON, invalid fingerprints, policy mismatches, and duplicate ids are
rejected while the prior active database remains in use. This file is
development-only, ignored by Git, and excluded from jars.

## Promote A Reviewed Definition

For the production proof, merge the reviewed room definition into:

```text
src/main/resources/assets/funnymap/rooms.json
```

Then run from the repository root:

```powershell
.\gradlew.bat test validateRoomDatabase build verifyReleaseResources -PbuildMode=development -PbuildCommit=<short-commit>
```

On Linux or macOS use `./gradlew`. Confirm all four stages pass. The production
database remains authoritative for releases; the local overlay never enters a
release jar.

## Fresh-Dungeon Recognition Proof

1. Install the rebuilt development jar.
2. Remove or temporarily rename `dev-rooms.json` when testing the bundled
   production definition.
3. Restart the client, or use `/fmapdev reload` only when testing an overlay.
4. Enter a completely new dungeon without manually capturing the target room.
5. Encounter the room and allow its normal client chunks to load.
6. Confirm its name appears automatically on the custom map.
7. While standing in or near it, run `/fmapdev room`.
8. Confirm the room id and fingerprint, healthy coverage, matched/conflict
   counts, accepted score, safe winner margin, cache state, and expected known
   or unknown orientation.
9. Run `/fmapdev debugcopy` and retain the compact report with the validation
   notes.
10. Mark the result PASS or FAIL in `room-data/ALPHA_STATUS.md`.

The accepted end-to-end path is:

```text
loaded client chunks
  -> structural discovery
  -> immutable RoomObservation
  -> candidate shortlist
  -> RoomMatcher verification
  -> immutable dungeon snapshot
  -> visible room name
```

## Diagnose A Failure

Run `/fmapdev room`, `/fmapdev scanner`, and `/fmapdev debugcopy`. Preserve at
least these fields before changing anything:

- eligible, matched, conflicting, and comparable sample counts;
- observation coverage;
- observed-to-definition coverage;
- available definition recall;
- total definition coverage;
- best and runner-up candidates;
- score, runner-up score, and margin;
- rotation and cache state;
- typed failure reason;
- database identity, room count, and fingerprint policy.

Check `logs/latest.log` for `funnymap/scanner` structured events. They record
session start, database reload, discovery, observation creation, acceptance or
rejection, invalidation, and cache outcome without per-block spam.

Do not broadly relax matching thresholds. First determine whether the failure
comes from inconsistent bounds, wrong declared rotation, unavailable chunks,
unstable samples, a policy mismatch, ambiguity, or genuinely inadequate
evidence.

## Room-Data Pull Request

Create a branch named `room-data/alpha-<room-name>`. Include:

- the reviewed production definition;
- a compact finalization evidence report in `room-data/review/` when useful;
- the real room's updated Alpha status row;
- no bulk raw captures and no third-party data.

CI runs the complete matcher tests, validates the production database and
policy compatibility, rejects duplicate definitions, builds the jar, and
verifies packaging. Room-data pull requests are always human-reviewed and are
not auto-merged.
