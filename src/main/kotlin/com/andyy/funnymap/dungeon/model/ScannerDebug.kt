package com.andyy.funnymap.dungeon.model

enum class ScannerLifecycleState {
	INACTIVE,
	EMPTY_DATABASE,
	DISCOVERING,
	SCANNING,
	READY,
}

enum class ScannerCacheState {
	EXACT_HIT,
	FINGERPRINT_HIT,
	MISS,
}

data class ScannerCounters(
	val roomsQueued: Long = 0,
	val roomsScanned: Long = 0,
	val chunksUnavailable: Long = 0,
	val observationsCreated: Long = 0,
	val shortlistCandidates: Long = 0,
	val successfulMatches: Long = 0,
	val failedMatches: Long = 0,
	val cacheHits: Long = 0,
	val cacheMisses: Long = 0,
	val scanTimeNanos: Long = 0,
	val matchTimeNanos: Long = 0,
) {
	init {
		listOf(
			roomsQueued,
			roomsScanned,
			chunksUnavailable,
			observationsCreated,
			shortlistCandidates,
			successfulMatches,
			failedMatches,
			cacheHits,
			cacheMisses,
			scanTimeNanos,
			matchTimeNanos,
		).forEach { require(it >= 0) { "Scanner counters must not be negative" } }
	}
}

data class RoomScanDebug(
	val observationId: String,
	val logicalCell: GridPosition? = null,
	val availableCoverage: Double = 0.0,
	val candidateCount: Int = 0,
	val bestCandidate: String? = null,
	val runnerUpCandidate: String? = null,
	val score: Double = 0.0,
	val margin: Double? = null,
	val rotation: RoomRotation? = null,
	val failureReason: RecognitionUnknownReason? = null,
	val cacheState: ScannerCacheState,
) {
	init {
		require(observationId.isNotBlank()) { "Debug observation id must not be blank" }
		require(availableCoverage.isFinite() && availableCoverage in 0.0..1.0) {
			"Debug coverage must be between 0 and 1"
		}
		require(candidateCount >= 0) { "Debug candidate count must not be negative" }
		require(score.isFinite() && score >= 0.0) { "Debug score must be finite and non-negative" }
		require(margin == null || margin.isFinite() && margin >= 0.0) {
			"Debug margin must be finite and non-negative"
		}
	}
}

data class DungeonScannerDebug(
	val lifecycle: ScannerLifecycleState,
	val databaseRoomCount: Int,
	val queuedWork: Int,
	val counters: ScannerCounters = ScannerCounters(),
	val latestRoom: RoomScanDebug? = null,
) {
	init {
		require(databaseRoomCount >= 0) { "Database room count must not be negative" }
		require(queuedWork >= 0) { "Queued work must not be negative" }
	}
}
