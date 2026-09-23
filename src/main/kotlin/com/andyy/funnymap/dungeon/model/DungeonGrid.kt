package com.andyy.funnymap.dungeon.model

import java.util.Collections

/** Immutable logical dungeon topology. This type deliberately has no Minecraft dependencies. */
class DungeonGrid(
	val bounds: GridBounds = GridBounds(),
	rooms: Collection<DungeonRoom> = emptyList(),
	connections: Collection<ConnectionCell> = emptyList(),
) {
	val roomsById: Map<RoomId, DungeonRoom>
	val roomCells: Map<GridPosition, RoomCell>
	val connections: Set<ConnectionCell>
	val cells: Set<DungeonCell>

	init {
		val mutableRooms = LinkedHashMap<RoomId, DungeonRoom>()
		val mutableRoomCells = LinkedHashMap<GridPosition, RoomCell>()

		for (room in rooms) {
			require(mutableRooms.put(room.id, room) == null) { "Duplicate room id: ${room.id}" }
			for (position in room.footprint.cells) {
				require(position in bounds) { "Room ${room.id} has cell outside grid bounds: $position" }
				val localPosition = GridPosition(
					column = position.column - room.footprint.anchor.column,
					row = position.row - room.footprint.anchor.row,
				)
				val cell = RoomCell(position, room.id, localPosition)
				val previous = mutableRoomCells.put(position, cell)
				require(previous == null) {
					"Rooms ${previous?.roomId} and ${room.id} overlap at $position"
				}
			}
		}

		val mutableConnections = LinkedHashSet<ConnectionCell>()
		val endpoints = HashSet<Pair<GridPosition, GridPosition>>()
		for (connection in connections) {
			require(connection.first in bounds && connection.second in bounds) {
				"Connection is outside grid bounds: $connection"
			}
			require(mutableRoomCells.containsKey(connection.first)) {
				"Connection endpoint has no room cell: ${connection.first}"
			}
			require(mutableRoomCells.containsKey(connection.second)) {
				"Connection endpoint has no room cell: ${connection.second}"
			}
			require(
				mutableRoomCells.getValue(connection.first).roomId !=
					mutableRoomCells.getValue(connection.second).roomId,
			) {
				"A connection cannot join cells inside the same room: ${connection.first} and ${connection.second}"
			}
			require(endpoints.add(connection.first to connection.second)) {
				"Duplicate connection between ${connection.first} and ${connection.second}"
			}
			mutableConnections.add(connection)
		}

		roomsById = Collections.unmodifiableMap(mutableRooms)
		roomCells = Collections.unmodifiableMap(mutableRoomCells)
		this.connections = Collections.unmodifiableSet(mutableConnections)
		cells = Collections.unmodifiableSet(
			LinkedHashSet<DungeonCell>(mutableRoomCells.size + mutableConnections.size).apply {
				addAll(mutableRoomCells.values)
				addAll(mutableConnections)
			},
		)
	}

	fun roomCellAt(position: GridPosition): RoomCell? = roomCells[position]

	fun roomAt(position: GridPosition): DungeonRoom? = roomCellAt(position)?.let { roomsById[it.roomId] }

	fun connectionsAt(position: GridPosition): List<ConnectionCell> = connections
		.filter { it.first == position || it.second == position }

	companion object {
		val EMPTY = DungeonGrid()
	}
}
