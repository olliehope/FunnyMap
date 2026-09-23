package com.andyy.funnymap.tools

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReleaseArtifactVerifierCliTest {
	@Test
	fun `rejects generated room data wherever it appears in an archive`() {
		assertTrue(ReleaseArtifactVerifierCli.isForbiddenPackagedPath("raw/capture.json"))
		assertTrue(ReleaseArtifactVerifierCli.isForbiddenPackagedPath("assets/funnymap/reports/final.json"))
		assertTrue(ReleaseArtifactVerifierCli.isForbiddenPackagedPath("room-data/exports/room.json"))
		assertTrue(ReleaseArtifactVerifierCli.isForbiddenPackagedPath("captures/example.room-capture.json"))
	}

	@Test
	fun `allows the opt-in capture implementation itself`() {
		assertFalse(
			ReleaseArtifactVerifierCli.isForbiddenPackagedPath(
				"com/andyy/funnymap/dungeon/capture/CaptureReportJson.class",
			),
		)
	}

	@Test
	fun `rejects synthetic room id markers without matching ordinary words`() {
		assertTrue(ReleaseArtifactVerifierCli.isSyntheticRoomId("catacombs/synthetic/example"))
		assertTrue(ReleaseArtifactVerifierCli.isSyntheticRoomId("fixtures/rotation-test"))
		assertTrue(ReleaseArtifactVerifierCli.isSyntheticRoomId("catacombs/test-room"))
		assertFalse(ReleaseArtifactVerifierCli.isSyntheticRoomId("catacombs/testament"))
	}
}
