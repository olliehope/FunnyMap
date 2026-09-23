package com.andyy.funnymap.dungeon.render

import com.andyy.funnymap.dungeon.model.ConnectionCell
import com.andyy.funnymap.dungeon.model.ConnectionState
import com.andyy.funnymap.dungeon.model.ConnectionType
import com.andyy.funnymap.dungeon.model.DungeonRoom
import com.andyy.funnymap.dungeon.model.DungeonSnapshot
import com.andyy.funnymap.dungeon.model.GridPosition
import com.andyy.funnymap.dungeon.model.RecognitionUnknownReason
import com.andyy.funnymap.dungeon.model.RoomCompletion
import com.andyy.funnymap.dungeon.model.RoomOrientation
import com.andyy.funnymap.dungeon.model.RoomRecognition
import com.andyy.funnymap.dungeon.model.RoomRotation
import com.andyy.funnymap.dungeon.model.RoomType
import com.andyy.funnymap.dungeon.render.DungeonMapPalette.BLOOD
import com.andyy.funnymap.dungeon.render.DungeonMapPalette.BLOOD_DOOR
import com.andyy.funnymap.dungeon.render.DungeonMapPalette.BOSS
import com.andyy.funnymap.dungeon.render.DungeonMapPalette.CLEARED
import com.andyy.funnymap.dungeon.render.DungeonMapPalette.COMPLETED
import com.andyy.funnymap.dungeon.render.DungeonMapPalette.DISCOVERED
import com.andyy.funnymap.dungeon.render.DungeonMapPalette.ENTRANCE
import com.andyy.funnymap.dungeon.render.DungeonMapPalette.ENTRANCE_DOOR
import com.andyy.funnymap.dungeon.render.DungeonMapPalette.FAILED
import com.andyy.funnymap.dungeon.render.DungeonMapPalette.FAIRY
import com.andyy.funnymap.dungeon.render.DungeonMapPalette.GRID_BORDER
import com.andyy.funnymap.dungeon.render.DungeonMapPalette.MAP_WELL
import com.andyy.funnymap.dungeon.render.DungeonMapPalette.MINIBOSS
import com.andyy.funnymap.dungeon.render.DungeonMapPalette.NORMAL
import com.andyy.funnymap.dungeon.render.DungeonMapPalette.NORMAL_DOOR
import com.andyy.funnymap.dungeon.render.DungeonMapPalette.OPEN_PASSAGE
import com.andyy.funnymap.dungeon.render.DungeonMapPalette.PANEL
import com.andyy.funnymap.dungeon.render.DungeonMapPalette.PANEL_BORDER
import com.andyy.funnymap.dungeon.render.DungeonMapPalette.PUZZLE
import com.andyy.funnymap.dungeon.render.DungeonMapPalette.SECONDARY_TEXT
import com.andyy.funnymap.dungeon.render.DungeonMapPalette.TEXT
import com.andyy.funnymap.dungeon.render.DungeonMapPalette.TRAP
import com.andyy.funnymap.dungeon.render.DungeonMapPalette.UNKNOWN
import com.andyy.funnymap.dungeon.render.DungeonMapPalette.WITHER_DOOR
import com.andyy.funnymap.dungeon.render.MapRenderCommand.FillRect
import com.andyy.funnymap.dungeon.render.MapRenderCommand.OutlineRect
import com.andyy.funnymap.dungeon.render.MapRenderCommand.Text
import kotlin.math.roundToInt

