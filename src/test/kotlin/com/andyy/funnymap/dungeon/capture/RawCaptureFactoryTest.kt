package com.andyy.funnymap.dungeon.capture

import com.andyy.funnymap.dungeon.model.GridPosition
import com.andyy.funnymap.dungeon.model.RoomFootprint
import com.andyy.funnymap.dungeon.model.RoomId
import com.andyy.funnymap.dungeon.model.RoomRotation
import com.andyy.funnymap.dungeon.model.RoomType
import com.andyy.funnymap.dungeon.room.GameDataVersion
import com.andyy.funnymap.dungeon.room.LocalBlockBounds
import com.andyy.funnymap.dungeon.room.LocalBlockPosition
import com.andyy.funnymap.dungeon.room.ObservationCoverage
import com.andyy.funnymap.dungeon.room.ObservationProvenance
import com.andyy.funnymap.dungeon.room.ObservationSource
import com.andyy.funnymap.dungeon.room.RoomGeometry
import com.andyy.funnymap.dungeon.room.RoomLocalDatum
import com.andyy.funnymap.dungeon.room.RoomObservation
import com.andyy.funnymap.dungeon.room.StructuralSample
import com.andyy.funnymap.dungeon.room.WorldCoordinate
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RawCaptureFactoryTest {
	@Test
	fun `factory converts a rotated rectangular capture to canonical coordinates`() {
		val footprint = RoomFootprint.of(GridPosition(0, 0), GridPosition(1, 0))
		val metadata = CaptureRoomMetadata(
			RoomId("catacombs/rotated-fixture"), "Rotated Fixture", RoomType.PUZZLE, footprint, 0, 0,
		)
		val observedBounds = LocalBlockBounds(LocalBlockPosition(0, 0, 0), LocalBlockPosition(1, 0, 2))
		val observation = RoomObservation(
			observationId = "rotated-1",
			worldSessionGeneration = 8,
			revision = 1,
			gameDataVersion = GameDataVersion("26.1.2", 9999, "main"),
			fingerprintPolicyVersion = CaptureTestFixtures.policy.version,
			footprint = RoomGeometry.rotateFootprint(footprint, RoomRotation.DEGREES_90),
			bounds = observedBounds,
			datum = RoomLocalDatum(WorldCoordinate(20, 64, 30)),
			samples = listOf(
				StructuralSample(LocalBlockPosition(1, 0, 0), "minecraft:oak_stairs", mapOf("facing" to "east")),
			),
			coverage = ObservationCoverage.complete(observedBounds),
			provenance = ObservationProvenance(ObservationSource.DEVELOPER_CAPTURE, "rotated-1"),
			observedRotation = RoomRotation.DEGREES_90,
		)

		val capture = RawCaptureFactory.createAt(
			"rotated-1", metadata, observation, CaptureTestFixtures.policy,
			capturedAt = Instant.parse("2026-09-22T00:00:00Z"),
		)

		assertEquals(footprint, capture.observation.footprint)
		assertEquals(LocalBlockBounds(LocalBlockPosition(0, 0, 0), LocalBlockPosition(2, 0, 1)), capture.observation.bounds)
		assertEquals(LocalBlockPosition(0, 0, 0), capture.samples.single().position)
		assertEquals("north", capture.samples.single().properties["facing"])
		assertTrue(RawCaptureIntegrity.verify(capture))
	}

	@Test
	fun `incomplete capture remains explicitly incomplete after raw conversion`() {
		val capture = CaptureTestFixtures.capture(
			"partial-1",
			CaptureTestFixtures.samples(positions = 0..4),
			availableMaxX = 4,
		)

		assertFalse(capture.observation.coverage.isComplete)
		assertEquals(0.625, capture.observation.coverage.coverageRatio)
		assertEquals(1, capture.observation.coverage.unavailableRegions.size)
	}
}
