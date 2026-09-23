# Contributing to FunnyMap

Thank you for helping build FunnyMap. Contributions should preserve the two
boundaries the project depends on: only normal client-visible game data may be
used, and the production room corpus must be independently collected.

By contributing original code, documentation, room data, or assets, you agree
that the contribution may be distributed under the repository's CC0-1.0
licensing intent. Do not contribute material you cannot license that way.

## Branches

The repository uses a small integration model:

- `main` contains stable, release-ready code. It must build, its production
  database must validate, and version tags originate here.
- `dev` contains active integration work and may produce development artifacts.
- Use short-lived `feature/<name>`, `fix/<name>`, `room-data/<name>`, or
  `refactor/<name>` branches.

Open normal pull requests against `dev`. Promotion from `dev` to `main` should
be an explicit, reviewed pull request. Delete short-lived branches after merge.
Do not create release or hotfix branch families unless the project develops a
concrete need for them.

Recommended GitHub protection is documented in [Development](docs/DEVELOPMENT.md).

## Local Setup

Install a Java 25 JDK, clone the repository, and use the included wrapper:

```bash
git clone https://github.com/olliehope/FunnyMap.git
cd FunnyMap
git switch dev
./gradlew test validateRoomDatabase build verifyReleaseResources
```

Windows contributors should use `gradlew.bat`. `./gradlew runClient` launches a
development client. Never commit files from `run/`, `.gradle/`, `build/`, raw
capture directories, logs, or IDE-local configuration.

## Code Changes

- Follow the existing Kotlin style: tabs for indentation, trailing commas in
  multiline declarations, descriptive names, and comments only where intent is
  not apparent from the code.
- Keep common room, matching, normalization, and rendering logic free of
  Minecraft and Fabric objects.
- All world, chunk, entity, and block-state reads must occur on the client
  thread and be copied immediately into immutable common values.
- Use no-create chunk lookup. Missing chunks are unavailable evidence, not air.
- Do not introduce packet spoofing, force-loading, hidden-state probing,
  automation, gameplay assistance, or anti-cheat bypasses.
- Add focused headless tests for new common-layer behavior and regressions.
- Update documentation when behavior, configuration, commands, schemas, or
  contributor workflow changes.

There is not yet an automatic source formatter. Format changed Kotlin and
Gradle files consistently with their neighbors and keep unrelated formatting
churn out of pull requests.

## Required Checks

Run these before opening a pull request:

```bash
./gradlew test
./gradlew validateRoomDatabase
./gradlew build
./gradlew verifyReleaseResources
```

`validateRoomDatabase` uses the same strict loader as runtime.
`verifyReleaseResources` checks build identity and fails if raw captures,
development reports, exports, fixtures, or synthetic room IDs enter the jar.

## Room Data Contributions

Room-data branches should use `room-data/<short-room-name>`. Follow
[docs/ROOM_DATA.md](docs/ROOM_DATA.md) from capture through human review.

A room-data pull request should report:

- stable room id, display name, type, and canonical shape;
- number of independent captures and relevant game/data versions;
- capture coverage and unavailable areas;
- accepted stable sample count and significant exclusions;
- fingerprint-policy version;
- the compact finalization report;
- the resulting exported definition and production database change.

Commit the reviewed production change in
`src/main/resources/assets/funnymap/rooms.json`. A compact review artifact may
be placed under `room-data/review/`; do not commit the full local raw capture
directory unless maintainers explicitly request it for a difficult review.

Do not copy room databases or fingerprints from FunnyMap, BetterMap, CryptKit,
Skyblocker, OdinFabric, or another project merely because they are publicly
accessible. This repository's corpus remains independently collected unless a
future explicit licensing decision changes that policy.

## Pull Requests

Keep each pull request focused and explain the observable behavior. Complete
the pull-request checklist, link relevant issues, include test results, and call
out remaining limitations. Room-data and scanner changes should include useful
debug evidence without exposing server credentials, account details, or other
personal information.

Reviewers may ask for narrower scope, more evidence, additional captures, or a
fresh export. Passing CI is necessary but does not replace human review.
