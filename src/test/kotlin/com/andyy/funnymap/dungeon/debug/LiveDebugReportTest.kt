package com.andyy.funnymap.dungeon.debug

import com.andyy.funnymap.dungeon.model.DungeonScannerDebug
import com.andyy.funnymap.dungeon.model.RecognitionUnknownReason
import com.andyy.funnymap.dungeon.model.RoomScanDebug
import com.andyy.funnymap.dungeon.model.ScannerLifecycleState
import com.andyy.funnymap.dungeon.model.ScannerObservationState
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse

class LiveDebugReportTest {
	@Test
	fun `empty database report is useful without fabricating a room`() {
		val scanner = DungeonScannerDebug(
			lifecycle = ScannerLifecycleState.EMPTY_DATABASE,
			databaseRoomCount = 0,
			queuedWork = 0,
			databaseFingerprintCount = 0,
			databaseIdentity = "sha256:empty",
			fingerprintPolicyVersion = "structural-blockstates-v1",
			loadedChunkCount = 9,
			statusMessage = "The active room database is empty.",
		)

		val report = LiveDebugReportFormatter.format(context(scanner))

		assertContains(report, "scanner.lifecycle=EMPTY_DATABASE")
		assertContains(report, "scanner.loadedChunks=9")
		assertContains(report, "database.rooms=0")
		assertContains(report, "room.state=NONE")
		assertFalse(report.contains("uuid", ignoreCase = true))
		assertFalse(report.contains("accessToken", ignoreCase = true))
	}

	@Test
	fun `failed match report carries evidence needed for threshold diagnosis`() {
		val room = RoomScanDebug(
			observationId = "runtime-alpha-7",
			observationState = ScannerObservationState.REJECTED,
			availableCoverage = 0.80,
			eligibleSampleCount = 42,
			definitionSampleCount = 50,
			matchedSampleCount = 31,
			conflictingSampleCount = 4,
			comparableSampleCount = 35,
			observedToDefinitionCoverage = 31.0 / 42.0,
			definitionToObservedCoverage = 31.0 / 35.0,
			totalDefinitionCoverage = 31.0 / 50.0,
			candidateCount = 2,
			bestCandidate = "catacombs/alpha/base@0",
			runnerUpCandidate = "catacombs/beta/base@90",
			score = 0.71,
			runnerUpScore = 0.68,
			margin = 0.03,
			failureReason = RecognitionUnknownReason.AMBIGUOUS_MATCH,
			diagnosticMessage = "Winner margin is below threshold",
		)
		val scanner = DungeonScannerDebug(
			lifecycle = ScannerLifecycleState.SCANNING,
			databaseRoomCount = 2,
			queuedWork = 0,
			latestRoom = room,
		)

		val report = LiveDebugReportFormatter.format(context(scanner), room)

		assertContains(report, "room.matchedSamples=31")
		assertContains(report, "room.conflicts=4")
		assertContains(report, "room.candidateCount=2")
		assertContains(report, "room.failure=AMBIGUOUS_MATCH")
		assertContains(report, "room.margin=0.0300")
	}

	private fun context(scanner: DungeonScannerDebug) = LiveDebugReportContext(
		version = "0.4.0-dev+test",
		commit = "abcdef0",
		buildMode = "development",
		minecraftVersion = "26.1.2",
		floor = "F1",
		sessionGeneration = 7,
		scanner = scanner,
	)
}
