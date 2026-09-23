package com.andyy.funnymap.dungeon.capture

import com.andyy.funnymap.dungeon.room.FingerprintPolicy
import com.andyy.funnymap.dungeon.room.LocalBlockPosition
import com.andyy.funnymap.dungeon.room.RoomDataProvenance
import com.andyy.funnymap.dungeon.room.RoomDatabaseJson
import com.andyy.funnymap.dungeon.room.RoomDefinition
import com.andyy.funnymap.dungeon.room.RoomFingerprint
import com.andyy.funnymap.dungeon.room.StructuralNormalizer
import java.util.Locale

object CaptureFinalizer {
	fun finalize(
		captures: Collection<RawRoomCapture>,
		policy: FingerprintPolicy,
		fingerprintId: String = "base",
		config: FinalizationConfig = FinalizationConfig(),
	): FinalizationReport {
		require(captures.isNotEmpty()) { "At least one raw capture is required for finalization" }
		val ordered = captures.sortedBy(RawRoomCapture::captureId)
		require(ordered.all { it.fingerprintPolicyVersion == policy.version }) {
			"All captures must use finalization policy '${policy.version}'"
		}
		val comparison = CaptureComparator.compare(
			captures = ordered,
			config = CaptureComparisonConfig(
				minimumPositionObservations = config.minimumPositionObservations,
				requireSameGameDataVersion = true,
			),
		)
		val exclusions = buildExclusions(comparison, ordered, config)
		val warnings = ArrayList<String>()
		val minimumSamples = maxOf(config.minimumStableSamples, policy.minimumSamples)

		if (comparison.captureIds.size < config.minimumIndependentCaptures) {
			warnings += "Only ${comparison.captureIds.size} independent captures were supplied; " +
				"${config.minimumIndependentCaptures} are required"
		}
		if (comparison.coverage < config.minimumObservationCoverage) {
			warnings += "Minimum capture coverage ${formatRatio(comparison.coverage)} is below required " +
				formatRatio(config.minimumObservationCoverage)
		}
		if (comparison.stableSamples.size < minimumSamples) {
			warnings += "Only ${comparison.stableSamples.size} stable samples remain; $minimumSamples are required"
		}
		val rotationIssueCount = ordered.sumOf { it.rotationIssues.size }
		if (rotationIssueCount > 0) {
			warnings += "$rotationIssueCount directional-property rotation issue(s) require human resolution"
		}
		if (comparison.changedProperties.isNotEmpty()) {
			warnings += "${comparison.changedProperties.size} varying property value(s) were removed from stable samples"
		}
		if (comparison.changedBlockIdentifiers.isNotEmpty()) {
			warnings += "${comparison.changedBlockIdentifiers.size} position(s) changed block identifier and were excluded"
		}
		if (comparison.presenceChanges.isNotEmpty()) {
			warnings += "${comparison.presenceChanges.size} position(s) changed presence and were excluded"
		}
		if (comparison.unavailablePositions.isNotEmpty()) {
			warnings += "${comparison.unavailablePositions.size} relevant position(s) were unavailable in at least one capture"
		}
		if (comparison.policyExclusions.isNotEmpty()) {
			warnings += "${comparison.policyExclusions.size} capture sample decision(s) were excluded wholly or in part by policy"
		}

		val ready = comparison.captureIds.size >= config.minimumIndependentCaptures &&
			comparison.coverage >= config.minimumObservationCoverage &&
			comparison.stableSamples.size >= minimumSamples &&
			rotationIssueCount == 0
		val bounds = ordered.first().observation.bounds
		val digest = comparison.stableSamples.takeIf { it.isNotEmpty() }?.let {
			StructuralNormalizer.sha256(bounds, comparison.stableSamples)
		}
		val definition = if (ready) {
			val metadata = ordered.first().metadata
			val provenance = RoomDataProvenance(
				gameVersions = ordered.map { it.observation.gameDataVersion.gameVersion }.toSet(),
				dataVersions = ordered.mapNotNull { it.observation.gameDataVersion.dataVersion }.toSet(),
				captureIds = comparison.captureIds,
				notes = "Generated from reviewed raw captures; exclusions remain in the finalization report.",
			)
			val fingerprint = RoomFingerprint.create(
				id = fingerprintId,
				bounds = bounds,
				origin = LocalBlockPosition(0, 0, 0),
				samples = comparison.stableSamples,
				policyVersion = policy.version,
				provenance = provenance,
			)
			RoomDefinition(
				id = metadata.roomId,
				displayName = metadata.displayName,
				type = metadata.type,
				footprint = metadata.footprint,
				secretCount = metadata.secretCount,
				cryptCount = metadata.cryptCount,
				fingerprints = listOf(fingerprint),
				provenance = provenance,
			)
		} else {
			null
		}
		return FinalizationReport(
			status = if (ready) FinalizationStatus.READY_FOR_REVIEW else FinalizationStatus.REJECTED,
			comparison = comparison,
			acceptedSamples = comparison.stableSamples,
			exclusions = exclusions,
			contributingCaptureIds = comparison.captureIds,
			finalDigest = digest,
			fingerprintPolicyVersion = policy.version,
			warnings = warnings,
			candidateDefinition = definition,
			candidateJson = definition?.let {
				RoomDatabaseJson.encodeCandidateDatabase(
					definition = it,
					fingerprintPolicyVersion = policy.version,
				)
			},
		)
	}

