package com.andyy.funnymap.dungeon.model

@JvmInline
value class RoomId(val value: String) {
	init {
		require(value.isNotBlank()) { "Room id must not be blank" }
	}

	override fun toString(): String = value
}

enum class RoomType {
	ENTRANCE,
	NORMAL,
	PUZZLE,
	TRAP,
	MINIBOSS,
	FAIRY,
	BLOOD,
	BOSS,
	UNKNOWN,
}

enum class RoomRotation(val degrees: Int) {
	DEGREES_0(0),
	DEGREES_90(90),
	DEGREES_180(180),
	DEGREES_270(270),
}

sealed interface RoomOrientation {
	data class Known(val rotation: RoomRotation) : RoomOrientation

	data object Unknown : RoomOrientation
}

enum class RoomCompletion {
	UNKNOWN,
	UNDISCOVERED,
	DISCOVERED,
	CLEARED,
	COMPLETED,
	FAILED,
}

enum class RecognitionUnknownReason {
	CHUNK_UNAVAILABLE,
	INSUFFICIENT_EVIDENCE,
	NO_DATABASE_MATCH,
	AMBIGUOUS_MATCH,
	UNSUPPORTED_SHAPE,
}

data class RecognitionEvidence(
	val observedSampleCount: Int = 0,
	val definitionSampleCount: Int = 0,
	val matchedSampleCount: Int = 0,
	val conflictingSampleCount: Int = 0,
	val unavailableSampleCount: Int = 0,
	val candidateCount: Int = 0,
	val observationCoverage: Double = 0.0,
	val definitionCoverage: Double = 0.0,
	val comparableSampleCount: Int = matchedSampleCount + conflictingSampleCount,
	val availableCoverage: Double = 0.0,
	val totalDefinitionCoverage: Double = 0.0,
	val observedToDefinitionCoverage: Double = observationCoverage,
	val definitionToObservedCoverage: Double = definitionCoverage,
	val candidateRotation: RoomRotation? = null,
	val candidateScore: Double = 0.0,
	val runnerUpScore: Double? = null,
	val scoreMargin: Double? = null,
) {
	init {
		require(observedSampleCount >= 0) { "Observed sample count must not be negative" }
		require(definitionSampleCount >= 0) { "Definition sample count must not be negative" }
		require(matchedSampleCount >= 0) { "Matched sample count must not be negative" }
		require(conflictingSampleCount >= 0) { "Conflicting sample count must not be negative" }
		require(unavailableSampleCount >= 0) { "Unavailable sample count must not be negative" }
		require(candidateCount >= 0) { "Candidate count must not be negative" }
		require(comparableSampleCount >= 0) { "Comparable sample count must not be negative" }
		require(matchedSampleCount <= observedSampleCount) {
			"Matched sample count cannot exceed observed sample count"
		}
		require(matchedSampleCount <= definitionSampleCount) {
			"Matched sample count cannot exceed definition sample count"
		}
		require(comparableSampleCount >= matchedSampleCount + conflictingSampleCount) {
			"Comparable samples cannot be fewer than matched and conflicting samples"
		}
		require(comparableSampleCount <= definitionSampleCount) {
			"Comparable samples cannot exceed definition sample count"
		}
		require(observationCoverage.isValidUnitInterval()) {
			"Observation coverage must be finite and between 0 and 1"
		}
		require(definitionCoverage.isValidUnitInterval()) {
			"Definition coverage must be finite and between 0 and 1"
		}
		require(availableCoverage.isValidUnitInterval()) {
			"Available coverage must be finite and between 0 and 1"
		}
		require(totalDefinitionCoverage.isValidUnitInterval()) {
			"Total definition coverage must be finite and between 0 and 1"
		}
		require(observedToDefinitionCoverage.isValidUnitInterval()) {
			"Observed-to-definition coverage must be finite and between 0 and 1"
		}
		require(definitionToObservedCoverage.isValidUnitInterval()) {
			"Definition-to-observed coverage must be finite and between 0 and 1"
		}
		require(candidateScore.isValidScore()) { "Candidate score must be finite and non-negative" }
		require(runnerUpScore == null || runnerUpScore.isValidScore()) {
			"Runner-up score must be finite and non-negative"
		}
		require(scoreMargin == null || scoreMargin.isValidScore()) {
			"Score margin must be finite and non-negative"
		}
	}
}

sealed interface RoomRecognition {
	val confidence: Double
	val evidence: RecognitionEvidence

	data class Known(
		val definitionId: String,
		val roomName: String,
		val fingerprintId: String? = null,
		override val confidence: Double,
		override val evidence: RecognitionEvidence,
	) : RoomRecognition {
		init {
			require(definitionId.isNotBlank()) { "Room definition id must not be blank" }
			require(roomName.isNotBlank()) { "Room name must not be blank" }
			require(confidence.isValidUnitInterval()) { "Confidence must be finite and between 0 and 1" }
		}
	}

	data class Unknown(
		val reason: RecognitionUnknownReason,
		override val confidence: Double = 0.0,
		override val evidence: RecognitionEvidence = RecognitionEvidence(),
	) : RoomRecognition {
		init {
			require(confidence.isValidUnitInterval()) { "Confidence must be finite and between 0 and 1" }
		}
	}
}

data class DungeonRoom(
	val id: RoomId,
	val footprint: RoomFootprint,
	val type: RoomType = RoomType.UNKNOWN,
	val orientation: RoomOrientation = RoomOrientation.Unknown,
	val completion: RoomCompletion = RoomCompletion.UNKNOWN,
	val secretCount: Int? = null,
	val cryptCount: Int? = null,
	val recognition: RoomRecognition = RoomRecognition.Unknown(
		reason = RecognitionUnknownReason.INSUFFICIENT_EVIDENCE,
	),
)

private fun Double.isValidUnitInterval(): Boolean = isFinite() && this in 0.0..1.0

private fun Double.isValidScore(): Boolean = isFinite() && this >= 0.0
