package com.andyy.funnymap.dungeon.room

import com.andyy.funnymap.dungeon.model.GridPosition
import com.andyy.funnymap.dungeon.model.RoomFootprint
import com.andyy.funnymap.dungeon.model.RoomRotation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class StructuralNormalizationTest {
	private val bounds = LocalBlockBounds(
		LocalBlockPosition(0, 0, 0),
		LocalBlockPosition(2, 3, 4),
	)

	@Test
	fun `normalization sorts positions and properties deterministically`() {
		val later = StructuralSample(
			LocalBlockPosition(2, 1, 4),
			"minecraft:oak_stairs",
			linkedMapOf("waterlogged" to "false", "facing" to "north"),
		)
		val earlier = StructuralSample(LocalBlockPosition(0, 0, 0), "minecraft:stone_bricks")

		val normalized = StructuralNormalizer.normalizeSamples(listOf(later, earlier))

		assertEquals(listOf(earlier, later), normalized)
		assertEquals(listOf("facing", "waterlogged"), normalized.last().properties.keys.toList())
		assertEquals(
			StructuralNormalizer.sha256(bounds, listOf(later, earlier)),
			StructuralNormalizer.sha256(bounds, listOf(earlier, later)),
		)
	}

	@Test
	fun `digest includes explicit bounds`() {
		val sample = StructuralSample(LocalBlockPosition(0, 0, 0), "minecraft:stone")
		val small = LocalBlockBounds(LocalBlockPosition(0, 0, 0), LocalBlockPosition(0, 0, 0))
		val large = LocalBlockBounds(LocalBlockPosition(0, 0, 0), LocalBlockPosition(1, 0, 0))

		assertNotEquals(
			StructuralNormalizer.sha256(small, listOf(sample)),
			StructuralNormalizer.sha256(large, listOf(sample)),
		)
	}

	@Test
	fun `digest encoding has a stable regression value`() {
		val point = LocalBlockPosition(0, 0, 0)
		val pointBounds = LocalBlockBounds(point, point)

		assertEquals(
			"sha256:25a9b4dbfe8779f813a29ec5253934316210bb2551b7e4c0354112693b2d4005",
			StructuralNormalizer.sha256(
				pointBounds,
				listOf(StructuralSample(point, "minecraft:stone")),
			),
		)
	}

	@Test
	fun `rectangular samples rotate through every quarter turn`() {
		val sample = StructuralSample(LocalBlockPosition(0, 2, 1), "minecraft:stone")
		val expected = mapOf(
			RoomRotation.DEGREES_0 to LocalBlockPosition(0, 2, 1),
			RoomRotation.DEGREES_90 to LocalBlockPosition(3, 2, 0),
			RoomRotation.DEGREES_180 to LocalBlockPosition(2, 2, 3),
			RoomRotation.DEGREES_270 to LocalBlockPosition(1, 2, 2),
		)

		expected.forEach { (rotation, position) ->
			val result = StructuralNormalizer.rotate(listOf(sample), bounds, rotation, FingerprintPolicy.DEFAULT)
			assertEquals(position, result.samples.single().position)
			val expectedDimensions = if (rotation.quarterTurns % 2 == 0) 3 to 5 else 5 to 3
			assertEquals(expectedDimensions, result.bounds.width to result.bounds.depth)
		}
	}

	@Test
	fun `four clockwise rotations restore samples and bounds`() {
		var samples = listOf(StructuralSample(LocalBlockPosition(0, 2, 1), "minecraft:stone"))
		var rotatedBounds = bounds
		repeat(4) {
			val result = StructuralNormalizer.rotate(
				samples,
				rotatedBounds,
				RoomRotation.DEGREES_90,
				FingerprintPolicy.DEFAULT,
			)
			samples = result.samples
			rotatedBounds = result.bounds
		}
		assertEquals(listOf(StructuralSample(LocalBlockPosition(0, 2, 1), "minecraft:stone")), samples)
		assertEquals(bounds, rotatedBounds)
	}

	@Test
	fun `rectangular and L footprints rotate and normalize`() {
		val line = RoomFootprint.of(GridPosition(4, 8), GridPosition(5, 8), GridPosition(6, 8))
		val rotatedLine = RoomGeometry.rotateFootprint(line, RoomRotation.DEGREES_90)
		assertEquals(setOf(GridPosition(0, 0), GridPosition(0, 1), GridPosition(0, 2)), rotatedLine.localCells)

		val lShape = RoomFootprint.of(GridPosition(0, 0), GridPosition(0, 1), GridPosition(1, 1))
		val rotatedL = RoomGeometry.rotateFootprint(lShape, RoomRotation.DEGREES_90)
		assertEquals(setOf(GridPosition(1, 0), GridPosition(0, 0), GridPosition(0, 1)), rotatedL.localCells)
	}

	@Test
	fun `directional allow-listed properties rotate explicitly`() {
		val sample = StructuralSample(
			LocalBlockPosition(0, 0, 1),
			"minecraft:oak_log",
			mapOf("facing" to "north", "axis" to "x", "rotation" to "15", "half" to "top"),
		)
		val result = StructuralNormalizer.rotate(
			listOf(sample),
			LocalBlockBounds(LocalBlockPosition(0, 0, 0), LocalBlockPosition(1, 0, 2)),
			RoomRotation.DEGREES_90,
			FingerprintPolicy.DEFAULT,
		)

		assertEquals(
			mapOf("axis" to "z", "facing" to "east", "half" to "top", "rotation" to "3"),
			result.samples.single().properties,
		)
		assertTrue(result.issues.isEmpty())
	}

	@Test
	fun `unsupported directional property is preserved and reported`() {
		val policy = FingerprintPolicy(
			version = "test-policy",
			allowedNamespaces = setOf("minecraft"),
			participatingProperties = setOf("orientation_hint"),
			directionalProperties = mapOf("orientation_hint" to DirectionalPropertyKind.UNSUPPORTED),
			minimumSamples = 1,
		)
		val sample = StructuralSample(
			LocalBlockPosition(0, 0, 1),
			"minecraft:stone",
			mapOf("orientation_hint" to "clockwise"),
		)
		val result = StructuralNormalizer.rotate(
			listOf(sample),
			LocalBlockBounds(LocalBlockPosition(0, 0, 0), LocalBlockPosition(1, 0, 2)),
			RoomRotation.DEGREES_90,
			policy,
		)

		assertEquals("clockwise", result.samples.single().properties["orientation_hint"])
		assertEquals(RotationIssueReason.UNSUPPORTED_DIRECTIONAL_PROPERTY, result.issues.single().reason)
		assertEquals(result.samples.single().position, result.issues.single().position)
	}

	@Test
	fun `symmetric structure has equivalent rotated view`() {
		val squareBounds = LocalBlockBounds(LocalBlockPosition(0, 0, 0), LocalBlockPosition(2, 0, 2))
		val samples = listOf(
			StructuralSample(LocalBlockPosition(0, 0, 0), "minecraft:stone"),
			StructuralSample(LocalBlockPosition(2, 0, 0), "minecraft:stone"),
			StructuralSample(LocalBlockPosition(0, 0, 2), "minecraft:stone"),
			StructuralSample(LocalBlockPosition(2, 0, 2), "minecraft:stone"),
		)
		val rotated = StructuralNormalizer.rotate(samples, squareBounds, RoomRotation.DEGREES_90, FingerprintPolicy.DEFAULT)

		assertEquals(StructuralNormalizer.normalizeSamples(samples), rotated.samples)
	}
}
