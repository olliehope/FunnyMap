# Corpus Alpha Status

Corpus Alpha targets approximately five independently collected, simple normal
1x1 Catacombs rooms. This file records only evidence observed with FunnyMap in
real dungeon instances.

**Current status: 0/5 rooms collected; live recognition not yet validated.**

No room rows exist yet because no real Hypixel captures were available during
the tooling phase. Add a row only after the first actual capture.

| Room id | Display name | Shape | Type | Captures | Coverage avg/min | Stable samples | Finalized | Promoted | Fresh-dungeon recognition | Rotations tested | Notes/problems |
| --- | --- | --- | --- | --- | ---: | ---: | ---: | --- | --- | --- | --- | --- |

<!--
Example structure only. Replace this comment with real observed values; do not
commit it as evidence:
| catacombs/<id> | <name> | 1x1 | NORMAL | 3 | <avg>/<min> | <count> | yes/no | yes/no | PASS/FAIL/not tested | 0,90,... | <notes> |
-->

## Acceptance Rule

A room counts toward Alpha only after at least three good captures where
practical, human-reviewed finalization, database validation, and automatic
recognition in a fresh dungeon. A local overlay result may be recorded in notes
but does not by itself mean the room was promoted to the bundled corpus.

For each failed live test, retain the eligible, matched, conflict and comparable
counts; all three coverage measures; score; runner-up; margin; rotation; typed
failure; database identity; and policy version. The procedure is in
[`docs/LIVE_VALIDATION.md`](../docs/LIVE_VALIDATION.md).
