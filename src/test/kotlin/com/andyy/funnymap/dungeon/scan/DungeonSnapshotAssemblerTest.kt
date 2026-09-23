package com.andyy.funnymap.dungeon.scan

import com.andyy.funnymap.dungeon.match.RoomMatchResult
import com.andyy.funnymap.dungeon.match.RoomMatcher
import com.andyy.funnymap.dungeon.model.DungeonSnapshotExchange
import com.andyy.funnymap.dungeon.model.GridPosition
import com.andyy.funnymap.dungeon.model.RecognitionUnknownReason
import com.andyy.funnymap.dungeon.model.RoomFootprint
import com.andyy.funnymap.dungeon.model.RoomId
import com.andyy.funnymap.dungeon.model.RoomOrientation
import com.andyy.funnymap.dungeon.model.RoomRecognition
import com.andyy.funnymap.dungeon.model.RoomRotation
import com.andyy.funnymap.dungeon.model.RoomType
import com.andyy.funnymap.dungeon.render.DungeonMapLayoutEngine
import com.andyy.funnymap.dungeon.render.MapRenderCommand
import com.andyy.funnymap.dungeon.render.TextMetrics
import com.andyy.funnymap.dungeon.room.FingerprintPolicy
import com.andyy.funnymap.dungeon.room.GameDataVersion
import com.andyy.funnymap.dungeon.room.LocalBlockBounds
import com.andyy.funnymap.dungeon.room.LocalBlockPosition
import com.andyy.funnymap.dungeon.room.ObservationCoverage
import com.andyy.funnymap.dungeon.room.ObservationProvenance
import com.andyy.funnymap.dungeon.room.ObservationSource
import com.andyy.funnymap.dungeon.room.RoomDatabase
import com.andyy.funnymap.dungeon.room.RoomDefinition
import com.andyy.funnymap.dungeon.room.RoomFingerprint
import com.andyy.funnymap.dungeon.room.RoomLocalDatum
import com.andyy.funnymap.dungeon.room.RoomObservation
import com.andyy.funnymap.dungeon.room.StructuralSample
import com.andyy.funnymap.dungeon.room.WorldCoordinate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DungeonSnapshotAssemblerTest {
	@Test
	fun `known matches become logical rooms with definition metadata`() {
		val fixture = Fixture(RoomFootprint.of(GridPosition(0, 0)))
		val first = fixture.known("first")
		val second = fixture.known("second")
		val snapshot = DungeonSnapshotAssembler().assemble(
			revision = 1,
			locatedMatches = listOf(
				located(WorldCoordinate(100, 64, 200), fixture.footprint, first),
				located(WorldCoordinate(132, 64, 200), fixture.footprint, second),
			),
		)

		assertEquals(2, snapshot.grid.roomsById.size)
		val left = snapshot.grid.roomAt(GridPosition(0, 0))!!
		val right = snapshot.grid.roomAt(GridPosition(1, 0))!!
		assertEquals("Synthetic", (left.recognition as RoomRecognition.Known).roomName)
		assertEquals(RoomType.PUZZLE, right.type)
		assertEquals(3, right.secretCount)
		assertEquals(1, right.cryptCount)
		assertEquals(RoomOrientation.Known(RoomRotation.DEGREES_0), right.orientation)
	}

	@Test
	fun `unknown proposals need a verified lattice anchor and remain visibly unknown`() {
		val fixture = Fixture(RoomFootprint.of(GridPosition(0, 0)))
		val unknown = fixture.unknown()
		val withoutAnchor = DungeonSnapshotAssembler().assemble(
			1,
			listOf(located(WorldCoordinate(132, 64, 200), fixture.footprint, unknown)),
		)
		assertTrue(withoutAnchor.grid.roomsById.isEmpty())
		assertEquals(RecognitionUnknownReason.NO_DATABASE_MATCH, withoutAnchor.failureReason)

		val withAnchor = DungeonSnapshotAssembler().assemble(
			2,
			listOf(
				located(WorldCoordinate(100, 64, 200), fixture.footprint, fixture.known("anchor")),
				located(WorldCoordinate(132, 64, 200), fixture.footprint, unknown),
			),
		)
		val room = withAnchor.grid.roomAt(GridPosition(1, 0))!!
		assertEquals(RoomType.UNKNOWN, room.type)
		assertIs<RoomRecognition.Unknown>(room.recognition)
	}

	@Test
	fun `off-lattice proposals are not fabricated into cells`() {
		val fixture = Fixture(RoomFootprint.of(GridPosition(0, 0)))
		val snapshot = DungeonSnapshotAssembler().assemble(
			1,
			listOf(
				located(WorldCoordinate(100, 64, 200), fixture.footprint, fixture.known("anchor")),
				located(WorldCoordinate(131, 64, 200), fixture.footprint, fixture.known("offset")),
			),
		)
		assertEquals(1, snapshot.grid.roomsById.size)
	}

	@Test
	fun `synthetic observation flows through matcher exchange and renderer`() {
		val footprint = RoomFootprint.of(GridPosition(0, 0), GridPosition(1, 0))
		val fixture = Fixture(footprint)
		val result = fixture.known("e2e")
		val snapshot = DungeonSnapshotAssembler().assemble(
			1,
			listOf(located(WorldCoordinate(0, 70, 0), footprint, result)),
		)
		val exchange = DungeonSnapshotExchange()
		assertTrue(exchange.publish(exchange.view().session, snapshot))

		val published = exchange.view().snapshot
		val room = published.grid.roomAt(GridPosition(1, 0))!!
		assertEquals(footprint.localCells, room.footprint.localCells)
		assertEquals(RoomType.PUZZLE, room.type)
		assertEquals(RoomOrientation.Known(RoomRotation.DEGREES_0), room.orientation)
		val labels = DungeonMapLayoutEngine.layout(published, TextMetrics(String::length))
			.commands.filterIsInstance<MapRenderCommand.Text>().map(MapRenderCommand.Text::value)
		assertTrue("Synthetic" in labels)
		assertFalse("No room data" in labels)
	}

	private fun located(
		origin: WorldCoordinate,
		footprint: RoomFootprint,
		result: RoomMatchResult,
	): LocatedRoomMatch = LocatedRoomMatch(origin, footprint, result, anchorVoteCount = 4)

	private class Fixture(val footprint: RoomFootprint) {
		private val samples = BLOCK_IDS.mapIndexed { index, block ->
			StructuralSample(LocalBlockPosition(index % 4, 0, index / 4), block)
		}
		private val definition = RoomDefinition(
			id = RoomId("synthetic/test-room"),
			displayName = "Synthetic",
			type = RoomType.PUZZLE,
			footprint = footprint,
			secretCount = 3,
			cryptCount = 1,
			fingerprints = listOf(RoomFingerprint.create("base", BOUNDS, samples = samples, policyVersion = POLICY.version)),
		)
		private val database = RoomDatabase.create(
			fingerprintPolicyVersion = POLICY.version,
			definitions = listOf(definition),
			policies = mapOf(POLICY.version to POLICY),
		)
		private val matcher = RoomMatcher(database)

		fun known(id: String): RoomMatchResult.Known = assertIs(matcher.match(observation(id, samples)))

		fun unknown(): RoomMatchResult.Unknown = assertIs(
			matcher.match(
				observation("unknown", samples.map { StructuralSample(it.position, "minecraft:netherrack") }),
			),
		)

		private fun observation(id: String, observed: List<StructuralSample>): RoomObservation = RoomObservation(
			observationId = id,
			worldSessionGeneration = 1,
			revision = id.hashCode().toLong().let { if (it == Long.MIN_VALUE) 0 else kotlin.math.abs(it) },
			gameDataVersion = GameDataVersion("26.1.2"),
			fingerprintPolicyVersion = POLICY.version,
			footprint = footprint,
			bounds = BOUNDS,
			datum = RoomLocalDatum(WorldCoordinate(0, 0, 0)),
			samples = observed,
			coverage = ObservationCoverage.complete(BOUNDS),
			provenance = ObservationProvenance(ObservationSource.HEADLESS_TEST),
		)
	}

	private companion object {
		val POLICY = FingerprintPolicy("snapshot-test-v1", setOf("minecraft"), minimumSamples = 8)
		val BOUNDS = LocalBlockBounds(LocalBlockPosition(0, 0, 0), LocalBlockPosition(3, 0, 2))
		val BLOCK_IDS = listOf(
			"minecraft:stone",
			"minecraft:cobblestone",
			"minecraft:granite",
			"minecraft:diorite",
			"minecraft:andesite",
			"minecraft:deepslate",
			"minecraft:tuff",
			"minecraft:calcite",
			"minecraft:bricks",
			"minecraft:obsidian",
			"minecraft:oak_planks",
			"minecraft:birch_planks",
		)
	}
}
