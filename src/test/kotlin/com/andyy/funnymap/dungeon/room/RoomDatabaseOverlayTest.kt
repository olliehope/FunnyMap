package com.andyy.funnymap.dungeon.room

import com.andyy.funnymap.dungeon.model.GridPosition
import com.andyy.funnymap.dungeon.model.RoomFootprint
import com.andyy.funnymap.dungeon.model.RoomId
import com.andyy.funnymap.dungeon.model.RoomType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RoomDatabaseOverlayTest {
	@Test
	fun `overlay adds independently validated definitions and rebuilds the candidate index`() {
		val bundled = RoomDatabase.create(definitions = listOf(definition("catacombs/bundled")))
		val overlay = RoomDatabase.create(definitions = listOf(definition("catacombs/overlay")))

		val merged = RoomDatabaseOverlay.merge(bundled, overlay)

		assertEquals(listOf("catacombs/bundled", "catacombs/overlay"), merged.definitions.map { it.id.value })
		assertEquals(8, merged.candidateIndex.views.size)
	}

	@Test
	fun `overlay rejects duplicate room ids explicitly`() {
		val bundled = RoomDatabase.create(definitions = listOf(definition("catacombs/duplicate")))
		val overlay = RoomDatabase.create(definitions = listOf(definition("catacombs/duplicate")))

		val error = assertFailsWith<RoomDatabaseValidationException> {
			RoomDatabaseOverlay.merge(bundled, overlay)
		}

		assertTrue(error.message.orEmpty().contains("Duplicate room id"))
	}

	private fun definition(id: String): RoomDefinition {
		val bounds = LocalBlockBounds(LocalBlockPosition(0, 0, 0), LocalBlockPosition(3, 0, 1))
		val samples = buildList {
			for (z in 0..1) for (x in 0..3) {
				add(StructuralSample(LocalBlockPosition(x, 0, z), "minecraft:stone"))
			}
		}
		return RoomDefinition(
			id = RoomId(id),
			displayName = id.substringAfter('/'),
			type = RoomType.NORMAL,
			footprint = RoomFootprint.of(GridPosition(0, 0)),
			secretCount = 0,
			cryptCount = 0,
			fingerprints = listOf(
				RoomFingerprint.create(
					id = "base",
					bounds = bounds,
					samples = samples,
					policyVersion = FingerprintPolicy.DEFAULT.version,
				),
			),
		)
	}
}