	private fun buildExclusions(
		comparison: CaptureComparison,
		captures: Collection<RawRoomCapture>,
		config: FinalizationConfig,
	): List<FinalizationExclusion> = buildList {
		comparison.changedBlockIdentifiers.forEach { change ->
			add(
				FinalizationExclusion(
					position = change.position,
					reason = FinalizationExclusionReason.BLOCK_IDENTIFIER_CHANGED,
					detail = change.observations.entries.joinToString { (block, ids) -> "$block in ${ids.sorted()}" },
				),
			)
		}
		comparison.changedProperties.forEach { change ->
			add(
				FinalizationExclusion(
					position = change.position,
					reason = FinalizationExclusionReason.PROPERTY_CHANGED,
					detail = "${change.blockId} property '${change.property}' varied: ${change.observations}",
				),
			)
		}
		comparison.presenceChanges.forEach { change ->
			add(
				FinalizationExclusion(
					position = change.position,
					reason = FinalizationExclusionReason.PRESENCE_CHANGED,
					detail = "Present in ${change.presentCaptureIds.sorted()}, absent in ${change.absentCaptureIds.sorted()}",
				),
			)
		}
		comparison.unavailablePositions.forEach { availability ->
			add(
				FinalizationExclusion(
					position = availability.position,
					reason = FinalizationExclusionReason.POSITION_UNAVAILABLE,
					detail = "Unavailable in ${availability.unavailableCaptureIds.sorted()}",
				),
			)
		}
		comparison.insufficientObservations.forEach { insufficient ->
			add(
				FinalizationExclusion(
					position = insufficient.position,
					reason = FinalizationExclusionReason.INSUFFICIENT_OBSERVATIONS,
					detail = "Observed ${insufficient.observationCount} time(s); ${insufficient.requiredCount} required",
				),
			)
		}
		comparison.policyExclusions.forEach { captured ->
			add(
				FinalizationExclusion(
					position = captured.exclusion.sample.position,
					reason = FinalizationExclusionReason.EXCLUDED_BY_POLICY,
					detail = "${captured.captureId}: ${captured.exclusion.reason}" +
						captured.exclusion.droppedProperties.keys.takeIf(Set<String>::isNotEmpty)
							?.let { " (properties ${it.sorted()})" }.orEmpty(),
				),
			)
		}
		captures.filter { it.observation.coverage.coverageRatio < config.minimumObservationCoverage }
			.forEach { capture ->
				add(
					FinalizationExclusion(
						position = null,
						reason = FinalizationExclusionReason.INSUFFICIENT_CAPTURE_COVERAGE,
						detail = "${capture.captureId}: ${formatRatio(capture.observation.coverage.coverageRatio)} coverage",
					),
				)
			}
		captures.forEach { capture ->
			capture.rotationIssues.forEach { issue ->
				add(
					FinalizationExclusion(
						position = issue.position,
						reason = FinalizationExclusionReason.UNRESOLVED_ROTATION_PROPERTY,
						detail = "${capture.captureId}: ${issue.propertyName}=${issue.propertyValue} (${issue.reason})",
					),
				)
			}
		}
	}

	private fun formatRatio(value: Double): String = "%.1f%%".format(Locale.ROOT, value * 100.0)
}
