package com.andyy.funnymap.dungeon.capture

import com.andyy.funnymap.dungeon.model.RoomFootprint
import com.andyy.funnymap.dungeon.model.RoomId
import com.andyy.funnymap.dungeon.model.RoomType
import com.andyy.funnymap.dungeon.room.LocalBlockPosition
import com.andyy.funnymap.dungeon.room.RoomObservation
import com.andyy.funnymap.dungeon.room.RoomDefinition
import com.andyy.funnymap.dungeon.room.RotationIssue
import com.andyy.funnymap.dungeon.room.StructuralSample
import com.andyy.funnymap.dungeon.room.Identifiers
import java.time.Instant
import java.util.Collections

data class CaptureRoomMetadata(
	val roomId: RoomId,
	val displayName: String,
	val type: RoomType,
	val footprint: RoomFootprint,
	val secretCount: Int,
	val cryptCount: Int,
) {
	init {
		require(Identifiers.isStableRoomId(roomId.value)) { "Invalid stable room id: ${roomId.value}" }
		require(displayName.isNotBlank()) { "Capture room display name must not be blank" }
		require(type != RoomType.UNKNOWN) { "Capture room type must be explicit, not UNKNOWN" }
		require(footprint.anchor == com.andyy.funnymap.dungeon.model.GridPosition(0, 0)) {
			"Capture room footprint must be normalized to a zero minimum"
		}
		require(secretCount >= 0) { "Secret count must not be negative" }
		require(cryptCount >= 0) { "Crypt count must not be negative" }
	}
}

/** A sample rejected during capture, retained so policy decisions remain reviewable. */
class PolicyExcludedSample(
	val sample: StructuralSample,
	val reason: String,
	droppedProperties: Map<String, String> = emptyMap(),
) {
	val droppedProperties: Map<String, String> = immutableMap(droppedProperties.toSortedMap())

	init {
		require(reason.isNotBlank()) { "A policy exclusion reason must not be blank" }
		this.droppedProperties.forEach { (name, value) ->
			require(Identifiers.isPropertyName(name)) { "Invalid dropped block property name: $name" }
			require(Identifiers.isPropertyValue(value)) { "Invalid dropped value '$value' for property '$name'" }
			require(sample.properties[name] == value) {
				"Dropped property '$name' must retain the value from its source sample"
			}
		}
	}

	override fun equals(other: Any?): Boolean =
		other is PolicyExcludedSample &&
			sample == other.sample &&
			reason == other.reason &&
			droppedProperties == other.droppedProperties

	override fun hashCode(): Int = 31 * (31 * sample.hashCode() + reason.hashCode()) + droppedProperties.hashCode()
}

/**
 * Development-only capture artifact. [observation] is detached from Minecraft state and expressed
 * in the room's canonical orientation; its provenance retains the observed transform.
 */
