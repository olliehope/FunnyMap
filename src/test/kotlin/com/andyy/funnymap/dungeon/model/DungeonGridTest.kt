package com.andyy.funnymap.dungeon.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class DungeonGridTest {
	@Test
	fun `uses six by six bounds by default`() {
		assertEquals(GridBounds(width = 6, height = 6), DungeonGrid.EMPTY.bounds)
	}

	@Test
	fun `indexes every cell of a multi-cell room`() {
		val room = room(
			id = "long-room",
			cells = listOf(position(1, 2), position(2, 2), position(3, 2)),
		)
		val grid = DungeonGrid(rooms = listOf(room))

		assertEquals(3, grid.roomCells.size)
		assertEquals(room, grid.roomAt(position(2, 2)))
		assertEquals(position(1, 0), grid.roomCellAt(position(2, 2))?.footprintPosition)
		assertNull(grid.roomAt(position(2, 3)))
	}

	@Test
	fun `rejects overlapping rooms and cells outside configurable bounds`() {
		val first = room("first", listOf(position(0, 0), position(1, 0)))
		val second = room("second", listOf(position(1, 0)))

		assertFailsWith<IllegalArgumentException> {
			DungeonGrid(rooms = listOf(first, second))
		}
		assertFailsWith<IllegalArgumentException> {
			DungeonGrid(
				bounds = GridBounds(width = 2, height = 2),
				rooms = listOf(room("outside", listOf(position(2, 1)))),
			)
		}
	}

	@Test
	fun `canonicalizes connection endpoints regardless of input order`() {
		val left = position(1, 1)
		val right = position(2, 1)
		val forward = ConnectionCell.between(left, right, ConnectionType.NORMAL_DOOR, ConnectionState.OPEN)
		val reverse = ConnectionCell.between(right, left, ConnectionType.NORMAL_DOOR, ConnectionState.OPEN)

		assertEquals(forward, reverse)
		assertEquals(left, reverse.first)
		assertEquals(right, reverse.second)
	}

	@Test
	fun `rejects invalid and duplicate connections`() {
		assertFailsWith<IllegalArgumentException> {
			ConnectionCell.between(position(0, 0), position(2, 0))
		}

		val rooms = listOf(
			room("left", listOf(position(0, 0))),
			room("right", listOf(position(1, 0))),
		)
		val first = ConnectionCell.between(position(0, 0), position(1, 0), ConnectionType.NORMAL_DOOR)
		val duplicate = ConnectionCell.between(position(1, 0), position(0, 0), ConnectionType.WITHER_DOOR)

		assertFailsWith<IllegalArgumentException> {
			DungeonGrid(rooms = rooms, connections = listOf(first, duplicate))
		}
	}

	@Test
	fun `rejects connections without room cells at both endpoints`() {
		val connection = ConnectionCell.between(position(0, 0), position(1, 0))

		assertFailsWith<IllegalArgumentException> {
			DungeonGrid(
				rooms = listOf(room("only", listOf(position(0, 0)))),
				connections = listOf(connection),
			)
		}
	}

	@Test
	fun `rejects explicit connections inside a multi-cell footprint`() {
		val multiCellRoom = room("joined", listOf(position(0, 0), position(1, 0)))
		val internalEdge = ConnectionCell.between(
			position(0, 0),
			position(1, 0),
			ConnectionType.OPEN_PASSAGE,
		)

		assertFailsWith<IllegalArgumentException> {
			DungeonGrid(rooms = listOf(multiCellRoom), connections = listOf(internalEdge))
		}
	}

	@Test
	fun `copies mutable room and connection inputs`() {
		val roomSource = mutableListOf(
			room("left", listOf(position(0, 0))),
			room("right", listOf(position(1, 0))),
		)
		val connectionSource = mutableListOf(ConnectionCell.between(position(0, 0), position(1, 0)))
		val grid = DungeonGrid(rooms = roomSource, connections = connectionSource)

		roomSource.clear()
		connectionSource.clear()

		assertEquals(2, grid.roomsById.size)
		assertEquals(1, grid.connections.size)
		assertNotNull(grid.roomCellAt(position(0, 0)))
	}

	private fun room(id: String, cells: Collection<GridPosition>) = DungeonRoom(
		id = RoomId(id),
		footprint = RoomFootprint.of(cells),
	)

	private fun position(column: Int, row: Int) = GridPosition(column, row)
}
