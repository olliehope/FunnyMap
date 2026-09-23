package com.andyy.funnymap.dungeon.room

import com.andyy.funnymap.dungeon.model.GridPosition
import com.andyy.funnymap.dungeon.model.RoomFootprint
import com.andyy.funnymap.dungeon.model.RoomRotation

data class LocalBlockPosition(
	val x: Int,
	val y: Int,
	val z: Int,
) : Comparable<LocalBlockPosition> {
	override fun compareTo(other: LocalBlockPosition): Int =
		compareValuesBy(this, other, LocalBlockPosition::x, LocalBlockPosition::y, LocalBlockPosition::z)
}

data class LocalBlockBounds(
	val min: LocalBlockPosition,
	val max: LocalBlockPosition,
) {
	init {
		require(min.x <= max.x && min.y <= max.y && min.z <= max.z) {
			"Local bounds minimum must not exceed maximum"
		}
	}

	val width: Int
		get() = max.x - min.x + 1
	val height: Int
		get() = max.y - min.y + 1
	val depth: Int
		get() = max.z - min.z + 1
	val volume: Long
		get() = width.toLong() * height.toLong() * depth.toLong()

	operator fun contains(position: LocalBlockPosition): Boolean =
		position.x in min.x..max.x && position.y in min.y..max.y && position.z in min.z..max.z

	fun normalizedHorizontally(): LocalBlockBounds = LocalBlockBounds(
		min = LocalBlockPosition(0, min.y, 0),
		max = LocalBlockPosition(width - 1, max.y, depth - 1),
	)
}

object RoomGeometry {
	fun normalizeFootprint(footprint: RoomFootprint): RoomFootprint =
		RoomFootprint.of(footprint.localCells)

	fun rotateFootprint(footprint: RoomFootprint, rotation: RoomRotation): RoomFootprint {
		val normalized = normalizeFootprint(footprint)
		val maxColumn = normalized.width - 1
		val maxRow = normalized.height - 1
		val rotated = normalized.localCells.map { cell ->
			when (rotation) {
				RoomRotation.DEGREES_0 -> cell
				RoomRotation.DEGREES_90 -> GridPosition(maxRow - cell.row, cell.column)
				RoomRotation.DEGREES_180 -> GridPosition(maxColumn - cell.column, maxRow - cell.row)
				RoomRotation.DEGREES_270 -> GridPosition(cell.row, maxColumn - cell.column)
			}
		}
		return RoomFootprint.of(rotated)
	}
}