/** Pure projection from an immutable dungeon snapshot to ordered draw commands. */
object DungeonMapLayoutEngine {
	fun layout(
		snapshot: DungeonSnapshot,
		textMetrics: TextMetrics,
		spec: MapLayoutSpec = MapLayoutSpec(),
		debug: Boolean = false,
	): MapRenderPlan {
		val bounds = snapshot.grid.bounds
		val mapWidth = bounds.width * spec.tileSize + (bounds.width - 1) * spec.gap
		val mapHeight = bounds.height * spec.tileSize + (bounds.height - 1) * spec.gap
		val panelWidth = mapWidth + spec.padding * 2
		val debugLines = if (debug) debugLines(snapshot) else emptyList()
		val panelHeight = spec.padding * 2 + spec.headerHeight + mapHeight +
			debugLines.size * spec.debugFooterHeight
		val mapX = spec.originX + spec.padding
		val mapY = spec.originY + spec.padding + spec.headerHeight
		val commands = ArrayList<MapRenderCommand>()

		commands += FillRect(spec.originX, spec.originY, panelWidth, panelHeight, PANEL)
		commands += OutlineRect(spec.originX, spec.originY, panelWidth, panelHeight, PANEL_BORDER)
		commands += Text("Dungeon Map", mapX, spec.originY + spec.padding + 2, TEXT)
		commands += FillRect(mapX, mapY, mapWidth, mapHeight, MAP_WELL)

		for (connection in snapshot.grid.connections.sortedWith(connectionComparator)) {
			drawConnection(commands, connection, mapX, mapY, spec)
		}

		for (room in snapshot.grid.roomsById.values.sortedBy { it.id.value }) {
			val roomColor = roomColor(room.type)
			drawInternalBridges(commands, room, roomColor, mapX, mapY, spec)
			for (cell in room.footprint.cells.sorted()) {
				val (x, y) = cellOrigin(cell, mapX, mapY, spec)
				commands += FillRect(x, y, spec.tileSize, spec.tileSize, roomColor)
				drawExposedBorders(commands, room, cell, x, y, spec)
			}
			drawRoomLabel(commands, room, textMetrics, mapX, mapY, spec)
			drawOrientation(commands, room, mapX, mapY, spec)
			drawCompletion(commands, room, mapX, mapY, spec)
		}

		if (snapshot.grid.roomsById.isEmpty()) {
			val message = "No room data"
			val x = mapX + (mapWidth - textMetrics.width(message)).coerceAtLeast(0) / 2
			commands += Text(message, x, mapY + (mapHeight - FONT_HEIGHT) / 2, SECONDARY_TEXT)
		}

		debugLines.forEachIndexed { index, debugText ->
			val clipped = ellipsize(debugText, panelWidth - spec.padding * 2, textMetrics)
			commands += Text(
				clipped,
				mapX,
				mapY + mapHeight + 2 + index * spec.debugFooterHeight,
				SECONDARY_TEXT,
			)
		}

		return MapRenderPlan(spec.originX, spec.originY, panelWidth, panelHeight, commands)
	}

	private fun drawInternalBridges(
		commands: MutableList<MapRenderCommand>,
		room: DungeonRoom,
		roomColor: Int,
		mapX: Int,
		mapY: Int,
		spec: MapLayoutSpec,
	) {
		val cells = room.footprint.cells
		for (cell in cells.sorted()) {
			val (x, y) = cellOrigin(cell, mapX, mapY, spec)
			if (cell.offset(1, 0) in cells) {
				commands += FillRect(x + spec.tileSize, y, spec.gap, spec.tileSize, roomColor)
				commands += FillRect(x + spec.tileSize, y, spec.gap, 1, GRID_BORDER)
				commands += FillRect(x + spec.tileSize, y + spec.tileSize - 1, spec.gap, 1, GRID_BORDER)
			}
			if (cell.offset(0, 1) in cells) {
				commands += FillRect(x, y + spec.tileSize, spec.tileSize, spec.gap, roomColor)
				commands += FillRect(x, y + spec.tileSize, 1, spec.gap, GRID_BORDER)
				commands += FillRect(x + spec.tileSize - 1, y + spec.tileSize, 1, spec.gap, GRID_BORDER)
			}
		}
	}

	private fun drawExposedBorders(
		commands: MutableList<MapRenderCommand>,
		room: DungeonRoom,
		cell: GridPosition,
		x: Int,
		y: Int,
		spec: MapLayoutSpec,
	) {
		val cells = room.footprint.cells
		if (cell.offset(0, -1) !in cells) commands += FillRect(x, y, spec.tileSize, 1, GRID_BORDER)
		if (cell.offset(1, 0) !in cells) {
			commands += FillRect(x + spec.tileSize - 1, y, 1, spec.tileSize, GRID_BORDER)
		}
		if (cell.offset(0, 1) !in cells) {
			commands += FillRect(x, y + spec.tileSize - 1, spec.tileSize, 1, GRID_BORDER)
		}
		if (cell.offset(-1, 0) !in cells) commands += FillRect(x, y, 1, spec.tileSize, GRID_BORDER)
	}

	private fun drawRoomLabel(
		commands: MutableList<MapRenderCommand>,
		room: DungeonRoom,
		textMetrics: TextMetrics,
		mapX: Int,
		mapY: Int,
		spec: MapLayoutSpec,
	) {
		val label = when (val recognition = room.recognition) {
			is RoomRecognition.Known -> recognition.roomName
			is RoomRecognition.Unknown -> "?"
		}
		val clipped = ellipsize(label, spec.tileSize - 4, textMetrics)
		if (clipped.isEmpty()) return

		val anchor = labelCell(room)
		val (x, y) = cellOrigin(anchor, mapX, mapY, spec)
		val labelX = x + (spec.tileSize - textMetrics.width(clipped)).coerceAtLeast(0) / 2
		val labelY = y + (spec.tileSize - FONT_HEIGHT) / 2
		commands += Text(clipped, labelX, labelY, TEXT)
	}