class RawRoomCapture(
	val schemaVersion: Int,
	val captureId: String,
	val metadata: CaptureRoomMetadata,
	val observation: RoomObservation,
	val fingerprintPolicyVersion: String,
	policyExclusions: List<PolicyExcludedSample>,
	rotationIssues: List<RotationIssue> = emptyList(),
	val developerNotes: String?,
	val capturedAt: Instant,
	val integrityDigest: String,
) {
	val samples: List<StructuralSample>
		get() = observation.samples

	init {
		require(schemaVersion == CURRENT_SCHEMA_VERSION) {
			"Unsupported raw capture schema version $schemaVersion; expected $CURRENT_SCHEMA_VERSION"
		}
		require(CAPTURE_ID.matches(captureId)) {
			"Capture id must contain only letters, digits, '.', '_', ':', or '-'"
		}
		require(Identifiers.isFingerprintId(fingerprintPolicyVersion)) {
			"Invalid raw capture fingerprint policy version: $fingerprintPolicyVersion"
		}
		require(observation.fingerprintPolicyVersion == fingerprintPolicyVersion) {
			"Observation and raw capture fingerprint policies must match"
		}
		require(observation.observationId == captureId) {
			"Observation id must match raw capture id"
		}
		require(observation.footprint == metadata.footprint) {
			"Raw capture observation must use the canonical metadata footprint"
		}
		require(observation.observedRotation != null) {
			"Raw developer captures must record their observed rotation"
		}
		require(observation.provenance.captureId == captureId) {
			"Raw capture provenance must reference its capture id"
		}
		policyExclusions.forEach { exclusion ->
			require(exclusion.sample.position in observation.bounds) {
				"Policy exclusion ${exclusion.sample.position} lies outside observation bounds"
			}
			require(observation.coverage.isAvailable(exclusion.sample.position)) {
				"Policy exclusion ${exclusion.sample.position} lies outside available coverage"
			}
		}
		rotationIssues.forEach { issue ->
			require(issue.position in observation.bounds) {
				"Rotation issue ${issue.position} lies outside observation bounds"
			}
		}
		require(developerNotes?.isNotBlank() != false) { "Developer notes must be null or non-blank" }
		require(SHA_256.matches(integrityDigest)) { "Capture integrity digest must be a lowercase sha256 digest" }
	}

	val policyExclusions = immutableList(policyExclusions)
	val rotationIssues = immutableList(rotationIssues)

	override fun equals(other: Any?): Boolean =
		other is RawRoomCapture &&
			schemaVersion == other.schemaVersion &&
			captureId == other.captureId &&
			metadata == other.metadata &&
			observation == other.observation &&
			fingerprintPolicyVersion == other.fingerprintPolicyVersion &&
			policyExclusions == other.policyExclusions &&
			rotationIssues == other.rotationIssues &&
			developerNotes == other.developerNotes &&
			capturedAt == other.capturedAt &&
			integrityDigest == other.integrityDigest

	override fun hashCode(): Int = listOf(
		schemaVersion,
		captureId,
		metadata,
		observation,
		fingerprintPolicyVersion,
		policyExclusions,
		rotationIssues,
		developerNotes,
		capturedAt,
		integrityDigest,
	).hashCode()

	override fun toString(): String = "RawRoomCapture(captureId=$captureId, roomId=${metadata.roomId})"

	companion object {
		const val CURRENT_SCHEMA_VERSION = 1
		private val CAPTURE_ID = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")
		private val SHA_256 = Regex("sha256:[0-9a-f]{64}")
	}
}

data class BlockIdentifierChange(
	val position: LocalBlockPosition,
	val observations: Map<String, Set<String>>,
)

data class PropertyChange(
	val position: LocalBlockPosition,
	val blockId: String,
	val property: String,
	/** Property value to capture ids. A null key means the property was absent. */
	val observations: Map<String?, Set<String>>,
)

data class PresenceChange(
	val position: LocalBlockPosition,
	val presentCaptureIds: Set<String>,
	val absentCaptureIds: Set<String>,
)

data class PositionAvailability(
	val position: LocalBlockPosition,
	val availableCaptureIds: Set<String>,
	val unavailableCaptureIds: Set<String>,
)

data class InsufficientObservation(
	val position: LocalBlockPosition,
	val observationCount: Int,
	val requiredCount: Int,
) {
	init {
		require(observationCount >= 0) { "Observation count must not be negative" }
		require(requiredCount > 0) { "Required observation count must be positive" }
	}
}

data class CapturePolicyExclusion(
	val captureId: String,
	val exclusion: PolicyExcludedSample,
) {
	init {
		require(captureId.isNotBlank()) { "Capture id must not be blank" }
	}
}

data class CaptureComparisonConfig(
	val minimumPositionObservations: Int = 2,
	val requireSameGameDataVersion: Boolean = true,
) {
	init {
		require(minimumPositionObservations > 0) { "Minimum position observations must be positive" }
	}
}

