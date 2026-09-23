# Development

## Prerequisites

- Java 25 JDK
- Git
- A network connection for the first Gradle dependency download

Use the checked-in Gradle 9.7.1 wrapper. Its distribution checksum is pinned in
`gradle/wrapper/gradle-wrapper.properties`.

## Branch Model

`main` is stable and release-ready. It must pass the full build, production
database validation, and packaging verification. Release tags originate from
`main`.

`dev` is the active integration branch. Completed short-lived work lands there
before an explicit promotion pull request to `main`. Pushes to `dev` produce
development artifacts, not Releases.

Use these branch prefixes:

- `feature/<name>`
- `fix/<name>`
- `room-data/<name>`
- `refactor/<name>`

### Recommended GitHub Protection

For `main`, require a pull request, require the CI checks, require the branch to
be up to date where practical, block force pushes, and block deletion. Include
production room-database validation among required checks.

For `dev`, require CI. Direct maintainer pushes may remain enabled during early
development, though reviewed pull requests are preferred.

These are GitHub repository settings and cannot be enforced by files alone.

## Common Commands

Linux/macOS:

```bash
./gradlew test
./gradlew validateRoomDatabase
./gradlew build
./gradlew verifyReleaseResources
./gradlew runClient
```

Windows PowerShell:

```powershell
.\gradlew.bat test
.\gradlew.bat validateRoomDatabase
.\gradlew.bat build
.\gradlew.bat verifyReleaseResources
.\gradlew.bat runClient
```

The `check` and `build` lifecycle includes database and packaging verification.
Tests use synthetic definitions under `src/test`; production data lives only in
the bundled `rooms.json` resource.

## Build Identity

`version` in `gradle.properties` is the authoritative semantic base version.
Normal builds default to development identity:

```text
FunnyMap 0.4.0-dev+local
```

CI supplies a short commit using `-PbuildCommit=<sha>`. Release-tag builds use
`-PbuildMode=release`, producing the base version without a development suffix.
Generated `build-info.properties`, `fabric.mod.json`, the manifest, and jar name
must agree; `verifyReleaseResources` checks them.

With developer tools enabled, `/fmapdev status` prints version, commit, mode,
Minecraft version, database digest, and room count. Development builds also
show a small `DEV` HUD indicator.

## Developer Tools

Set `FUNNYMAP_DEV_TOOLS=true` or JVM property `funnymap.devTools=true` to
register `/fmapdev`. Set `FUNNYMAP_DEBUG_HUD=true` or JVM property
`funnymap.debugHud=true` for matcher/scanner diagnostics.

Generated room artifacts are written below the game directory in
`funnymap-room-captures/` and are ignored by Git. See [ROOM_DATA.md](ROOM_DATA.md).
The first live-corpus procedure, all development command syntax, the strict
local overlay, and the fresh-dungeon acceptance test are documented in
[LIVE_VALIDATION.md](LIVE_VALIDATION.md).

## Releases

1. Merge a reviewed `dev` promotion into `main`.
2. Update the base version and `CHANGELOG.md` in that pull request.
3. Confirm CI and production database validation pass on `main`.
4. Create an annotated or lightweight tag matching the base version, such as
   `v0.4.0`.
5. The release workflow verifies the tag/version match, builds in release mode,
   rechecks packaged resources, creates a SHA-256 checksum, and publishes both
   files with the repository-provided `GITHUB_TOKEN`.

Never create a release from `dev` or upload a development artifact as a Release.
