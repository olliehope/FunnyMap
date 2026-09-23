package com.andyy.funnymap.dungeon.model

import com.andyy.funnymap.dungeon.room.LocalBlockBounds
import com.andyy.funnymap.dungeon.room.WorldCoordinate

enum class ScannerLifecycleState {
	INACTIVE,
	EMPTY_DATABASE,
	DISCOVERING,
	SCANNING,
	READY,
}

enum class ScannerCacheState {
	NOT_APPLICABLE,
	EXACT_HIT,
	FINGERPRINT_HIT,
	MISS,
}

enum class ScannerObservationState {
	NONE,
	EMPTY_DATABASE,
	ANCHOR_VOTING,
	OBSERVATION_QUEUED,
	OBSERVING,
	MATCH_QUEUED,
	MATCHED,
	REJECTED,
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
	val chunkLoadEvents: Long = 0,
	val loadedChunksObserved: Long = 0,
	val chunksSurveyed: Long = 0,
	val anchorSamples: Long = 0,
	val proposalsDiscovered: Long = 0,
	val invalidations: Long = 0,
	val snapshotsPublished: Long = 0,
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
			chunkLoadEvents,
			loadedChunksObserved,
			chunksSurveyed,
			anchorSamples,
			proposalsDiscovered,
			invalidations,
			snapshotsPublished,
		).forEach { require(it >= 0) { "Scanner counters must not be negative" } }
	}
}

data class RoomScanDebug(
	val observationId: String,
	val logicalCell: GridPosition? = null,
	val worldOrigin: WorldCoordinate? = null,
	val footprint: RoomFootprint? = null,
	val localBounds: LocalBlockBounds? = null,
	val observationState: ScannerObservationState = ScannerObservationState.NONE,
	val availableCoverage: Double = 0.0,
	val eligibleSampleCount: Int = 0,
	val definitionSampleCount: Int = 0,
	val matchedSampleCount: Int = 0,
	val conflictingSampleCount: Int = 0,
	val comparableSampleCount: Int = 0,
	val observedToDefinitionCoverage: Double = 0.0,
	val definitionToObservedCoverage: Double = 0.0,
	val totalDefinitionCoverage: Double = 0.0,
	val candidateCount: Int = 0,
	val bestCandidate: String? = null,
	val runnerUpCandidate: String? = null,
	val score: Double = 0.0,
	val runnerUpScore: Double? = null,
	val margin: Double? = null,
	val matchedRoomId: String? = null,
	val matchedRoomName: String? = null,
	val matchedFingerprintId: String? = null,
	val rotation: RoomRotation? = null,
	val failureReason: RecognitionUnknownReason? = null,
	val cacheState: ScannerCacheState = ScannerCacheState.NOT_APPLICABLE,
	val diagnosticMessage: String = "",
) {
	init {
		require(observationId.isNotBlank()) { "Debug observation id must not be blank" }
		require(availableCoverage.isFinite() && availableCoverage in 0.0..1.0) {
			"Debug coverage must be between 0 and 1"
		}
		require(candidateCount >= 0) { "Debug candidate count must not be negative" }
		listOf(
			eligibleSampleCount,
			definitionSampleCount,
			matchedSampleCount,
			conflictingSampleCount,
			comparableSampleCount,
		).forEach { require(it >= 0) { "Debug evidence counts must not be negative" } }
		listOf(
			observedToDefinitionCoverage,
			definitionToObservedCoverage,
			totalDefinitionCoverage,
		).forEach {
			require(it.isFinite() && it in 0.0..1.0) { "Debug evidence coverage must be between 0 and 1" }
		}
		require(score.isFinite() && score >= 0.0) { "Debug score must be finite and non-negative" }
		require(runnerUpScore == null || runnerUpScore.isFinite() && runnerUpScore >= 0.0) {
			"Debug runner-up score must be finite and non-negative"
		}
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
	val databaseFingerprintCount: Int = 0,
	val databaseIdentity: String = "",
	val fingerprintPolicyVersion: String = "",
	val loadedChunkCount: Int = 0,
	val surveyedChunkCount: Int = 0,
	val anchorVoteGroupCount: Int = 0,
	val discoveredProposalCount: Int = 0,
	val statusMessage: String = "",
) {
	init {
		require(databaseRoomCount >= 0) { "Database room count must not be negative" }
		require(databaseFingerprintCount >= 0) { "Database fingerprint count must not be negative" }
		require(queuedWork >= 0) { "Queued work must not be negative" }
		require(loadedChunkCount >= 0) { "Loaded chunk count must not be negative" }
		require(surveyedChunkCount >= 0) { "Surveyed chunk count must not be negative" }
		require(anchorVoteGroupCount >= 0) { "Anchor vote group count must not be negative" }
		require(discoveredProposalCount >= 0) { "Discovered proposal count must not be negative" }
	}
}
