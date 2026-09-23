package com.andyy.funnymap.dungeon.render

import com.andyy.funnymap.dungeon.model.ConnectionCell
import com.andyy.funnymap.dungeon.model.ConnectionState
import com.andyy.funnymap.dungeon.model.ConnectionType
import com.andyy.funnymap.dungeon.model.DungeonGrid
import com.andyy.funnymap.dungeon.model.DungeonRoom
import com.andyy.funnymap.dungeon.model.DungeonScannerDebug
import com.andyy.funnymap.dungeon.model.DungeonSnapshot
import com.andyy.funnymap.dungeon.model.GridPosition
import com.andyy.funnymap.dungeon.model.RecognitionEvidence
import com.andyy.funnymap.dungeon.model.RoomCompletion
import com.andyy.funnymap.dungeon.model.RoomFootprint
import com.andyy.funnymap.dungeon.model.RoomId
import com.andyy.funnymap.dungeon.model.RoomOrientation
import com.andyy.funnymap.dungeon.model.RoomRecognition
import com.andyy.funnymap.dungeon.model.RoomRotation
import com.andyy.funnymap.dungeon.model.RoomType
import com.andyy.funnymap.dungeon.model.ScannerCounters
import com.andyy.funnymap.dungeon.model.ScannerLifecycleState
import com.andyy.funnymap.dungeon.render.MapRenderCommand.FillRect
import com.andyy.funnymap.dungeon.render.MapRenderCommand.Text
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DungeonMapLayoutEngineTest {
	private val metrics = TextMetrics { it.length * 3 }
	private val spec = MapLayoutSpec(originX = 0, originY = 0)

	@Test
	fun `empty snapshot reports no data and typed debug reason`() {
		val plan = DungeonMapLayoutEngine.layout(DungeonSnapshot.EMPTY, metrics, spec, debug = true)
		val text = plan.commands.filterIsInstance<Text>().map(Text::value)

		assertTrue("No room data" in text)
		assertTrue("State: INSUFFICIENT_EVIDENCE" in text)
		assertEquals(152, plan.width)
		assertEquals(178, plan.height)
	}

	@Test
	fun `scanner debug snapshot adds bounded diagnostic lines`() {
		val snapshot = DungeonSnapshot(
			revision = 1,
			grid = DungeonGrid.EMPTY,
			scannerDebug = DungeonScannerDebug(
				lifecycle = ScannerLifecycleState.SCANNING,
				databaseRoomCount = 2,
				queuedWork = 5,
				counters = ScannerCounters(successfulMatches = 1, failedMatches = 2),
			),
		)
		val plan = DungeonMapLayoutEngine.layout(snapshot, metrics, spec, debug = true)
		val text = plan.commands.filterIsInstance<Text>().map(Text::value)

		assertTrue(text.any { it.startsWith("Scan: SCANNING") })
		assertTrue(text.any { it.startsWith("Cell ?") })
		assertTrue(text.any { it.startsWith("Best=") })
		assertTrue(text.any { it.startsWith("Runner=") })
		assertEquals(214, plan.height)
	}

	@Test
	fun `joins adjacent cells owned by one room`() {
		val room = room("long", listOf(position(0, 0), position(1, 0)), RoomType.NORMAL)
		val plan = layout(room)
		val mapX = spec.padding
		val mapY = spec.padding + spec.headerHeight

		assertTrue(
			plan.commands.contains(
				FillRect(mapX + spec.tileSize, mapY, spec.gap, spec.tileSize, DungeonMapPalette.NORMAL),
			),
		)
	}

	@Test
	fun `L footprint preserves its missing corner`() {
		val room = room(
			"l-room",
			listOf(position(0, 0), position(1, 0), position(0, 1)),
			RoomType.PUZZLE,
		)
		val plan = layout(room)
		val holeX = spec.padding + spec.stride + spec.tileSize / 2
		val holeY = spec.padding + spec.headerHeight + spec.stride + spec.tileSize / 2

		assertFalse(plan.commands.filterIsInstance<FillRect>().any { rectangle ->
			rectangle.color == DungeonMapPalette.PUZZLE &&
				holeX in rectangle.x until rectangle.x + rectangle.width &&
				holeY in rectangle.y until rectangle.y + rectangle.height
		})
	}

	@Test
	fun `does not infer a connection from adjacent rooms`() {
		val left = room("left", listOf(position(0, 0)), RoomType.NORMAL)
		val right = room("right", listOf(position(1, 0)), RoomType.TRAP)
		val withoutConnection = layout(left, right)
		val withConnection = DungeonMapLayoutEngine.layout(
			DungeonSnapshot(
				revision = 1,
				grid = DungeonGrid(
					rooms = listOf(left, right),
					connections = listOf(
						ConnectionCell.between(
							position(0, 0),
							position(1, 0),
							ConnectionType.WITHER_DOOR,
						),
					),
				),
			),
			metrics,
			spec,
		)

		assertFalse(withoutConnection.commands.filterIsInstance<FillRect>().any {
			it.color == DungeonMapPalette.WITHER_DOOR
		})
		assertTrue(withConnection.commands.filterIsInstance<FillRect>().any {
			it.color == DungeonMapPalette.WITHER_DOOR && it.width == spec.gap
		})
	}

	@Test
	fun `projects a vertical open door with a visible state notch`() {
		val top = room("top", listOf(position(2, 1)), RoomType.NORMAL)
		val bottom = room("bottom", listOf(position(2, 2)), RoomType.NORMAL)
		val snapshot = DungeonSnapshot(
			revision = 1,
			grid = DungeonGrid(
				rooms = listOf(top, bottom),
				connections = listOf(
					ConnectionCell.between(
						position(2, 1),
						position(2, 2),
						ConnectionType.BLOOD_DOOR,
						ConnectionState.OPEN,
					),
				),
			),
		)
		val plan = DungeonMapLayoutEngine.layout(snapshot, metrics, spec)
		val door = plan.commands.filterIsInstance<FillRect>().single {
			it.color == DungeonMapPalette.BLOOD_DOOR
		}
		val notch = plan.commands.filterIsInstance<FillRect>().single {
			it.color == DungeonMapPalette.MAP_WELL && it.width == 1 && it.height == spec.gap
		}

		assertEquals(spec.connectionThickness, door.width)
		assertEquals(spec.gap, door.height)
		assertEquals(door.x + door.width / 2, notch.x)
		assertEquals(door.y, notch.y)
	}

	@Test
	fun `renders one known label plus orientation and completion markers`() {
		val room = DungeonRoom(
			id = RoomId("known"),
			footprint = RoomFootprint.of(position(2, 2), position(3, 2)),
			type = RoomType.BLOOD,
			orientation = RoomOrientation.Known(RoomRotation.DEGREES_90),
			completion = RoomCompletion.COMPLETED,
			recognition = RoomRecognition.Known(
				definitionId = "catacombs/known",
				roomName = "Known",
				confidence = 0.9,
				evidence = RecognitionEvidence(
					observedSampleCount = 10,
					definitionSampleCount = 10,
					matchedSampleCount = 9,
				),
			),
		)
		val plan = layout(room)

		assertEquals(1, plan.commands.filterIsInstance<Text>().count { it.value.startsWith("K") })
		assertTrue(plan.commands.filterIsInstance<FillRect>().any {
			it.color == DungeonMapPalette.TEXT && it.width == 2
		})
		assertEquals(
			5,
			plan.commands.filterIsInstance<FillRect>().count {
				it.color == DungeonMapPalette.COMPLETED
			},
		)
	}

	@Test
	fun `all generated rectangles stay inside the plan`() {
		val room = room(
			"edge",
			listOf(position(4, 5), position(5, 5)),
			RoomType.ENTRANCE,
		)
		val plan = layout(room)

		for (command in plan.commands) {
			when (command) {
				is FillRect -> {
					assertTrue(command.x >= plan.x && command.y >= plan.y)
					assertTrue(command.x + command.width <= plan.x + plan.width)
					assertTrue(command.y + command.height <= plan.y + plan.height)
				}
				is MapRenderCommand.OutlineRect -> Unit
				is Text -> Unit
			}
		}
	}

	private fun layout(vararg rooms: DungeonRoom): MapRenderPlan = DungeonMapLayoutEngine.layout(
		DungeonSnapshot(revision = 1, grid = DungeonGrid(rooms = rooms.asList())),
		metrics,
		spec,
	)

	private fun room(
		id: String,
		cells: Collection<GridPosition>,
		type: RoomType,
	): DungeonRoom = DungeonRoom(RoomId(id), RoomFootprint.of(cells), type)

	private fun position(column: Int, row: Int): GridPosition = GridPosition(column, row)
}
