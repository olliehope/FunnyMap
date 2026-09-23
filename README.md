# FunnyMap

FunnyMap is a native Fabric dungeon-map and room-recognition utility for
Minecraft Java 26.1.2. It is inspired by useful architectural ideas in older
dungeon tools, including the original FunnyMap, while being implemented
independently for modern Minecraft APIs and block-state data.

The project is pre-1.0 and under active development. Milestones 1 through 4 are
implemented: conservative Hypixel/SkyBlock/Catacombs detection, an immutable
dungeon model, the custom HUD map, independent room capture tooling, structural
fingerprints, and loaded-only incremental room matching. The bundled production
room database is intentionally empty until independently collected definitions
have completed review.

## Screenshots

Screenshots will be added after the independent production room corpus is large
enough to demonstrate real layouts without shipping copied third-party data.

<!-- Add reviewed screenshots under docs/images/ and replace this notice. -->

## Requirements

- Minecraft Java Edition 26.1.2
- Fabric Loader 0.19.5 or a newer compatible loader
- Fabric API 0.155.3+26.1.2 or a compatible release
- Fabric Language Kotlin 1.14.1+kotlin.2.4.20 or a compatible release
- Java 25 for building from source

## Features

- Hypixel, SkyBlock, Catacombs, and floor detection from client-visible server
  metadata and the sidebar scoreboard.
- Immutable 6x6 dungeon topology with 1x1, 1x2, 1x3, 1x4, 2x2, and L-shaped
  room footprints.
- A snapshot-only HUD renderer for room geometry, names, known orientation,
  typed doors, completion markers, and debug evidence supplied by producers.
- A versioned structural room database using registry identifiers and selected
  stable block properties instead of legacy numeric IDs.
- Four-rotation, bidirectional matching with coverage thresholds, conflict
  accounting, ambiguity rejection, and typed failure reasons.
- Loaded-only incremental scanning with per-tick budgets, chunk dependency
  invalidation, and session-safe caches.
- Opt-in `/fmapdev` capture, comparison, finalization, export, and build/database
  status tools for creating an independent room corpus.

## Client Data Boundary

FunnyMap uses only information delivered to the normal Minecraft client. It
does not force-load chunks, query hidden server state, spoof packets, automate
player actions, assist movement or combat, or attempt anti-cheat bypasses.
Missing world data stays unavailable and uncertain matches stay unknown.

This project may expose information earlier or differently than the unmodified
game client. Server rules and allowed-mod policies can change. Users are
responsible for determining whether using particular features is permitted on
the servers they play on.

FunnyMap is not affiliated with or endorsed by Mojang Studios, Microsoft,
Hypixel, or any referenced dungeon-mod project. It does not claim to be
"allowed", "undetectable", or safe from moderation action.

## Installation

1. Install Fabric Loader for Minecraft 26.1.2.
2. Install compatible Fabric API and Fabric Language Kotlin builds.
3. Download a FunnyMap release jar from the repository's Releases page.
4. Place all required jars in the Minecraft `mods` directory.

Development artifacts from GitHub Actions are snapshots for testing. They show
a small `DEV` indicator and are not GitHub Releases. Do not redistribute a
development artifact as an official release.

## Building

Clone the repository and use the checked-in Gradle wrapper:

```bash
git clone https://github.com/olliehope/FunnyMap.git
cd FunnyMap
./gradlew test validateRoomDatabase build verifyReleaseResources
```

On Windows, use `gradlew.bat` instead of `./gradlew`. A normal local build is
identified as `FunnyMap 0.4.0-dev+local`. Artifacts are written to `build/libs/`.

Release mode is reserved for validated tag builds:

```bash
./gradlew clean build -PbuildMode=release -PbuildCommit=<short-commit>
```

The base semantic version lives in `gradle.properties`. Fabric metadata,
artifact names, runtime build information, and development suffixes derive from
that value.

## Room Recognition

Loaded chunk sections are searched incrementally for uncommon structural
anchors from the bundled database. Several independent room-local positions
must agree on a candidate origin before FunnyMap snapshots the candidate area.
The snapshot records available and unavailable coverage, immediately discards
Minecraft objects, and passes immutable values to the pure matcher. Matching
then checks compatible shapes and rotations in both directions and rejects weak
or ambiguous evidence. See [Scanner](docs/SCANNER.md) and
[Architecture](docs/ARCHITECTURE.md).

## Room Data

The production corpus must be independently captured with this project's
tooling. Public availability of another project's database is not permission to
copy it. The capture-to-review process is documented in
[Room Data Contributions](docs/ROOM_DATA.md). Project owners collecting the
first live definitions should follow [Corpus Alpha Live Validation](docs/LIVE_VALIDATION.md)
and record real results in [Alpha Status](room-data/ALPHA_STATUS.md).

## Contributing

Start with [CONTRIBUTING.md](CONTRIBUTING.md) and
[Development](docs/DEVELOPMENT.md). Bug, feature, and room-data issue forms are
available on GitHub. Pull requests should target `dev` unless preparing an
explicitly reviewed promotion to `main`.

## License

Original FunnyMap project code and independently collected project data are
made available under [CC0 1.0 Universal](LICENSE). Contributors must not submit
code, data, or assets they do not have the right to contribute under that
licensing intent.

## Acknowledgements

FunnyMap, BetterMap, CryptKit, Skyblocker, and OdinFabric were studied as
architectural references. Their code, data, names, and licenses remain their
own. See [ACKNOWLEDGEMENTS.md](ACKNOWLEDGEMENTS.md) for the exact distinction.
