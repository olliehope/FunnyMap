package com.andyy.funnymap.dungeon.capture

import com.andyy.funnymap.dungeon.room.LocalBlockPosition
import com.google.gson.JsonParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CaptureComparisonTest {
	@Test
	fun `comparison keeps stable blocks while reporting participating and ignored property changes`() {
		val first = CaptureTestFixtures.capture(
			"capture-10",
			CaptureTestFixtures.samples(
				propertiesAt = mapOf(0 to mapOf("facing" to "north", "waterlogged" to "false")),
			),
		)
		val second = CaptureTestFixtures.capture(
			"capture-11",
			CaptureTestFixtures.samples(
				propertiesAt = mapOf(0 to mapOf("facing" to "south", "waterlogged" to "true")),
			),
		)

		val comparison = CaptureComparator.compare(listOf(first, second))
		val atOrigin = comparison.stableSamples.single { it.position == LocalBlockPosition(0, 0, 0) }

		assertEquals(emptyMap(), atOrigin.properties)
		assertEquals(setOf("facing", "waterlogged"), comparison.changedProperties.map { it.property }.toSet())
		assertEquals(8, comparison.stableSamples.size)
		assertTrue(comparison.policyExclusions.any { "waterlogged" in it.exclusion.droppedProperties })
	}

	@Test
	fun `comparison separates block changes presence changes and unavailable positions`() {
		val baseline = CaptureTestFixtures.capture("capture-20")
		val changed = CaptureTestFixtures.capture(
			"capture-21",
			CaptureTestFixtures.samples(blockAt = mapOf(1 to "minecraft:cobblestone")).filterNot {
				it.position == LocalBlockPosition(2, 0, 0)
			},
		)
		val partial = CaptureTestFixtures.capture(
			"capture-22",
			CaptureTestFixtures.samples(positions = 0..5),
			availableMaxX = 5,
		)

		val comparison = CaptureComparator.compare(listOf(baseline, changed, partial))

		assertEquals(listOf(LocalBlockPosition(1, 0, 0)), comparison.changedBlockIdentifiers.map { it.position })
		assertEquals(listOf(LocalBlockPosition(2, 0, 0)), comparison.presenceChanges.map { it.position })
		assertEquals(setOf(LocalBlockPosition(6, 0, 0), LocalBlockPosition(7, 0, 0)), comparison.unavailablePositions.map { it.position }.toSet())
		assertFalse(comparison.stableSamples.any { it.position.x in 1..2 })
		assertEquals(0.75, comparison.coverage)
	}

	@Test
	fun `finalizer requires evidence and emits reloadable review candidate`() {
		val captures = listOf("capture-30", "capture-31", "capture-32").map(CaptureTestFixtures::capture)
		val report = CaptureFinalizer.finalize(
			captures = captures,
			policy = CaptureTestFixtures.policy,
			fingerprintId = "base",
			config = FinalizationConfig(
				minimumIndependentCaptures = 3,
				minimumObservationCoverage = 1.0,
				minimumStableSamples = 8,
				minimumPositionObservations = 3,
			),
		)

		assertEquals(FinalizationStatus.READY_FOR_REVIEW, report.status)
		assertEquals(8, report.acceptedSamples.size)
		assertEquals(CaptureTestFixtures.policy.version, report.fingerprintPolicyVersion)
		assertNotNull(report.finalDigest)
		val json = assertNotNull(report.candidateJson)
		val database = com.andyy.funnymap.dungeon.room.RoomDatabase.load(
			json,
			mapOf(CaptureTestFixtures.policy.version to CaptureTestFixtures.policy),
		)
		assertEquals(CaptureTestFixtures.metadata.roomId, database.definitions.single().id)
		val reportJson = JsonParser.parseString(CaptureReportJson.encodeFinalization(report)).asJsonObject
		assertEquals("capture-finalization", reportJson.get("kind").asString)
		assertEquals(8, reportJson.getAsJsonArray("acceptedSamples").size())
		assertEquals(1, reportJson.getAsJsonObject("candidateRoomDatabase").getAsJsonArray("rooms").size())
	}

	@Test
	fun `comparison report JSON retains partial and changing evidence`() {
		val first = CaptureTestFixtures.capture("capture-35")
		val second = CaptureTestFixtures.capture(
			"capture-36",
			CaptureTestFixtures.samples(blockAt = mapOf(0 to "minecraft:cobblestone"), positions = 0..5),
			availableMaxX = 5,
		)
		val comparison = CaptureComparator.compare(listOf(first, second))

		val report = JsonParser.parseString(CaptureReportJson.encodeComparison(comparison)).asJsonObject

		assertEquals("capture-comparison", report.get("kind").asString)
		assertEquals(1, report.getAsJsonArray("changedBlockIdentifiers").size())
		assertEquals(2, report.getAsJsonArray("unavailablePositions").size())
	}

	@Test
	fun `finalizer rejects incomplete evidence without inventing a definition`() {
		val report = CaptureFinalizer.finalize(
			captures = listOf(
				CaptureTestFixtures.capture("capture-40", CaptureTestFixtures.samples(positions = 0..5), 5),
				CaptureTestFixtures.capture("capture-41", CaptureTestFixtures.samples(positions = 0..5), 5),
			),
			policy = CaptureTestFixtures.policy,
			config = FinalizationConfig(
				minimumIndependentCaptures = 3,
				minimumObservationCoverage = 0.9,
				minimumStableSamples = 8,
				minimumPositionObservations = 2,
			),
		)

		assertEquals(FinalizationStatus.REJECTED, report.status)
		assertEquals(null, report.candidateDefinition)
		assertEquals(null, report.candidateJson)
		assertTrue(report.warnings.any { "independent captures" in it })
		assertTrue(report.warnings.any { "coverage" in it })
	}
}
