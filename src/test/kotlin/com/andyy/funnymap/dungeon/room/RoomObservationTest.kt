package com.andyy.funnymap.dungeon.room

import com.andyy.funnymap.dungeon.model.GridPosition
import com.andyy.funnymap.dungeon.model.RoomFootprint
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RoomObservationTest {
	private val bounds = LocalBlockBounds(
		LocalBlockPosition(0, 0, 0),
		LocalBlockPosition(3, 0, 1),
	)

	@Test
	fun `incomplete coverage remains explicit and queryable`() {
		val coverage = ObservationCoverage(
			availableRegions = listOf(
				CoverageRegion(LocalBlockBounds(LocalBlockPosition(0, 0, 0), LocalBlockPosition(1, 0, 1)), 0, 0),
			),
			unavailableRegions = listOf(
				UnavailableRegion(
					CoverageRegion(LocalBlockBounds(LocalBlockPosition(2, 0, 0), LocalBlockPosition(3, 0, 1)), 1, 0),
					UnavailableReason.CHUNK_UNAVAILABLE,
				),
			),
		)

		assertEquals(0.5, coverage.coverageRatio)
		assertFalse(coverage.isComplete)
		assertTrue(coverage.isAvailable(LocalBlockPosition(1, 0, 1)))
		assertEquals(UnavailableReason.CHUNK_UNAVAILABLE, coverage.unavailableReasonAt(LocalBlockPosition(3, 0, 1)))
	}

	@Test
	fun `observation has structural equality and defensive sample copy`() {
		val mutableSamples = mutableListOf(StructuralSample(LocalBlockPosition(0, 0, 0), "minecraft:stone"))
		val first = observation(mutableSamples)
		val second = observation(mutableSamples.toList())
		mutableSamples.clear()

		assertEquals(first, second)
		assertEquals(first.hashCode(), second.hashCode())
		assertEquals(1, first.samples.size)
	}

	@Test
	fun `sample cannot claim unavailable coverage`() {
		val coverage = ObservationCoverage(
			availableRegions = listOf(CoverageRegion(LocalBlockBounds(bounds.min, LocalBlockPosition(1, 0, 1)))),
			unavailableRegions = listOf(
				UnavailableRegion(
					CoverageRegion(LocalBlockBounds(LocalBlockPosition(2, 0, 0), bounds.max)),
					UnavailableReason.CHUNK_UNAVAILABLE,
				),
			),
		)

		assertFailsWith<IllegalArgumentException> {
			observation(
				listOf(StructuralSample(LocalBlockPosition(3, 0, 1), "minecraft:stone")),
				coverage,
			)
		}
	}

	private fun observation(
		samples: Collection<StructuralSample>,
		coverage: ObservationCoverage = ObservationCoverage.complete(bounds),
	): RoomObservation = RoomObservation(
		observationId = UUID.nameUUIDFromBytes("observation".toByteArray()).toString(),
		worldSessionGeneration = 4,
		revision = 7,
		gameDataVersion = GameDataVersion("26.1.2", 5000, "main"),
		footprint = RoomFootprint.of(GridPosition(0, 0)),
		bounds = bounds,
		datum = RoomLocalDatum(WorldCoordinate(100, 70, -20)),
		samples = samples,
		coverage = coverage,
		provenance = ObservationProvenance(ObservationSource.HEADLESS_TEST, attributes = mapOf("fixture" to "true")),
	)
}
