package com.andyy.funnymap.dungeon.model

import java.util.Collections

data class GridPosition(
	val column: Int,
	val row: Int,
) : Comparable<GridPosition> {
	override fun compareTo(other: GridPosition): Int =
		compareValuesBy(this, other, GridPosition::row, GridPosition::column)

	fun offset(columns: Int, rows: Int): GridPosition = GridPosition(column + columns, row + rows)

	fun isOrthogonallyAdjacentTo(other: GridPosition): Boolean =
		kotlin.math.abs(column - other.column) + kotlin.math.abs(row - other.row) == 1
}

data class GridBounds(
	val width: Int = DEFAULT_SIZE,
	val height: Int = DEFAULT_SIZE,
) {
	init {
		require(width > 0) { "Grid width must be positive" }
		require(height > 0) { "Grid height must be positive" }
	}

	operator fun contains(position: GridPosition): Boolean =
		position.column in 0 until width && position.row in 0 until height

	companion object {
		const val DEFAULT_SIZE = 6
	}
}

enum class RoomShape {
	ONE_BY_ONE,
	ONE_BY_TWO,
	ONE_BY_THREE,
	ONE_BY_FOUR,
	TWO_BY_TWO,
	L_SHAPED,
}

/** A validated set of logical room cells. Coordinates are absolute grid coordinates. */
class RoomFootprint private constructor(
	val cells: Set<GridPosition>,
	val localCells: Set<GridPosition>,
	val anchor: GridPosition,
	val shape: RoomShape,
	val width: Int,
	val height: Int,
) {
	val cellCount: Int
		get() = cells.size

	override fun equals(other: Any?): Boolean = other is RoomFootprint && cells == other.cells

	override fun hashCode(): Int = cells.hashCode()

	override fun toString(): String = "RoomFootprint(shape=$shape, cells=$cells)"

	companion object {
		fun of(vararg cells: GridPosition): RoomFootprint = of(cells.asList())

		fun of(cells: Collection<GridPosition>): RoomFootprint {
			require(cells.isNotEmpty()) { "A room footprint must contain at least one cell" }
			require(cells.size == cells.toSet().size) { "A room footprint cannot contain duplicate cells" }

			val sortedCells = cells.sorted()
			val minColumn = sortedCells.minOf(GridPosition::column)
			val maxColumn = sortedCells.maxOf(GridPosition::column)
			val minRow = sortedCells.minOf(GridPosition::row)
			val maxRow = sortedCells.maxOf(GridPosition::row)
			val width = maxColumn - minColumn + 1
			val height = maxRow - minRow + 1
			val shape = classify(sortedCells.size, width, height)

			val stableCells = immutableSet(sortedCells)
			val localCells = immutableSet(
				sortedCells.map { GridPosition(it.column - minColumn, it.row - minRow) }.sorted(),
			)

			return RoomFootprint(
				cells = stableCells,
				localCells = localCells,
				anchor = GridPosition(minColumn, minRow),
				shape = shape,
				width = width,
				height = height,
			)
		}

		private fun classify(cellCount: Int, width: Int, height: Int): RoomShape = when {
			cellCount == 1 && width == 1 && height == 1 -> RoomShape.ONE_BY_ONE
			cellCount == 2 && isLine(width, height, 2) -> RoomShape.ONE_BY_TWO
			cellCount == 3 && isLine(width, height, 3) -> RoomShape.ONE_BY_THREE
			cellCount == 4 && isLine(width, height, 4) -> RoomShape.ONE_BY_FOUR
			cellCount == 4 && width == 2 && height == 2 -> RoomShape.TWO_BY_TWO
			cellCount == 3 && width == 2 && height == 2 -> RoomShape.L_SHAPED
			else -> throw IllegalArgumentException(
				"Unsupported room footprint with $cellCount cells in a ${width}x$height bounding box",
			)
		}

		private fun isLine(width: Int, height: Int, length: Int): Boolean =
			(width == length && height == 1) || (width == 1 && height == length)

		private fun <T> immutableSet(values: Collection<T>): Set<T> =
			Collections.unmodifiableSet(LinkedHashSet(values))
	}
}
