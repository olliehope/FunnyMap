# Summary

Describe the behavior changed and why.

## Validation

List the commands and relevant manual checks performed.

## Checklist

- [ ] `./gradlew build` succeeds.
- [ ] Headless/unit tests pass.
- [ ] `./gradlew validateRoomDatabase` succeeds.
- [ ] `./gradlew verifyReleaseResources` succeeds.
- [ ] No third-party copyrighted or incompatibly licensed room data was copied.
- [ ] New or changed common-layer logic has focused tests.
- [ ] No Minecraft/Fabric object crosses the immutable observation boundary.
- [ ] No force-loading, unavailable-world probing, packet spoofing, automation,
      gameplay assistance, or anti-cheat bypass was introduced.
- [ ] Room-data changes include capture/finalization evidence and independent provenance.
- [ ] Corpus Alpha room changes update `room-data/ALPHA_STATUS.md` with a real
      fresh-dungeon result, or clearly mark that result not yet tested.
- [ ] Documentation and changelog were updated where applicable.

## Room Data

For room-data changes, include the room id, metadata, capture count, coverage,
stable sample count, policy version, finalization report, digest, and exported
definition. Otherwise write `Not applicable`.
