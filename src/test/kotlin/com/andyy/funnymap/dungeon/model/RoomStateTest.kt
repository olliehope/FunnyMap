package com.andyy.funnymap.dungeon.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class RoomStateTest {
	@Test
	fun `known recognition retains matcher confidence and evidence`() {
		val evidence = RecognitionEvidence(
			observedSampleCount = 10,
			definitionSampleCount = 12,
			matchedSampleCount = 9,
			conflictingSampleCount = 1,
			candidateCount = 2,
			observationCoverage = 0.9,
			definitionCoverage = 0.75,
		)
		val recognition = RoomRecognition.Known(
			definitionId = "catacombs/test-room",
			roomName = "Test Room",
			confidence = 0.82,
			evidence = evidence,
		)

		assertEquals(0.82, recognition.confidence)
		assertEquals(evidence, recognition.evidence)
	}

	@Test
	fun `unknown recognition retains a typed failure`() {
		val recognition = RoomRecognition.Unknown(
			reason = RecognitionUnknownReason.CHUNK_UNAVAILABLE,
			evidence = RecognitionEvidence(unavailableSampleCount = 8),
		)

		assertEquals(RecognitionUnknownReason.CHUNK_UNAVAILABLE, recognition.reason)
		assertEquals(8, recognition.evidence.unavailableSampleCount)
	}

	@Test
	fun `evidence carries partial match scoring without assigning identity`() {
		val evidence = RecognitionEvidence(
			observedSampleCount = 12,
			definitionSampleCount = 20,
			matchedSampleCount = 8,
			conflictingSampleCount = 1,
			unavailableSampleCount = 3,
			candidateCount = 4,
			availableCoverage = 0.75,
			totalDefinitionCoverage = 0.6,
			observedToDefinitionCoverage = 8.0 / 9.0,
			definitionToObservedCoverage = 0.8,
			candidateRotation = RoomRotation.DEGREES_90,
			candidateScore = 7.5,
			runnerUpScore = 7.0,
			scoreMargin = 0.5,
		)

		assertEquals(9, evidence.comparableSampleCount)
		assertEquals(RoomRotation.DEGREES_90, evidence.candidateRotation)
		assertEquals(0.5, evidence.scoreMargin)
	}

	@Test
	fun `rejects invalid confidence coverage and sample counts`() {
		assertFailsWith<IllegalArgumentException> {
			RoomRecognition.Unknown(RecognitionUnknownReason.NO_DATABASE_MATCH, confidence = Double.NaN)
		}
		assertFailsWith<IllegalArgumentException> {
			RecognitionEvidence(observationCoverage = 1.01)
		}
		assertFailsWith<IllegalArgumentException> {
			RecognitionEvidence(
				observedSampleCount = 2,
				definitionSampleCount = 2,
				matchedSampleCount = 2,
				conflictingSampleCount = 1,
			)
		}
	}

	@Test
	fun `empty snapshot exposes insufficient evidence while populated snapshots may be healthy`() {
		assertEquals(
			RecognitionUnknownReason.INSUFFICIENT_EVIDENCE,
			DungeonSnapshot.EMPTY.failureReason,
		)

		val snapshot = DungeonSnapshot(revision = 1, grid = DungeonGrid.EMPTY)
		assertNull(snapshot.failureReason)
	}
}
