package com.andyy.funnymap.dungeon.scan

import com.andyy.funnymap.dungeon.match.RoomMatchResult
import com.andyy.funnymap.dungeon.model.DungeonGrid
import com.andyy.funnymap.dungeon.model.DungeonRoom
import com.andyy.funnymap.dungeon.model.DungeonScannerDebug
import com.andyy.funnymap.dungeon.model.DungeonSnapshot
import com.andyy.funnymap.dungeon.model.GridPosition
import com.andyy.funnymap.dungeon.model.RecognitionUnknownReason
import com.andyy.funnymap.dungeon.model.RoomCompletion
import com.andyy.funnymap.dungeon.model.RoomFootprint
import com.andyy.funnymap.dungeon.model.RoomId
import com.andyy.funnymap.dungeon.model.RoomOrientation
import com.andyy.funnymap.dungeon.model.RoomRecognition
import com.andyy.funnymap.dungeon.model.RoomType
import com.andyy.funnymap.dungeon.room.WorldCoordinate

data class LocatedRoomMatch(
	val worldOrigin: WorldCoordinate,
	val observedFootprint: RoomFootprint,
	val result: RoomMatchResult,
	val anchorVoteCount: Int,
) {
	init {
		require(observedFootprint.anchor == GridPosition(0, 0)) {
			"Located observation footprints must be normalized"
		}
		require(anchorVoteCount > 0) { "A located observation needs structural anchor evidence" }
	}
}

/** Pure, conservative projection from located match results to the logical grid. */
class DungeonSnapshotAssembler(
	private val profile: CatacombsLatticeProfile = CatacombsLatticeProfile(),
) {
	fun assemble(
		revision: Long,
		locatedMatches: Collection<LocatedRoomMatch>,
		debug: DungeonScannerDebug? = null,
	): DungeonSnapshot {
		require(revision >= 0) { "Snapshot revision must not be negative" }
		val known = locatedMatches.filter { it.result is RoomMatchResult.Known }
		if (known.isEmpty()) {
			return DungeonSnapshot(
				revision = revision,
				grid = DungeonGrid(bounds = profile.logicalBounds),
				failureReason = locatedMatches.bestFailureReason(),
				scannerDebug = debug,
			)
		}

		val baseX = known.minOf { it.worldOrigin.x }
		val baseZ = known.minOf { it.worldOrigin.z }
		val occupied = HashSet<GridPosition>()
		val rooms = ArrayList<DungeonRoom>()
		val ordered = locatedMatches.sortedWith(
			compareByDescending<LocatedRoomMatch> { it.result is RoomMatchResult.Known }
				.thenByDescending { it.result.recognition.evidence.candidateScore }
				.thenByDescending(LocatedRoomMatch::anchorVoteCount)
				.thenBy { it.worldOrigin.x }
				.thenBy { it.worldOrigin.z },
		)

		for (located in ordered) {
			val anchor = logicalAnchor(located.worldOrigin, baseX, baseZ) ?: continue
			val absoluteCells = located.observedFootprint.localCells.map { local ->
				anchor.offset(local.column, local.row)
			}
			if (absoluteCells.any { it !in profile.logicalBounds || it in occupied }) continue
			val footprint = RoomFootprint.of(absoluteCells)
			val result = located.result
			val room = when (result) {
				is RoomMatchResult.Known -> DungeonRoom(
					id = runtimeRoomId(located.worldOrigin),
					footprint = footprint,
					type = result.definition.type,
					orientation = result.orientation,
					completion = RoomCompletion.UNKNOWN,
					secretCount = result.definition.secretCount,
					cryptCount = result.definition.cryptCount,
					recognition = result.recognition,
				)
				is RoomMatchResult.Unknown -> DungeonRoom(
					id = runtimeRoomId(located.worldOrigin),
					footprint = footprint,
					type = RoomType.UNKNOWN,
					orientation = RoomOrientation.Unknown,
					recognition = result.recognition,
				)
			}
			rooms += room
			occupied += absoluteCells
		}

		return DungeonSnapshot(
			revision = revision,
			grid = DungeonGrid(bounds = profile.logicalBounds, rooms = rooms),
			failureReason = if (rooms.any { it.recognition is RoomRecognition.Known }) null
			else locatedMatches.bestFailureReason(),
			scannerDebug = debug,
		)
	}

	private fun logicalAnchor(origin: WorldCoordinate, baseX: Int, baseZ: Int): GridPosition? {
		val deltaX = origin.x - baseX
		val deltaZ = origin.z - baseZ
		if (deltaX % profile.cellSpacingBlocks != 0 || deltaZ % profile.cellSpacingBlocks != 0) return null
		return GridPosition(deltaX / profile.cellSpacingBlocks, deltaZ / profile.cellSpacingBlocks)
	}

	private fun runtimeRoomId(origin: WorldCoordinate): RoomId =
		RoomId("runtime/${origin.x}_${origin.y}_${origin.z}")
}

private fun Collection<LocatedRoomMatch>.bestFailureReason(): RecognitionUnknownReason =
	asSequence()
		.mapNotNull { (it.result as? RoomMatchResult.Unknown)?.reason }
		.minByOrNull(RecognitionUnknownReason::ordinal)
		?: RecognitionUnknownReason.INSUFFICIENT_EVIDENCE
