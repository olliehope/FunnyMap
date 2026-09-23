package com.andyy.funnymap.dungeon.room

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class FingerprintPolicyTest {
	@Test
	fun `policy drops nonparticipating properties without discarding stable block`() {
		val sample = StructuralSample(
			LocalBlockPosition(1, 2, 3),
			"minecraft:oak_stairs",
			mapOf("facing" to "north", "waterlogged" to "true"),
		)

		val accepted = assertIs<PolicyDecision.Accepted>(FingerprintPolicy.DEFAULT.apply(sample))

		assertEquals(mapOf("facing" to "north"), accepted.sample.properties)
		assertEquals(setOf("waterlogged"), accepted.droppedProperties)
	}

	@Test
	fun `policy excludes runtime-variable blocks and foreign namespaces`() {
		val chest = StructuralSample(LocalBlockPosition(0, 0, 0), "minecraft:chest")
		val foreign = StructuralSample(LocalBlockPosition(0, 0, 0), "example:stone")

		assertEquals(
			PolicyExclusionReason.RUNTIME_VARIABLE_BLOCK,
			assertIs<PolicyDecision.Excluded>(FingerprintPolicy.DEFAULT.apply(chest)).reason,
		)
		assertEquals(
			PolicyExclusionReason.BLOCK_NAMESPACE_NOT_ALLOWED,
			assertIs<PolicyDecision.Excluded>(FingerprintPolicy.DEFAULT.apply(foreign)).reason,
		)
	}
}