	private fun drawOrientation(
		commands: MutableList<MapRenderCommand>,
		room: DungeonRoom,
		mapX: Int,
		mapY: Int,
		spec: MapLayoutSpec,
	) {
		val orientation = room.orientation as? RoomOrientation.Known ?: return
		val anchor = room.footprint.cells.minOrNull() ?: return
		val (x, y) = cellOrigin(anchor, mapX, mapY, spec)
		val middle = (spec.tileSize - ORIENTATION_MARKER_LENGTH) / 2
		val marker = when (orientation.rotation) {
			RoomRotation.DEGREES_0 -> FillRect(x + middle, y + 2, ORIENTATION_MARKER_LENGTH, 2, TEXT)
			RoomRotation.DEGREES_90 -> FillRect(
				x + spec.tileSize - 4,
				y + middle,
				2,
				ORIENTATION_MARKER_LENGTH,
				TEXT,
			)
			RoomRotation.DEGREES_180 -> FillRect(
				x + middle,
				y + spec.tileSize - 4,
				ORIENTATION_MARKER_LENGTH,
				2,
				TEXT,
			)
			RoomRotation.DEGREES_270 -> FillRect(x + 2, y + middle, 2, ORIENTATION_MARKER_LENGTH, TEXT)
		}
		commands += marker
	}

	private fun drawCompletion(
		commands: MutableList<MapRenderCommand>,
		room: DungeonRoom,
		mapX: Int,
		mapY: Int,
		spec: MapLayoutSpec,
	) {
		val color = when (room.completion) {
			RoomCompletion.COMPLETED -> COMPLETED
			RoomCompletion.CLEARED -> CLEARED
			RoomCompletion.DISCOVERED -> DISCOVERED
			RoomCompletion.FAILED -> FAILED
			RoomCompletion.UNKNOWN,
			RoomCompletion.UNDISCOVERED,
			-> return
		}
		val cell = room.footprint.cells.maxOrNull() ?: return
		val (x, y) = cellOrigin(cell, mapX, mapY, spec)
		val markerX = x + spec.tileSize - 7
		val markerY = y + spec.tileSize - 7
		when (room.completion) {
			RoomCompletion.COMPLETED,
			RoomCompletion.CLEARED,
			-> {
				commands += FillRect(markerX, markerY + 2, 1, 2, color)
				commands += FillRect(markerX + 1, markerY + 3, 1, 1, color)
				commands += FillRect(markerX + 2, markerY + 2, 1, 1, color)
				commands += FillRect(markerX + 3, markerY + 1, 1, 1, color)
				commands += FillRect(markerX + 4, markerY, 1, 1, color)
			}
			RoomCompletion.FAILED -> {
				for (offset in 0..3) {
					commands += FillRect(markerX + offset, markerY + offset, 1, 1, color)
					commands += FillRect(markerX + 3 - offset, markerY + offset, 1, 1, color)
				}
			}
			RoomCompletion.DISCOVERED -> commands += FillRect(markerX + 1, markerY + 1, 3, 3, color)
			RoomCompletion.UNKNOWN,
			RoomCompletion.UNDISCOVERED,
			-> Unit
		}
	}

	private fun drawConnection(
		commands: MutableList<MapRenderCommand>,
		connection: ConnectionCell,
		mapX: Int,
		mapY: Int,
		spec: MapLayoutSpec,
	) {
		val (firstX, firstY) = cellOrigin(connection.first, mapX, mapY, spec)
		val inset = (spec.tileSize - spec.connectionThickness) / 2
		val horizontal = connection.first.row == connection.second.row
		val door = if (horizontal) {
			FillRect(
				firstX + spec.tileSize,
				firstY + inset,
				spec.gap,
				spec.connectionThickness,
				connectionColor(connection.type),
			)
		} else {
			FillRect(
				firstX + inset,
				firstY + spec.tileSize,
				spec.connectionThickness,
				spec.gap,
				connectionColor(connection.type),
			)
		}
		commands += door

		if (connection.state == ConnectionState.OPEN && connection.type != ConnectionType.OPEN_PASSAGE) {
			commands += if (horizontal) {
				FillRect(
					door.x,
					door.y + door.height / 2,
					door.width,
					1,
					MAP_WELL,
				)
			} else {
				FillRect(
					door.x + door.width / 2,
					door.y,
					1,
					door.height,
					MAP_WELL,
				)
			}
		}
	}

