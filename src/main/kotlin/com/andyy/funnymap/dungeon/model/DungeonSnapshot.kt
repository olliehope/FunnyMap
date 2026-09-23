package com.andyy.funnymap.dungeon.model

/** A complete immutable value handed from dungeon state producers to consumers such as the HUD. */
data class DungeonSnapshot(
	val revision: Long,
	val grid: DungeonGrid,
	val failureReason: RecognitionUnknownReason? = null,
	val scannerDebug: DungeonScannerDebug? = null,
) {
	init {
		require(revision >= 0) { "Snapshot revision must not be negative" }
	}

	companion object {
		val EMPTY = DungeonSnapshot(
			revision = 0,
			grid = DungeonGrid.EMPTY,
			failureReason = RecognitionUnknownReason.INSUFFICIENT_EVIDENCE,
		)
	}
}
