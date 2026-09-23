package com.andyy.funnymap.dungeon.match

import com.andyy.funnymap.dungeon.model.GridPosition
import com.andyy.funnymap.dungeon.model.RoomFootprint
import com.andyy.funnymap.dungeon.model.RoomId
import com.andyy.funnymap.dungeon.model.RoomRotation
import com.andyy.funnymap.dungeon.model.RoomType
import com.andyy.funnymap.dungeon.room.FingerprintPolicy
import com.andyy.funnymap.dungeon.room.GameDataVersion
import com.andyy.funnymap.dungeon.room.LocalBlockBounds
import com.andyy.funnymap.dungeon.room.LocalBlockPosition
import com.andyy.funnymap.dungeon.room.ObservationCoverage
import com.andyy.funnymap.dungeon.room.ObservationProvenance
import com.andyy.funnymap.dungeon.room.ObservationSource
import com.andyy.funnymap.dungeon.room.RoomDatabase
import com.andyy.funnymap.dungeon.room.RoomDefinition
import com.andyy.funnymap.dungeon.room.RoomFingerprint
import com.andyy.funnymap.dungeon.room.RoomLocalDatum
import com.andyy.funnymap.dungeon.room.RoomObservation
import com.andyy.funnymap.dungeon.room.StructuralNormalizer
import com.andyy.funnymap.dungeon.room.StructuralSample
import com.andyy.funnymap.dungeon.room.WorldCoordinate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RoomCandidateIndexTest {
	private val policy = FingerprintPolicy(
		version = "test-policy-v1",
		allowedNamespaces = setOf("minecraft"),
		participatingProperties = setOf("facing"),
		directionalProperties = mapOf(
			"facing" to com.andyy.funnymap.dungeon.room.DirectionalPropertyKind.CARDINAL_FACING,
		),
		minimumSamples = 1,
	)

	@Test
	fun `partial observation narrows candidates without declaring a winner`() {
		val bounds = bounds(3, 2)
		val first = definition(
			"synthetic/first",
			bounds,
			listOf(sample(0, 0, "minecraft:stone_bricks"), sample(2, 1, "minecraft:oak_planks")),
		)
		val second = definition(
			"synthetic/second",
			bounds,
			listOf(sample(0, 0, "minecraft:deepslate_tiles"), sample(2, 1, "minecraft:oak_planks")),
		)
		val index = index(first, second)
		val observation = observation(bounds, listOf(sample(0, 0, "minecraft:stone_bricks")))

		val shortlist = index.shortlist(observation)

		assertEquals("synthetic/first", shortlist.hits.first().candidate.key.roomId.value)
		assertEquals(1, shortlist.hits.first().matchedTokenCount)
		assertTrue(shortlist.hits.all { it.candidate.key.roomId.value != "synthetic/second" })
	}

	@Test
	fun `rotated view indexes rotated coordinates and facing`() {
		val bounds = bounds(3, 2)
		val canonical = sample(0, 0, "minecraft:oak_stairs", mapOf("facing" to "north"))
		val definition = definition("synthetic/rotated", bounds, listOf(canonical))
		val rotated = StructuralNormalizer.rotate(
			listOf(canonical),
			bounds,
			RoomRotation.DEGREES_90,
			policy,
		)
		val observation = observation(
			bounds = rotated.bounds,
			samples = rotated.samples,
			footprint = StructuralNormalizer.rotateFootprint(definition.footprint, RoomRotation.DEGREES_90),
		)

		val shortlist = index(definition).shortlist(observation)

		assertEquals(RoomRotation.DEGREES_90, shortlist.hits.first().candidate.key.rotation)
		assertEquals("east", shortlist.hits.first().candidate.samples.single().properties["facing"])
	}

	@Test
	fun `symmetric evidence preserves ambiguous rotation views`() {
		val bounds = bounds(3, 3)
		val definition = definition(
			"synthetic/symmetric",
			bounds,
			listOf(sample(1, 1, "minecraft:polished_andesite")),
		)

		val hits = index(definition).shortlist(
			observation(bounds, listOf(sample(1, 1, "minecraft:polished_andesite"))),
		).hits

		assertEquals(RoomRotation.entries.toSet(), hits.map { it.candidate.key.rotation }.toSet())
	}

	@Test
	fun `logical footprint filters otherwise identical tokens`() {
		val bounds = bounds(3, 3)
		val oneByOne = definition(
			"synthetic/single",
			bounds,
			listOf(sample(1, 1, "minecraft:bricks")),
		)
		val longRoom = definition(
			"synthetic/long",
			bounds,
			listOf(sample(1, 1, "minecraft:bricks")),
			RoomFootprint.of(GridPosition(0, 0), GridPosition(1, 0)),
		)

		val hits = index(oneByOne, longRoom).shortlist(
			observation(bounds, listOf(sample(1, 1, "minecraft:bricks"))),
		).hits

		assertTrue(hits.all { it.candidate.key.roomId.value == "synthetic/single" })
	}

	private fun index(vararg definitions: RoomDefinition): RoomCandidateIndex {
		val database = RoomDatabase.create(
			fingerprintPolicyVersion = policy.version,
			definitions = definitions.asList(),
			policies = mapOf(policy.version to policy),
		)
		return RoomCandidateIndex.build(database, mapOf(policy.version to policy))
	}

	private fun definition(
		id: String,
		bounds: LocalBlockBounds,
		samples: List<StructuralSample>,
		footprint: RoomFootprint = RoomFootprint.of(GridPosition(0, 0)),
	): RoomDefinition = RoomDefinition(
		id = RoomId(id),
		displayName = id.substringAfterLast('/'),
		type = RoomType.NORMAL,
		footprint = footprint,
		secretCount = 0,
		cryptCount = 0,
		fingerprints = listOf(
			RoomFingerprint.create(
				id = "base",
				bounds = bounds,
				samples = samples,
				policyVersion = policy.version,
			),
		),
	)

	private fun observation(
		bounds: LocalBlockBounds,
		samples: List<StructuralSample>,
		footprint: RoomFootprint = RoomFootprint.of(GridPosition(0, 0)),
	): RoomObservation = RoomObservation(
		observationId = "observation",
		worldSessionGeneration = 1,
		revision = 1,
		gameDataVersion = GameDataVersion("test"),
		fingerprintPolicyVersion = policy.version,
		footprint = footprint,
		bounds = bounds,
		datum = RoomLocalDatum(WorldCoordinate(0, 0, 0)),
		samples = samples,
		coverage = ObservationCoverage.complete(bounds),
		provenance = ObservationProvenance(ObservationSource.HEADLESS_TEST),
	)

	private fun bounds(width: Int, depth: Int) = LocalBlockBounds(
		LocalBlockPosition(0, 0, 0),
		LocalBlockPosition(width - 1, 0, depth - 1),
	)

	private fun sample(
		x: Int,
		z: Int,
		block: String,
		properties: Map<String, String> = emptyMap(),
	) = StructuralSample(LocalBlockPosition(x, 0, z), block, properties)
}
