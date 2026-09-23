package com.andyy.funnymap.dungeon.debug

import com.andyy.funnymap.dungeon.model.DungeonScannerDebug
import com.andyy.funnymap.dungeon.model.RoomScanDebug
import java.util.Locale

data class LiveDebugReportContext(
	val version: String,
	val commit: String,
	val buildMode: String,
	val minecraftVersion: String,
	val floor: String,
	val sessionGeneration: Long,
	val scanner: DungeonScannerDebug,
) {
	init {
		require(version.isNotBlank()) { "Debug report version must not be blank" }
		require(commit.isNotBlank()) { "Debug report commit must not be blank" }
		require(buildMode.isNotBlank()) { "Debug report build mode must not be blank" }
		require(minecraftVersion.isNotBlank()) { "Debug report Minecraft version must not be blank" }
		require(floor.isNotBlank()) { "Debug report floor must not be blank" }
		require(sessionGeneration >= 0) { "Debug report session generation must not be negative" }
	}
}

/** Produces a compact issue-ready report without account, chat, server, or credential data. */
object LiveDebugReportFormatter {
	fun format(context: LiveDebugReportContext, room: RoomScanDebug? = context.scanner.latestRoom): String = buildString {
		appendLine("FunnyMap live room diagnostic")
		appendLine("version=${context.version}")
		appendLine("commit=${context.commit}")
		appendLine("buildMode=${context.buildMode}")
		appendLine("minecraft=${context.minecraftVersion}")
		appendLine("floor=${context.floor}")
		appendLine("sessionGeneration=${context.sessionGeneration}")
		appendLine("scanner.lifecycle=${context.scanner.lifecycle.name}")
		appendLine("scanner.status=${context.scanner.statusMessage.ifBlank { "<none>" }}")
		appendLine("scanner.queuedWork=${context.scanner.queuedWork}")
		appendLine("scanner.loadedChunks=${context.scanner.loadedChunkCount}")
		appendLine("scanner.surveyedChunks=${context.scanner.surveyedChunkCount}")
		appendLine("scanner.anchorVoteGroups=${context.scanner.anchorVoteGroupCount}")
		appendLine("scanner.discoveredProposals=${context.scanner.discoveredProposalCount}")
		appendLine("database.rooms=${context.scanner.databaseRoomCount}")
		appendLine("database.fingerprints=${context.scanner.databaseFingerprintCount}")
		appendLine("database.digest=${context.scanner.databaseIdentity.ifBlank { "<none>" }}")
		appendLine("database.policy=${context.scanner.fingerprintPolicyVersion.ifBlank { "<none>" }}")
		appendCounters(context.scanner)
		appendRoom(room)
	}

	private fun StringBuilder.appendCounters(scanner: DungeonScannerDebug) {
		val counters = scanner.counters
		appendLine("counters.chunkLoadEvents=${counters.chunkLoadEvents}")
		appendLine("counters.loadedChunksObserved=${counters.loadedChunksObserved}")
		appendLine("counters.chunksSurveyed=${counters.chunksSurveyed}")
		appendLine("counters.anchorSamples=${counters.anchorSamples}")
		appendLine("counters.proposalsDiscovered=${counters.proposalsDiscovered}")
		appendLine("counters.roomsQueued=${counters.roomsQueued}")
		appendLine("counters.roomsScanned=${counters.roomsScanned}")
		appendLine("counters.chunksUnavailable=${counters.chunksUnavailable}")
		appendLine("counters.observationsCreated=${counters.observationsCreated}")
		appendLine("counters.shortlistCandidates=${counters.shortlistCandidates}")
		appendLine("counters.successfulMatches=${counters.successfulMatches}")
		appendLine("counters.failedMatches=${counters.failedMatches}")
		appendLine("counters.cacheHits=${counters.cacheHits}")
		appendLine("counters.cacheMisses=${counters.cacheMisses}")
		appendLine("counters.invalidations=${counters.invalidations}")
		appendLine("counters.snapshotsPublished=${counters.snapshotsPublished}")
	}

	private fun StringBuilder.appendRoom(room: RoomScanDebug?) {
		if (room == null) {
			appendLine("room.state=NONE")
			appendLine("room.reason=No scanner candidate is available near the player")
			return
		}
		appendLine("room.state=${room.observationState.name}")
		appendLine("room.observationId=${room.observationId}")
		appendLine("room.logicalCell=${room.logicalCell?.let { "${it.column},${it.row}" } ?: "UNKNOWN"}")
		appendLine("room.worldOrigin=${room.worldOrigin?.let { "${it.x},${it.y},${it.z}" } ?: "UNKNOWN"}")
		appendLine("room.footprint=${room.footprint?.shape?.name ?: "UNKNOWN"}")
		appendLine("room.bounds=${room.localBounds?.let { "${it.min}..${it.max}" } ?: "UNKNOWN"}")
		appendLine("room.coverage=${ratio(room.availableCoverage)}")
		appendLine("room.eligibleSamples=${room.eligibleSampleCount}")
		appendLine("room.definitionSamples=${room.definitionSampleCount}")
		appendLine("room.matchedSamples=${room.matchedSampleCount}")
		appendLine("room.conflicts=${room.conflictingSampleCount}")
		appendLine("room.comparableSamples=${room.comparableSampleCount}")
		appendLine("room.observedToDefinition=${ratio(room.observedToDefinitionCoverage)}")
		appendLine("room.definitionToObserved=${ratio(room.definitionToObservedCoverage)}")
		appendLine("room.totalDefinitionCoverage=${ratio(room.totalDefinitionCoverage)}")
		appendLine("room.candidateCount=${room.candidateCount}")
		appendLine("room.best=${room.bestCandidate ?: "<none>"}")
		appendLine("room.runnerUp=${room.runnerUpCandidate ?: "<none>"}")
		appendLine("room.score=${decimal(room.score)}")
		appendLine("room.runnerUpScore=${room.runnerUpScore?.let(::decimal) ?: "<none>"}")
		appendLine("room.margin=${room.margin?.let(::decimal) ?: "<none>"}")
		appendLine("room.matchId=${room.matchedRoomId ?: "<none>"}")
		appendLine("room.matchName=${room.matchedRoomName ?: "<none>"}")
		appendLine("room.fingerprint=${room.matchedFingerprintId ?: "<none>"}")
		appendLine("room.rotation=${room.rotation?.degrees?.toString() ?: "UNKNOWN"}")
		appendLine("room.cache=${room.cacheState.name}")
		appendLine("room.failure=${room.failureReason?.name ?: "<none>"}")
		appendLine("room.diagnostic=${room.diagnosticMessage.ifBlank { "<none>" }}")
	}

	private fun ratio(value: Double): String = "${decimal(value * 100.0)}%"

	private fun decimal(value: Double): String = String.format(Locale.ROOT, "%.4f", value)
}
