package com.andyy.funnymap.dungeon.match

import com.andyy.funnymap.dungeon.model.RecognitionEvidence
import com.andyy.funnymap.dungeon.model.RecognitionUnknownReason
import com.andyy.funnymap.dungeon.model.RoomFootprint
import com.andyy.funnymap.dungeon.model.RoomOrientation
import com.andyy.funnymap.dungeon.model.RoomRecognition
import com.andyy.funnymap.dungeon.model.RoomRotation
import com.andyy.funnymap.dungeon.room.PolicyDecision
import com.andyy.funnymap.dungeon.room.RoomDatabase
import com.andyy.funnymap.dungeon.room.RoomDefinition
import com.andyy.funnymap.dungeon.room.RoomObservation
import com.andyy.funnymap.dungeon.room.StructuralSample
import java.util.Collections

data class RoomMatchConfig(
	val minimumObservedSamples: Int = 8,
	val minimumMatchedSamples: Int = 8,
	val minimumObservationCoverage: Double = 0.50,
	val minimumAvailableDefinitionCoverage: Double = 0.60,
	val minimumObservedToDefinitionCoverage: Double = 0.70,
	val minimumDefinitionToObservedCoverage: Double = 0.85,
	val minimumTotalDefinitionCoverage: Double = 0.55,
	val maximumConflictRatio: Double = 0.15,
	val minimumScore: Double = 0.72,
	val minimumWinnerMargin: Double = 0.08,
	val orientationTieTolerance: Double = 0.000_001,
	val shortlistLimit: Int = RoomCandidateIndex.DEFAULT_LIMIT,
	val minimumShortlistTokenMatches: Int = 1,
) {
	init {
		require(minimumObservedSamples > 0) { "Minimum observed samples must be positive" }
		require(minimumMatchedSamples > 0) { "Minimum matched samples must be positive" }
		listOf(
			minimumObservationCoverage,
			minimumAvailableDefinitionCoverage,
			minimumObservedToDefinitionCoverage,
			minimumDefinitionToObservedCoverage,
			minimumTotalDefinitionCoverage,
			maximumConflictRatio,
			minimumScore,
			minimumWinnerMargin,
			orientationTieTolerance,
		).forEach { require(it.isFinite() && it in 0.0..1.0) { "Matcher ratios must be between 0 and 1" } }
		require(shortlistLimit > 0) { "Shortlist limit must be positive" }
		require(minimumShortlistTokenMatches > 0) { "Minimum shortlist token matches must be positive" }
	}
}

data class CandidateVerification(
	val candidate: CandidateView,
	val evidence: RecognitionEvidence,
	val conflictRatio: Double,
	val accepted: Boolean,
)

class RoomMatchDiagnostics(
	val indexedObservedSamples: Int,
	val shortlistSize: Int,
	val evaluatedCandidateCount: Int,
	val bestCandidate: CandidateViewKey?,
	val runnerUpCandidate: CandidateViewKey?,
	val message: String,
	evaluations: Collection<CandidateVerification> = emptyList(),
) {
	val evaluations: List<CandidateVerification> =
		Collections.unmodifiableList(evaluations.sortedWith(verificationComparator))

	fun withMessage(message: String): RoomMatchDiagnostics = RoomMatchDiagnostics(
		indexedObservedSamples,
		shortlistSize,
		evaluatedCandidateCount,
		bestCandidate,
		runnerUpCandidate,
		message,
		evaluations,
	)
}

sealed interface RoomMatchResult {
	val observationId: String
	val worldSessionGeneration: Long
	val observationRevision: Long
	val recognition: RoomRecognition
	val diagnostics: RoomMatchDiagnostics

	data class Known(
		override val observationId: String,
		override val worldSessionGeneration: Long,
		override val observationRevision: Long,
		val definition: RoomDefinition,
		val fingerprintId: String,
		val equivalentFingerprintIds: Set<String>,
		val orientation: RoomOrientation,
		val observedFootprint: RoomFootprint,
		val primaryCandidate: CandidateVerification,
		override val recognition: RoomRecognition.Known,
		override val diagnostics: RoomMatchDiagnostics,
	) : RoomMatchResult

	data class Unknown(
		override val observationId: String,
		override val worldSessionGeneration: Long,
		override val observationRevision: Long,
		val reason: RecognitionUnknownReason,
		override val recognition: RoomRecognition.Unknown,
		override val diagnostics: RoomMatchDiagnostics,
	) : RoomMatchResult
}

