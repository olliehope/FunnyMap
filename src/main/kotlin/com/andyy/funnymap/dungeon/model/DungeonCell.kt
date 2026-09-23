package com.andyy.funnymap.dungeon.model

sealed interface DungeonCell

data class RoomCell(
	val position: GridPosition,
	val roomId: RoomId,
	val footprintPosition: GridPosition,
) : DungeonCell

enum class ConnectionType {
	OPEN_PASSAGE,
	NORMAL_DOOR,
	WITHER_DOOR,
	BLOOD_DOOR,
	ENTRANCE_DOOR,
	UNKNOWN,
}

enum class ConnectionState {
	UNKNOWN,
	OPEN,
	CLOSED,
}

/** A connection between two orthogonally adjacent room cells, stored in canonical endpoint order. */
class ConnectionCell private constructor(
	val first: GridPosition,
	val second: GridPosition,
	val type: ConnectionType,
	val state: ConnectionState,
) : DungeonCell {
	init {
		require(first < second) { "Connection endpoints must use canonical order" }
		require(first.isOrthogonallyAdjacentTo(second)) {
			"Connection endpoints must be orthogonally adjacent: $first and $second"
		}
	}

	override fun equals(other: Any?): Boolean = other is ConnectionCell &&
		first == other.first &&
		second == other.second &&
		type == other.type &&
		state == other.state

	override fun hashCode(): Int {
		var result = first.hashCode()
		result = 31 * result + second.hashCode()
		result = 31 * result + type.hashCode()
		result = 31 * result + state.hashCode()
		return result
	}

	override fun toString(): String =
		"ConnectionCell(first=$first, second=$second, type=$type, state=$state)"

	companion object {
		fun between(
			first: GridPosition,
			second: GridPosition,
			type: ConnectionType = ConnectionType.UNKNOWN,
			state: ConnectionState = ConnectionState.UNKNOWN,
		): ConnectionCell {
			val (canonicalFirst, canonicalSecond) = if (first < second) {
				first to second
			} else {
				second to first
			}
			return ConnectionCell(canonicalFirst, canonicalSecond, type, state)
		}
	}
}
