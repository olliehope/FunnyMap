package com.andyy.funnymap.dungeon.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RoomFootprintTest {
	@Test
	fun `classifies every supported footprint`() {
		val footprints = mapOf(
			RoomShape.ONE_BY_ONE to listOf(position(0, 0)),
			RoomShape.ONE_BY_TWO to listOf(position(0, 0), position(0, 1)),
			RoomShape.ONE_BY_THREE to listOf(position(0, 0), position(1, 0), position(2, 0)),
			RoomShape.ONE_BY_FOUR to listOf(position(0, 0), position(0, 1), position(0, 2), position(0, 3)),
			RoomShape.TWO_BY_TWO to listOf(position(0, 0), position(1, 0), position(0, 1), position(1, 1)),
			RoomShape.L_SHAPED to listOf(position(0, 0), position(1, 0), position(1, 1)),
		)

		for ((expectedShape, cells) in footprints) {
			assertEquals(expectedShape, RoomFootprint.of(cells).shape)
		}
	}

	@Test
	fun `accepts every rotation of the L footprint`() {
		val square = setOf(position(0, 0), position(1, 0), position(0, 1), position(1, 1))

		for (missingCell in square) {
			assertEquals(RoomShape.L_SHAPED, RoomFootprint.of(square - missingCell).shape)
		}
	}

	@Test
	fun `normalizes room cells relative to their top-left anchor`() {
		val footprint = RoomFootprint.of(position(4, 3), position(4, 4), position(3, 4))

		assertEquals(position(3, 3), footprint.anchor)
		assertEquals(
			setOf(position(1, 0), position(1, 1), position(0, 1)),
			footprint.localCells,
		)
	}

	@Test
	fun `rejects duplicate disconnected and unsupported shapes`() {
		assertFailsWith<IllegalArgumentException> {
			RoomFootprint.of(position(0, 0), position(0, 0))
		}
		assertFailsWith<IllegalArgumentException> {
			RoomFootprint.of(position(0, 0), position(2, 0))
		}
		assertFailsWith<IllegalArgumentException> {
			RoomFootprint.of(
				position(0, 0),
				position(1, 0),
				position(2, 0),
				position(0, 1),
				position(1, 1),
			)
		}
	}

	@Test
	fun `copies mutable footprint input`() {
		val source = mutableListOf(position(2, 2), position(3, 2))
		val footprint = RoomFootprint.of(source)

		source.clear()

		assertEquals(setOf(position(2, 2), position(3, 2)), footprint.cells)
	}

	private fun position(column: Int, row: Int) = GridPosition(column, row)
}
