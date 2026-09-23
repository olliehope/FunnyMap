package com.andyy.funnymap.dungeon.capture

import com.andyy.funnymap.dungeon.model.RoomRotation
import com.andyy.funnymap.dungeon.room.CoverageRegion
import com.andyy.funnymap.dungeon.room.FingerprintPolicy
import com.andyy.funnymap.dungeon.room.LocalBlockBounds
import com.andyy.funnymap.dungeon.room.LocalBlockPosition
import com.andyy.funnymap.dungeon.room.ObservationCoverage
import com.andyy.funnymap.dungeon.room.ObservationProvenance
import com.andyy.funnymap.dungeon.room.PolicyDecision
import com.andyy.funnymap.dungeon.room.RoomLocalDatum
import com.andyy.funnymap.dungeon.room.RoomObservation
import com.andyy.funnymap.dungeon.room.StructuralNormalizer
import com.andyy.funnymap.dungeon.room.UnavailableRegion
import com.andyy.funnymap.dungeon.room.WorldCoordinate
import com.andyy.funnymap.dungeon.room.inverse
import java.time.Clock
import java.time.Instant

object RawCaptureFactory {
	fun create(
		captureId: String,
		metadata: CaptureRoomMetadata,
		observation: RoomObservation,
		policy: FingerprintPolicy,
		developerNotes: String? = null,
		clock: Clock = Clock.systemUTC(),
	): RawRoomCapture = createAt(
		captureId = captureId,
		metadata = metadata,
		observation = observation,
		policy = policy,
		developerNotes = developerNotes,
		capturedAt = clock.instant(),
	)

	fun createAt(
		captureId: String,
		metadata: CaptureRoomMetadata,
		observation: RoomObservation,
		policy: FingerprintPolicy,
		developerNotes: String? = null,
		capturedAt: Instant,
	): RawRoomCapture {
		require(observation.fingerprintPolicyVersion == policy.version) {
			"Observation policy '${observation.fingerprintPolicyVersion}' does not match capture policy '${policy.version}'"
		}
		val observedRotation = requireNotNull(observation.observedRotation) {
			"Developer capture '$captureId' requires an explicit observed rotation"
		}
		val expectedObservedFootprint = StructuralNormalizer.rotateFootprint(metadata.footprint, observedRotation)
		require(observation.footprint == expectedObservedFootprint) {
			"Observed footprint ${observation.footprint.localCells} does not match canonical footprint " +
				"${metadata.footprint.localCells} rotated ${observedRotation.degrees} degrees"
		}

		val canonicalRotation = StructuralNormalizer.rotate(
			samples = observation.samples,
			bounds = observation.bounds,
			rotation = observedRotation.inverse(),
			policy = policy,
		)
		val accepted = ArrayList<com.andyy.funnymap.dungeon.room.StructuralSample>()
		val exclusions = ArrayList<PolicyExcludedSample>()
		canonicalRotation.samples.forEach { sample ->
			when (val decision = policy.apply(sample)) {
				is PolicyDecision.Accepted -> {
					accepted += decision.sample
					if (decision.droppedProperties.isNotEmpty()) {
						exclusions += PolicyExcludedSample(
							sample = sample,
							reason = "Properties do not participate in policy ${policy.version}",
							droppedProperties = sample.properties.filterKeys(decision.droppedProperties::contains),
						)
					}
				}
				is PolicyDecision.Excluded -> exclusions += PolicyExcludedSample(
					sample = decision.sample,
					reason = decision.reason.name,
				)
			}
		}

		val inverse = observedRotation.inverse()
		val canonicalCoverage = rotateCoverage(observation.coverage, observation.bounds, inverse)
		val canonicalFootprint = StructuralNormalizer.rotateFootprint(observation.footprint, inverse)
		require(canonicalFootprint == metadata.footprint) {
			"Canonicalized observation footprint does not equal capture metadata footprint"
		}
		val canonicalObservation = RoomObservation(
			observationId = observation.observationId,
			worldSessionGeneration = observation.worldSessionGeneration,
			revision = observation.revision,
			gameDataVersion = observation.gameDataVersion,
			fingerprintPolicyVersion = policy.version,
			footprint = canonicalFootprint,
			bounds = canonicalRotation.bounds,
			datum = rotateDatum(observation.datum, observation.bounds, inverse),
			samples = accepted,
			coverage = canonicalCoverage,
			provenance = ObservationProvenance(
				source = observation.provenance.source,
				captureId = captureId,
				notes = observation.provenance.notes,
				attributes = observation.provenance.attributes,
			),
			observedRotation = observedRotation,
		)

		val unsigned = UnsignedRawCapture(
			schemaVersion = RawRoomCapture.CURRENT_SCHEMA_VERSION,
			captureId = captureId,
			metadata = metadata,
			observation = canonicalObservation,
			fingerprintPolicyVersion = policy.version,
			policyExclusions = exclusions,
			rotationIssues = canonicalRotation.issues,
			developerNotes = developerNotes,
			capturedAt = capturedAt,
		)
		return unsigned.toCapture(RawCaptureIntegrity.sha256(unsigned))
	}

