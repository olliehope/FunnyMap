# Changelog

FunnyMap follows semantic versioning. Before 1.0, minor versions may represent
substantial milestones and compatibility can still change.

## Unreleased

### Added

- Public-project documentation, contribution guidance, issue forms, and
  automated CI/development/release workflows.
- Programmatic development/release build identity and package verification.
- Corpus Alpha live-validation guidance, status tracking, structured scanner
  diagnostics, safe current-room reports, and a validated development database
  overlay.
- Two-step room capture with explicit corner marks, a non-writing preview, and
  confirmation before raw artifacts are created.

### Changed

- Empty-database scanner sessions now expose loaded-client-chunk activity and
  an explicit bootstrap explanation without fabricating room candidates.
- The opt-in debug HUD and development `/fmapdev` status tools expose database, observation,
  match-evidence, cache, and invalidation state.

### Fixed

- Canonical slash-separated room ids are accepted directly by developer
  commands.
- Developer commands and local room overlays cannot be enabled in release
  builds.
- Development builds now register `/fmapdev` by default instead of requiring a
  second opt-in flag.

### Removed

- None.

No public releases have been recorded in this changelog yet.
