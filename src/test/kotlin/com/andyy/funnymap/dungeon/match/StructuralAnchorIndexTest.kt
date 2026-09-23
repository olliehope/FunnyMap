package com.andyy.funnymap.dungeon.match

import com.andyy.funnymap.dungeon.model.GridPosition
import com.andyy.funnymap.dungeon.model.RoomFootprint
import com.andyy.funnymap.dungeon.model.RoomId
import com.andyy.funnymap.dungeon.model.RoomType
import com.andyy.funnymap.dungeon.room.FingerprintPolicy
import com.andyy.funnymap.dungeon.room.LocalBlockBounds
import com.andyy.funnymap.dungeon.room.LocalBlockPosition
import com.andyy.funnymap.dungeon.room.RoomDatabase
import com.andyy.funnymap.dungeon.room.RoomDefinition
import com.andyy.funnymap.dungeon.room.RoomFingerprint
import com.andyy.funnymap.dungeon.room.StructuralSample
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StructuralAnchorIndexTest {
	@Test
	fun `rare structural signatures locate candidate local positions`() {
		val database = database(
			listOf(
				StructuralSample(LocalBlockPosition(0, 0, 0), "minecraft:stone"),
				StructuralSample(LocalBlockPosition(1, 0, 0), "minecraft:oak_stairs", mapOf("facing" to "north")),
			),
		)
		val anchors = StructuralAnchorIndex.build(database.candidateIndex, maximumSignatureReferences = 8)

		val north = anchors.referencesFor("minecraft:oak_stairs", mapOf("facing" to "north"), 16)
		val south = anchors.referencesFor("minecraft:oak_stairs", mapOf("facing" to "south"), 16)

		assertTrue(anchors.containsBlock("minecraft:oak_stairs"))
		assertEquals(LocalBlockPosition(1, 0, 0), north.first().localPosition)
		assertTrue(north.all { it.requiredProperties["facing"] == "north" })
		assertTrue(south.all { it.requiredProperties["facing"] == "south" })
	}

	@Test
	fun `high fanout signatures are excluded from world-origin discovery`() {
		val samples = (0 until 8).map { StructuralSample(LocalBlockPosition(it, 0, 0), "minecraft:stone") }
		val database = database(samples)
		val anchors = StructuralAnchorIndex.build(database.candidateIndex, maximumSignatureReferences = 4)

		assertFalse(anchors.containsBlock("minecraft:stone"))
	}

	private fun database(samples: List<StructuralSample>): RoomDatabase {
		val bounds = LocalBlockBounds(LocalBlockPosition(0, 0, 0), LocalBlockPosition(7, 0, 0))
		val definition = RoomDefinition(
			RoomId("rooms/anchor"), "Anchor", RoomType.NORMAL,
			RoomFootprint.of(GridPosition(0, 0)), 0, 0,
			listOf(RoomFingerprint.create("base", bounds, samples = samples, policyVersion = POLICY.version)),
		)
		return RoomDatabase.create(
			fingerprintPolicyVersion = POLICY.version,
			definitions = listOf(definition),
			policies = mapOf(POLICY.version to POLICY),
		)
	}

	private companion object {
		val POLICY = FingerprintPolicy(
			"anchor-test-v1",
			setOf("minecraft"),
			participatingProperties = setOf("facing"),
			directionalProperties = mapOf("facing" to com.andyy.funnymap.dungeon.room.DirectionalPropertyKind.CARDINAL_FACING),
			minimumSamples = 1,
		)
	}
}