	private fun rotateCoverage(
		coverage: ObservationCoverage,
		bounds: LocalBlockBounds,
		rotation: RoomRotation,
	): ObservationCoverage = ObservationCoverage(
		availableRegions = coverage.availableRegions.map { region ->
			CoverageRegion(
				bounds = rotateSubBounds(region.bounds, bounds, rotation),
				sourceChunkX = region.sourceChunkX,
				sourceChunkZ = region.sourceChunkZ,
			)
		},
		unavailableRegions = coverage.unavailableRegions.map { unavailable ->
			UnavailableRegion(
				region = CoverageRegion(
					bounds = rotateSubBounds(unavailable.region.bounds, bounds, rotation),
					sourceChunkX = unavailable.region.sourceChunkX,
					sourceChunkZ = unavailable.region.sourceChunkZ,
				),
				reason = unavailable.reason,
			)
		},
		expectedPositions = coverage.expectedPositions.map { rotatePosition(it, bounds, rotation) },
	)

	private fun rotateSubBounds(
		region: LocalBlockBounds,
		captureBounds: LocalBlockBounds,
		rotation: RoomRotation,
	): LocalBlockBounds {
		require(region.min in captureBounds && region.max in captureBounds) {
			"Coverage region $region lies outside capture bounds $captureBounds"
		}
		val corners = listOf(
			LocalBlockPosition(region.min.x, region.min.y, region.min.z),
			LocalBlockPosition(region.min.x, region.min.y, region.max.z),
			LocalBlockPosition(region.max.x, region.max.y, region.min.z),
			LocalBlockPosition(region.max.x, region.max.y, region.max.z),
		).map { rotatePosition(it, captureBounds, rotation) }
		return LocalBlockBounds(
			min = LocalBlockPosition(corners.minOf { it.x }, corners.minOf { it.y }, corners.minOf { it.z }),
			max = LocalBlockPosition(corners.maxOf { it.x }, corners.maxOf { it.y }, corners.maxOf { it.z }),
		)
	}

	private fun rotatePosition(
		position: LocalBlockPosition,
		bounds: LocalBlockBounds,
		rotation: RoomRotation,
	): LocalBlockPosition {
		require(position in bounds) { "Position $position lies outside capture bounds $bounds" }
		val relativeX = position.x - bounds.min.x
		val relativeZ = position.z - bounds.min.z
		return when (rotation) {
			RoomRotation.DEGREES_0 -> LocalBlockPosition(relativeX, position.y, relativeZ)
			RoomRotation.DEGREES_90 -> LocalBlockPosition(bounds.depth - 1 - relativeZ, position.y, relativeX)
			RoomRotation.DEGREES_180 -> LocalBlockPosition(
				bounds.width - 1 - relativeX,
				position.y,
				bounds.depth - 1 - relativeZ,
			)
			RoomRotation.DEGREES_270 -> LocalBlockPosition(relativeZ, position.y, bounds.width - 1 - relativeX)
		}
	}

	private fun rotateDatum(
		datum: RoomLocalDatum,
		bounds: LocalBlockBounds,
		rotation: RoomRotation,
	): RoomLocalDatum {
		val sourceForCanonicalOrigin = when (rotation) {
			RoomRotation.DEGREES_0 -> LocalBlockPosition(bounds.min.x, 0, bounds.min.z)
			RoomRotation.DEGREES_90 -> LocalBlockPosition(bounds.min.x, 0, bounds.max.z)
			RoomRotation.DEGREES_180 -> LocalBlockPosition(bounds.max.x, 0, bounds.max.z)
			RoomRotation.DEGREES_270 -> LocalBlockPosition(bounds.max.x, 0, bounds.min.z)
		}
		return RoomLocalDatum(
			worldOrigin = WorldCoordinate(
				x = datum.worldOrigin.x + sourceForCanonicalOrigin.x - bounds.min.x,
				y = datum.worldOrigin.y,
				z = datum.worldOrigin.z + sourceForCanonicalOrigin.z - bounds.min.z,
			),
			verticalDatum = datum.verticalDatum,
			horizontalDatum = datum.horizontalDatum,
		)
	}
}