class CaptureComparison(
	val roomId: RoomId,
	captureIds: Collection<String>,
	stableSamples: Collection<StructuralSample>,
	changedBlockIdentifiers: Collection<BlockIdentifierChange>,
	changedProperties: Collection<PropertyChange>,
	presenceChanges: Collection<PresenceChange>,
	unavailablePositions: Collection<PositionAvailability>,
	insufficientObservations: Collection<InsufficientObservation>,
	policyExclusions: Collection<CapturePolicyExclusion>,
	val coverage: Double,
	coverageByCapture: Map<String, Double>,
) {
	val captureIds = immutableSet(captureIds)
	val stableSamples = immutableList(stableSamples)
	val changedBlockIdentifiers = immutableList(changedBlockIdentifiers)
	val changedProperties = immutableList(changedProperties)
	val presenceChanges = immutableList(presenceChanges)
	val unavailablePositions = immutableList(unavailablePositions)
	val insufficientObservations = immutableList(insufficientObservations)
	val policyExclusions = immutableList(policyExclusions)
	val coverageByCapture = immutableMap(coverageByCapture.toSortedMap())

	init {
		require(captureIds.isNotEmpty()) { "A comparison requires at least one capture" }
		require(coverage.isFinite() && coverage in 0.0..1.0) { "Coverage must be between 0 and 1" }
	}
}

enum class FinalizationExclusionReason {
	BLOCK_IDENTIFIER_CHANGED,
	PROPERTY_CHANGED,
	PRESENCE_CHANGED,
	POSITION_UNAVAILABLE,
	INSUFFICIENT_OBSERVATIONS,
	EXCLUDED_BY_POLICY,
	INSUFFICIENT_CAPTURE_COVERAGE,
	UNRESOLVED_ROTATION_PROPERTY,
}

data class FinalizationExclusion(
	val position: LocalBlockPosition?,
	val reason: FinalizationExclusionReason,
	val detail: String,
) {
	init {
		require(detail.isNotBlank()) { "Finalization exclusion detail must not be blank" }
	}
}

data class FinalizationConfig(
	val minimumIndependentCaptures: Int = 3,
	val minimumObservationCoverage: Double = 0.85,
	val minimumStableSamples: Int = 32,
	val minimumPositionObservations: Int = 2,
) {
	init {
		require(minimumIndependentCaptures > 0) { "Minimum independent captures must be positive" }
		require(minimumObservationCoverage.isFinite() && minimumObservationCoverage in 0.0..1.0) {
			"Minimum observation coverage must be between 0 and 1"
		}
		require(minimumStableSamples > 0) { "Minimum stable samples must be positive" }
		require(minimumPositionObservations > 0) { "Minimum position observations must be positive" }
	}
}

enum class FinalizationStatus {
	READY_FOR_REVIEW,
	REJECTED,
}

class FinalizationReport(
	val status: FinalizationStatus,
	val comparison: CaptureComparison,
	acceptedSamples: Collection<StructuralSample>,
	exclusions: Collection<FinalizationExclusion>,
	contributingCaptureIds: Collection<String>,
	val finalDigest: String?,
	val fingerprintPolicyVersion: String,
	warnings: Collection<String>,
	val candidateDefinition: RoomDefinition?,
	val candidateJson: String?,
) {
	val acceptedSamples = immutableList(acceptedSamples)
	val exclusions = immutableList(exclusions)
	val contributingCaptureIds = immutableSet(contributingCaptureIds)
	val warnings = immutableList(warnings)

	init {
		require(fingerprintPolicyVersion.isNotBlank()) { "Fingerprint policy version must not be blank" }
		require((candidateDefinition == null) == (candidateJson == null)) {
			"Candidate definition and JSON must either both be present or both be absent"
		}
		require(status != FinalizationStatus.READY_FOR_REVIEW || candidateDefinition != null) {
			"A review-ready finalization must contain a candidate definition"
		}
	}
}

internal fun <T> immutableList(values: Collection<T>): List<T> =
	Collections.unmodifiableList(ArrayList(values))

internal fun <T> immutableSet(values: Collection<T>): Set<T> =
	Collections.unmodifiableSet(LinkedHashSet(values))

internal fun <K, V> immutableMap(values: Map<K, V>): Map<K, V> =
	Collections.unmodifiableMap(LinkedHashMap(values))
