# Production Room Corpus

The runtime production corpus is the validated resource at:

```text
src/main/resources/assets/funnymap/rooms.json
```

This directory is for small, review-oriented contribution artifacts only.
Place an approved compact finalization report under `review/` when it helps a
room-data pull request. Do not place the production database here and do not
commit bulk raw capture sets.

Every definition must be independently captured with FunnyMap tooling and pass
both human review and `./gradlew validateRoomDatabase`. See
[docs/ROOM_DATA.md](../docs/ROOM_DATA.md) for the complete workflow.
