# Room Data Contributions

FunnyMap's room corpus is independently collected. Production definitions must
come from normal client-visible block data captured with this project, not from
another mod's database.

```text
CAPTURE
    |
REPEAT CAPTURE
    |
COMPARE
    |
FINALIZE
    |
EXPORT
    |
HUMAN REVIEW
    |
ROOM-DATA PR
    |
CI VALIDATION
    |
MERGE
    |
RELEASE DATABASE
```

## Before Capturing

Build a development jar and choose explicit metadata: stable room id, display
name, room type, canonical footprint, secret count, crypt count, and observed
rotation. Development builds register `/fmapdev` automatically. Determine
inclusive world bounds
that use the room's north-west footprint boundary and a consistent vertical
datum. The matcher does not need to recognise the room first.

Do not capture or submit blocks from unavailable chunks. The tool uses no-create
lookups and records unavailable coverage explicitly.

## Capture and Compare

For live collection, prefer the reviewed two-step workflow. It removes the need
to type large world-coordinate bounds and does not write until the preview is
confirmed:

```text
/fmapdev capturemark min
/fmapdev capturemark max
/fmapdev capturemark show
/fmapdev capturehere catacombs/test-room "Test Room" NORMAL "0,0" 3 1 0 first-pass
/fmapdev captureconfirm
```

Shape, footprint, rotation, bounds, secrets, and crypts remain developer
assertions. The command never guesses them. Use `/fmapdev capturecancel` for an
incomplete preview. The exact in-dungeon procedure is in
[LIVE_VALIDATION.md](LIVE_VALIDATION.md).

The original explicit-bounds form remains available for scripted or otherwise
hard-to-target volumes:

```text
/fmapdev capture catacombs/test-room "Test Room" NORMAL "0,0" 3 1 0 100 60 200 131 79 231 first-pass
/fmapdev capture catacombs/test-room "Test Room" NORMAL "0,0" 3 1 0 100 60 200 131 79 231 second-pass
/fmapdev capture catacombs/test-room "Test Room" NORMAL "0,0" 3 1 0 100 60 200 131 79 231 third-pass
/fmapdev captures catacombs/test-room
/fmapdev compare catacombs/test-room
```

Capture the room independently under meaningfully different runtime conditions
where possible. Keep bounds, canonical footprint, and supplied orientation
consistent. Comparison identifies stable blocks, changed identifiers, changed
properties, presence changes, policy exclusions, insufficient observations,
and unavailable positions.

## Finalize and Export

```text
/fmapdev finalize catacombs/test-room base
/fmapdev export catacombs/test-room
```

Finalization applies minimum independent-capture, coverage, stable-sample, and
per-position observation thresholds. It writes accepted samples, exclusions,
warnings, contributors, digest, policy version, and a candidate definition for
human review. It never edits the bundled database.

Artifacts are created under:

```text
funnymap-room-captures/
  raw/
  reports/
  exports/
```

A development build may additionally load a reviewed full database document at
`funnymap-room-captures/dev-rooms.json` using `/fmapdev reload`. It passes the
same validation and policy checks as bundled data, rejects duplicate ids, is
ignored by Git, and is never packaged. The bundled database remains
authoritative in release builds.

## What to Commit

For a normal room-data pull request, commit:

- the reviewed change to
  `src/main/resources/assets/funnymap/rooms.json`;
- a compact finalization report under `room-data/review/` when it materially
  helps review;
- documentation changes needed for a policy or schema change.

Do not normally commit:

- the entire `funnymap-room-captures/` directory;
- raw captures containing redundant full-room observations;
- generated text reports that duplicate the compact finalization report;
- local logs, screenshots with personal information, or game/runtime files;
- copied definitions, hashes, coordinates, or fingerprints from another mod.

Maintainers may request selected raw captures for an ambiguous change. Share
only the requested artifacts and review them for personal data first.

## Pull Request Evidence

Use a `room-data/<name>` branch and include:

- room id, display name, type, shape, secrets, and crypts;
- number of captures and game/data versions;
- minimum and per-capture coverage;
- stable sample count and important exclusion reasons;
- fingerprint policy version and final digest;
- rotation information and whether structural symmetry remains;
- finalization report and exported definition;
- screenshots only when they help identify the visible room.

Run:

```bash
./gradlew test validateRoomDatabase build verifyReleaseResources
```

CI validates the entire production database and ensures synthetic or capture
artifacts did not enter the release jar. Human review still decides whether the
capture provenance, metadata, and structural stability are convincing.

## Licensing Rule

Do not copy databases from FunnyMap, BetterMap, CryptKit, Skyblocker,
OdinFabric, or any other project merely because they can be downloaded. Public
access is not a license grant compatible with this repository. The corpus stays
independently collected unless maintainers make and document a future explicit
licensing decision.