	private fun labelCell(room: DungeonRoom): GridPosition {
		val cells = room.footprint.cells
		val centerColumn = cells.map { it.column }.average()
		val centerRow = cells.map { it.row }.average()
		return cells.minWithOrNull(
			compareBy<GridPosition> {
				val columnDistance = it.column - centerColumn
				val rowDistance = it.row - centerRow
				(columnDistance * columnDistance + rowDistance * rowDistance).roundToInt()
			}.thenBy { it },
		) ?: room.footprint.anchor
	}

	private fun debugLines(snapshot: DungeonSnapshot): List<String> {
		val scanner = snapshot.scannerDebug
		if (scanner != null) {
			val latest = scanner.latestRoom
			val cell = latest?.logicalCell?.let { "${it.column},${it.row}" } ?: "?"
			val rotation = latest?.rotation?.degrees?.toString()?.plus("deg") ?: "?"
			val margin = latest?.margin?.let { formatPercent(it) } ?: "?"
			return listOf(
				"Scan: ${scanner.lifecycle.name} q=${scanner.queuedWork} ok=${scanner.counters.successfulMatches} fail=${scanner.counters.failedMatches}",
				"DB: rooms=${scanner.databaseRoomCount} fp=${scanner.databaseFingerprintCount} loaded=${scanner.loadedChunkCount} proposals=${scanner.discoveredProposalCount}",
				"Cell $cell cov=${formatPercent(latest?.availableCoverage ?: 0.0)} candidates=${latest?.candidateCount ?: 0} cache=${latest?.cacheState?.name ?: "?"}",
				"Evidence m=${latest?.matchedSampleCount ?: 0} x=${latest?.conflictingSampleCount ?: 0} n=${latest?.comparableSampleCount ?: 0} obs=${formatPercent(latest?.observedToDefinitionCoverage ?: 0.0)} avail=${formatPercent(latest?.definitionToObservedCoverage ?: 0.0)} total=${formatPercent(latest?.totalDefinitionCoverage ?: 0.0)}",
				"Best=${latest?.bestCandidate ?: "?"} score=${formatPercent(latest?.score ?: 0.0)} margin=$margin rot=$rotation",
				"Runner=${latest?.runnerUpCandidate ?: "?"} state=${latest?.failureReason?.name ?: "KNOWN_OR_PENDING"}",
			)
		}
		val reason = snapshot.failureReason ?: snapshot.grid.roomsById.values
			.asSequence()
			.mapNotNull { (it.recognition as? RoomRecognition.Unknown)?.reason }
			.firstOrNull()
		return listOf(reason?.let { "State: ${it.name}" } ?: "State: READY")
	}

	private fun formatPercent(value: Double): String = "${(value * 100.0).roundToInt()}%"

	private fun ellipsize(value: String, maxWidth: Int, metrics: TextMetrics): String {
		if (maxWidth <= 0) return ""
		if (metrics.width(value) <= maxWidth) return value
		if (metrics.width(ELLIPSIS) > maxWidth) return ""
		var end = value.length
		while (end > 0 && metrics.width(value.substring(0, end) + ELLIPSIS) > maxWidth) end--
		return value.substring(0, end) + ELLIPSIS
	}

	private fun roomColor(type: RoomType): Int = when (type) {
		RoomType.ENTRANCE -> ENTRANCE
		RoomType.NORMAL -> NORMAL
		RoomType.PUZZLE -> PUZZLE
		RoomType.TRAP -> TRAP
		RoomType.MINIBOSS -> MINIBOSS
		RoomType.FAIRY -> FAIRY
		RoomType.BLOOD -> BLOOD
		RoomType.BOSS -> BOSS
		RoomType.UNKNOWN -> UNKNOWN
	}

	private fun connectionColor(type: ConnectionType): Int = when (type) {
		ConnectionType.OPEN_PASSAGE -> OPEN_PASSAGE
		ConnectionType.NORMAL_DOOR -> NORMAL_DOOR
		ConnectionType.WITHER_DOOR -> WITHER_DOOR
		ConnectionType.BLOOD_DOOR -> BLOOD_DOOR
		ConnectionType.ENTRANCE_DOOR -> ENTRANCE_DOOR
		ConnectionType.UNKNOWN -> UNKNOWN
	}

	private fun cellOrigin(
		position: GridPosition,
		mapX: Int,
		mapY: Int,
		spec: MapLayoutSpec,
	): Pair<Int, Int> = Pair(
		mapX + position.column * spec.stride,
		mapY + position.row * spec.stride,
	)

	private val connectionComparator = compareBy<ConnectionCell>(
		{ it.first },
		{ it.second },
		{ it.type.ordinal },
	)

	private const val FONT_HEIGHT = 9
	private const val ORIENTATION_MARKER_LENGTH = 6
	private const val ELLIPSIS = "..."
}
