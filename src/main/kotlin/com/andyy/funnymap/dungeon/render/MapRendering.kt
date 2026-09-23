package com.andyy.funnymap.dungeon.render

import java.util.Collections

data class MapLayoutSpec(
	val originX: Int = 8,
	val originY: Int = 8,
	val padding: Int = 6,
	val headerHeight: Int = 14,
	val tileSize: Int = 20,
	val gap: Int = 4,
	val connectionThickness: Int = 6,
	val debugFooterHeight: Int = 12,
) {
	init {
		require(padding >= 0) { "Padding must not be negative" }
		require(headerHeight > 0) { "Header height must be positive" }
		require(tileSize >= 8) { "Tile size must be at least 8" }
		require(gap >= 1) { "Gap must be positive" }
		require(connectionThickness in 1..tileSize) {
			"Connection thickness must fit within a tile"
		}
		require(debugFooterHeight > 0) { "Debug footer height must be positive" }
	}

	val stride: Int
		get() = tileSize + gap
}

fun interface TextMetrics {
	fun width(text: String): Int
}

sealed interface MapRenderCommand {
	data class FillRect(
		val x: Int,
		val y: Int,
		val width: Int,
		val height: Int,
		val color: Int,
	) : MapRenderCommand {
		init {
			require(width > 0 && height > 0) { "Rectangle dimensions must be positive" }
		}
	}

	data class OutlineRect(
		val x: Int,
		val y: Int,
		val width: Int,
		val height: Int,
		val color: Int,
	) : MapRenderCommand {
		init {
			require(width > 0 && height > 0) { "Rectangle dimensions must be positive" }
		}
	}

	data class Text(
		val value: String,
		val x: Int,
		val y: Int,
		val color: Int,
	) : MapRenderCommand
}

class MapRenderPlan(
	val x: Int,
	val y: Int,
	val width: Int,
	val height: Int,
	commands: Collection<MapRenderCommand>,
) {
	val commands: List<MapRenderCommand> = Collections.unmodifiableList(ArrayList(commands))

	init {
		require(width > 0 && height > 0) { "Plan dimensions must be positive" }
	}
}

object DungeonMapPalette {
	const val PANEL = 0xE6121518.toInt()
	const val PANEL_BORDER = 0xFF4B535A.toInt()
	const val MAP_WELL = 0xFF1B2024.toInt()
	const val GRID_BORDER = 0xFF252C31.toInt()
	const val TEXT = 0xFFF4F7F8.toInt()
	const val SECONDARY_TEXT = 0xFFCBD2D6.toInt()
	const val UNKNOWN = 0xFF6F7780.toInt()
	const val NORMAL = 0xFF9C755D.toInt()
	const val PUZZLE = 0xFFAF70D0.toInt()
	const val TRAP = 0xFFE08A4E.toInt()
	const val ENTRANCE = 0xFF55A66F.toInt()
	const val FAIRY = 0xFFE58AB5.toInt()
	const val BLOOD = 0xFFD64C5A.toInt()
	const val MINIBOSS = 0xFFF1C75B.toInt()
	const val BOSS = 0xFF873A47.toInt()
	const val OPEN_PASSAGE = 0xFF8E989E.toInt()
	const val NORMAL_DOOR = 0xFF7A5238.toInt()
	const val WITHER_DOOR = 0xFF202327.toInt()
	const val BLOOD_DOOR = 0xFFB52D3D.toInt()
	const val ENTRANCE_DOOR = 0xFF55A66F.toInt()
	const val COMPLETED = 0xFF5ED27A.toInt()
	const val CLEARED = 0xFFF2F5F6.toInt()
	const val DISCOVERED = 0xFFF1C75B.toInt()
	const val FAILED = 0xFFE15B64.toInt()
}