class RoomMatcher(
	private val database: RoomDatabase,
	val config: RoomMatchConfig = RoomMatchConfig(),
) {
	fun match(observation: RoomObservation): RoomMatchResult {
		if (observation.fingerprintPolicyVersion != database.fingerprintPolicyVersion) {
			return failure(
				observation,
				RecognitionUnknownReason.INSUFFICIENT_EVIDENCE,
				RecognitionEvidence(observedSampleCount = observation.samples.size),
				RoomMatchDiagnostics(0, 0, 0, null, null, "Fingerprint policy mismatch"),
			)
		}

		val acceptedObservationSamples = observation.samples.mapNotNull { sample ->
			when (val decision = database.candidateIndex.policy.apply(sample)) {
				is PolicyDecision.Accepted -> decision.sample
				is PolicyDecision.Excluded -> null
			}
		}
		if (acceptedObservationSamples.size < config.minimumObservedSamples) {
			val reason = if (observation.coverage.unavailableRegions.isNotEmpty()) {
				RecognitionUnknownReason.CHUNK_UNAVAILABLE
			} else {
				RecognitionUnknownReason.INSUFFICIENT_EVIDENCE
			}
			return failure(
				observation,
				reason,
				RecognitionEvidence(
					observedSampleCount = acceptedObservationSamples.size,
					observationCoverage = observation.coverage.coverageRatio,
					availableCoverage = observation.coverage.coverageRatio,
				),
				RoomMatchDiagnostics(
					acceptedObservationSamples.size, 0, 0, null, null,
					"Observation contains fewer than ${config.minimumObservedSamples} policy-eligible samples",
				),
			)
		}

		val shortlist = database.candidateIndex.shortlist(
			observation = observation,
			limit = config.shortlistLimit,
			minimumTokenMatches = config.minimumShortlistTokenMatches,
		)
		if (shortlist.hits.isEmpty()) {
			return failure(
				observation,
				RecognitionUnknownReason.NO_DATABASE_MATCH,
				RecognitionEvidence(
					observedSampleCount = acceptedObservationSamples.size,
					observationCoverage = observation.coverage.coverageRatio,
					availableCoverage = observation.coverage.coverageRatio,
				),
				RoomMatchDiagnostics(
					shortlist.indexedObservedTokenCount, 0, 0, null, null,
					if (database.definitions.isEmpty()) "Room database is empty" else "No indexed candidate shares the observed evidence",
				),
			)
		}

		val observedByPosition = acceptedObservationSamples.associateBy(StructuralSample::position)
		val evaluations = shortlist.hits.map { hit ->
			verify(observation, observedByPosition, acceptedObservationSamples.size, hit.candidate)
		}.sortedWith(verificationComparator)
		val bestByIdentity = evaluations.groupBy { it.candidate.definition.id }
			.values.map { candidates -> candidates.minWith(verificationComparator) }
			.sortedWith(verificationComparator)
		val best = bestByIdentity.first()
		val runnerUp = bestByIdentity.getOrNull(1)
		val margin = runnerUp?.let { (best.evidence.candidateScore - it.evidence.candidateScore).coerceAtLeast(0.0) }
		val bestEvidence = best.evidence.withCompetition(evaluations.size, runnerUp?.evidence?.candidateScore, margin)
		val bestWithCompetition = best.copy(evidence = bestEvidence)
		val runnerKey = runnerUp?.candidate?.key
		val diagnostics = RoomMatchDiagnostics(
			indexedObservedSamples = shortlist.indexedObservedTokenCount,
			shortlistSize = shortlist.hits.size,
			evaluatedCandidateCount = evaluations.size,
			bestCandidate = best.candidate.key,
			runnerUpCandidate = runnerKey,
			message = if (best.accepted) "Candidate passed explicit bidirectional verification" else "Best candidate failed acceptance thresholds",
			evaluations = evaluations,
		)

		if (!best.accepted) {
			val unavailableBlocked = observation.coverage.unavailableRegions.isNotEmpty() &&
				(best.evidence.availableCoverage < config.minimumAvailableDefinitionCoverage ||
					best.evidence.totalDefinitionCoverage < config.minimumTotalDefinitionCoverage)
			val reason = if (unavailableBlocked) RecognitionUnknownReason.CHUNK_UNAVAILABLE
			else RecognitionUnknownReason.NO_DATABASE_MATCH
			return failure(observation, reason, bestEvidence, diagnostics)
		}
		if (runnerUp != null && margin != null && margin < config.minimumWinnerMargin) {
			return failure(
				observation,
				RecognitionUnknownReason.AMBIGUOUS_MATCH,
				bestEvidence,
				diagnostics.withMessage("Winner margin $margin is below ${config.minimumWinnerMargin}"),
			)
		}

		val identityCandidates = evaluations.filter {
			it.accepted && it.candidate.definition.id == best.candidate.definition.id &&
				kotlin.math.abs(it.evidence.candidateScore - best.evidence.candidateScore) <= config.orientationTieTolerance
		}
		val rotations = identityCandidates.map { it.candidate.key.rotation }.toSet()
		val orientation = rotations.singleOrNull()?.let(RoomOrientation::Known) ?: RoomOrientation.Unknown
		val fingerprints = identityCandidates.map { it.candidate.key.fingerprintId }.toSortedSet()
		val recognition = RoomRecognition.Known(
			definitionId = best.candidate.definition.id.value,
			roomName = best.candidate.definition.displayName,
			fingerprintId = best.candidate.key.fingerprintId,
			confidence = bestEvidence.candidateScore.coerceIn(0.0, 1.0),
			evidence = bestEvidence,
		)
		return RoomMatchResult.Known(
			observationId = observation.observationId,
			worldSessionGeneration = observation.worldSessionGeneration,
			observationRevision = observation.revision,
			definition = best.candidate.definition,
			fingerprintId = best.candidate.key.fingerprintId,
			equivalentFingerprintIds = Collections.unmodifiableSet(fingerprints),
			orientation = orientation,
			observedFootprint = best.candidate.footprint,
			primaryCandidate = bestWithCompetition,
			recognition = recognition,
			diagnostics = diagnostics,
		)
	}

	private fun verify(
		observation: RoomObservation,
		observedByPosition: Map<com.andyy.funnymap.dungeon.room.LocalBlockPosition, StructuralSample>,
		observedSampleCount: Int,
		candidate: CandidateView,
	): CandidateVerification {
		val availableDefinitionSamples = candidate.samples.filter { observation.coverage.isAvailable(it.position) }
		var matched = 0
		for (expected in availableDefinitionSamples) {
			val observed = observedByPosition[expected.position]
			if (observed != null && samplesMatch(observed, expected)) matched++
		}
		val conflicts = availableDefinitionSamples.size - matched
		val comparable = availableDefinitionSamples.size
		val observedCoverage = ratio(matched, observedSampleCount)
		val definitionCoverage = ratio(matched, comparable)
		val availableCoverage = ratio(comparable, candidate.samples.size)
		val totalCoverage = ratio(matched, candidate.samples.size)
		val conflictRatio = ratio(conflicts, comparable)
		val score = (
			observedCoverage * OBSERVED_WEIGHT +
				definitionCoverage * DEFINITION_WEIGHT +
				totalCoverage * TOTAL_WEIGHT +
				(1.0 - conflictRatio) * CONFLICT_WEIGHT
			).coerceIn(0.0, 1.0)
		val evidence = RecognitionEvidence(
			observedSampleCount = observedSampleCount,
			definitionSampleCount = candidate.samples.size,
			matchedSampleCount = matched,
			conflictingSampleCount = conflicts,
			unavailableSampleCount = candidate.samples.size - comparable,
			candidateCount = 1,
			observationCoverage = observation.coverage.coverageRatio,
			definitionCoverage = definitionCoverage,
			comparableSampleCount = comparable,
			availableCoverage = availableCoverage,
			totalDefinitionCoverage = totalCoverage,
			observedToDefinitionCoverage = observedCoverage,
			definitionToObservedCoverage = definitionCoverage,
			candidateRotation = candidate.key.rotation,
			candidateScore = score,
		)
		val accepted = matched >= config.minimumMatchedSamples &&
			observation.coverage.coverageRatio >= config.minimumObservationCoverage &&
			availableCoverage >= config.minimumAvailableDefinitionCoverage &&
			observedCoverage >= config.minimumObservedToDefinitionCoverage &&
			definitionCoverage >= config.minimumDefinitionToObservedCoverage &&
			totalCoverage >= config.minimumTotalDefinitionCoverage &&
			conflictRatio <= config.maximumConflictRatio &&
			score >= config.minimumScore
		return CandidateVerification(candidate, evidence, conflictRatio, accepted)
	}

	private fun samplesMatch(observed: StructuralSample, expected: StructuralSample): Boolean =
		observed.blockId == expected.blockId && expected.properties.all { (key, value) -> observed.properties[key] == value }

	private fun failure(
		observation: RoomObservation,
		reason: RecognitionUnknownReason,
		evidence: RecognitionEvidence,
		diagnostics: RoomMatchDiagnostics,
	): RoomMatchResult.Unknown = RoomMatchResult.Unknown(
		observationId = observation.observationId,
		worldSessionGeneration = observation.worldSessionGeneration,
		observationRevision = observation.revision,
		reason = reason,
		recognition = RoomRecognition.Unknown(reason, evidence = evidence),
		diagnostics = diagnostics,
	)

	private companion object {
		const val OBSERVED_WEIGHT = 0.35
		const val DEFINITION_WEIGHT = 0.35
		const val TOTAL_WEIGHT = 0.20
		const val CONFLICT_WEIGHT = 0.10
	}
}

private fun RecognitionEvidence.withCompetition(
	candidateCount: Int,
	runnerUpScore: Double?,
	margin: Double?,
): RecognitionEvidence = copy(
	candidateCount = candidateCount,
	runnerUpScore = runnerUpScore,
	scoreMargin = margin,
)

private fun ratio(numerator: Int, denominator: Int): Double =
	if (denominator == 0) 0.0 else numerator.toDouble() / denominator

private val verificationComparator = compareByDescending<CandidateVerification> { it.evidence.candidateScore }
	.thenByDescending { it.evidence.matchedSampleCount }
	.thenBy { it.evidence.conflictingSampleCount }
	.thenBy { it.candidate.key.roomId.value }
	.thenBy { it.candidate.key.fingerprintId }
	.thenBy { it.candidate.key.rotation.ordinal }
