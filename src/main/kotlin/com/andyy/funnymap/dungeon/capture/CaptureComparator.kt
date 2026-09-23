package com.andyy.funnymap.dungeon.capture

import com.andyy.funnymap.dungeon.room.LocalBlockPosition
import com.andyy.funnymap.dungeon.room.StructuralNormalizer
import com.andyy.funnymap.dungeon.room.StructuralSample
import java.util.TreeMap

object CaptureComparator {
	fun compare(
		captures: Collection<RawRoomCapture>,
		config: CaptureComparisonConfig = CaptureComparisonConfig(),
	): CaptureComparison {
		require(captures.isNotEmpty()) { "At least one raw capture is required for comparison" }
		val ordered = captures.sortedBy(RawRoomCapture::captureId)
		require(ordered.map(RawRoomCapture::captureId).distinct().size == ordered.size) {
			"Capture ids must be unique within a comparison"
		}
		ordered.forEach { capture ->
			require(RawCaptureIntegrity.verify(capture)) {
				"Raw capture '${capture.captureId}' failed its integrity check"
			}
		}
		val first = ordered.first()
		require(ordered.all { it.metadata == first.metadata }) {
			"All captures in a comparison must have identical canonical room metadata"
		}
		require(ordered.all { it.fingerprintPolicyVersion == first.fingerprintPolicyVersion }) {
			"All captures in a comparison must use the same fingerprint policy version"
		}
		require(ordered.all { it.observation.bounds == first.observation.bounds }) {
			"All captures in a comparison must use identical explicit structural bounds"
		}
		if (config.requireSameGameDataVersion) {
			require(ordered.all { it.observation.gameDataVersion == first.observation.gameDataVersion }) {
				"Captures with different game/data versions must be finalized as separate variants"
			}
		}

		val acceptedByCapture = ordered.associate { capture ->
			capture.captureId to capture.samples.associateBy(StructuralSample::position)
		}
		val rawByCapture = ordered.associate { capture ->
			val values = LinkedHashMap<LocalBlockPosition, StructuralSample>()
			capture.samples.forEach { values[it.position] = it }
			// Policy exclusions retain the original property map, including individually dropped values.
			capture.policyExclusions.forEach { values[it.sample.position] = it.sample }
			capture.captureId to values
		}
		val positions = rawByCapture.values.flatMap(Map<LocalBlockPosition, StructuralSample>::keys).toSortedSet()

		val stable = ArrayList<StructuralSample>()
		val blockChanges = ArrayList<BlockIdentifierChange>()
		val propertyChanges = ArrayList<PropertyChange>()
		val presenceChanges = ArrayList<PresenceChange>()
		val unavailable = ArrayList<PositionAvailability>()
		val insufficient = ArrayList<InsufficientObservation>()

		positions.forEach { position ->
			val availableCaptures = ordered.filter { it.observation.coverage.isAvailable(position) }
			val unavailableCaptures = ordered.filterNot { it.observation.coverage.isAvailable(position) }
			if (unavailableCaptures.isNotEmpty()) {
				unavailable += PositionAvailability(
					position = position,
					availableCaptureIds = immutableSet(availableCaptures.map(RawRoomCapture::captureId)),
					unavailableCaptureIds = immutableSet(unavailableCaptures.map(RawRoomCapture::captureId)),
				)
			}
			if (availableCaptures.size < config.minimumPositionObservations) {
				insufficient += InsufficientObservation(
					position = position,
					observationCount = availableCaptures.size,
					requiredCount = config.minimumPositionObservations,
				)
				return@forEach
			}

			val present = availableCaptures.filter { rawByCapture.getValue(it.captureId)[position] != null }
			val absent = availableCaptures - present.toSet()
			if (present.isNotEmpty() && absent.isNotEmpty()) {
				presenceChanges += PresenceChange(
					position = position,
					presentCaptureIds = immutableSet(present.map(RawRoomCapture::captureId)),
					absentCaptureIds = immutableSet(absent.map(RawRoomCapture::captureId)),
				)
				return@forEach
			}
			if (present.isEmpty()) return@forEach

			val rawSamples = present.associate { capture ->
				capture.captureId to rawByCapture.getValue(capture.captureId).getValue(position)
			}
			val identifiers = rawSamples.entries.groupBy({ it.value.blockId }, { it.key })
			if (identifiers.size > 1) {
				blockChanges += BlockIdentifierChange(
					position = position,
					observations = immutableNestedSets(identifiers),
				)
				return@forEach
			}

			val acceptedSamples = present.mapNotNull { capture ->
				acceptedByCapture.getValue(capture.captureId)[position]?.let { capture.captureId to it }
			}
			val blockId = rawSamples.values.first().blockId
			val rawPropertyNames = rawSamples.values.flatMap { it.properties.keys }.toSortedSet()
			rawPropertyNames.forEach { property ->
				val values = rawSamples.entries.groupBy(
					keySelector = { it.value.properties[property] },
					valueTransform = { it.key },
				)
				if (values.size > 1) {
					propertyChanges += PropertyChange(
						position = position,
						blockId = blockId,
						property = property,
						observations = immutableNullableNestedSets(values),
					)
				}
			}
			if (acceptedSamples.size != present.size) {
				// The position is consistently present but at least one policy decision excluded it.
				return@forEach
			}
			val propertyNames = acceptedSamples.flatMap { it.second.properties.keys }.toSortedSet()
			val stableProperties = TreeMap<String, String>()
			propertyNames.forEach { property ->
				val values = acceptedSamples.groupBy(
					keySelector = { it.second.properties[property] },
					valueTransform = { it.first },
				)
				if (values.size == 1 && values.keys.single() != null) {
					stableProperties[property] = values.keys.single()!!
				} else if (propertyChanges.none { it.position == position && it.property == property }) {
					propertyChanges += PropertyChange(
						position = position,
						blockId = blockId,
						property = property,
						observations = immutableNullableNestedSets(values),
					)
				}
			}
			stable += StructuralSample(position, blockId, stableProperties)
		}

		val coverageByCapture = ordered.associate { it.captureId to it.observation.coverage.coverageRatio }
		return CaptureComparison(
			roomId = first.metadata.roomId,
			captureIds = ordered.map(RawRoomCapture::captureId),
			stableSamples = StructuralNormalizer.normalizeSamples(stable),
			changedBlockIdentifiers = blockChanges,
			changedProperties = propertyChanges,
			presenceChanges = presenceChanges,
			unavailablePositions = unavailable,
			insufficientObservations = insufficient,
			policyExclusions = ordered.flatMap { capture ->
				capture.policyExclusions.map { CapturePolicyExclusion(capture.captureId, it) }
			},
			coverage = coverageByCapture.values.minOrNull() ?: 0.0,
			coverageByCapture = coverageByCapture,
		)
	}

	private fun immutableNestedSets(values: Map<String, List<String>>): Map<String, Set<String>> =
		immutableMap(values.toSortedMap().mapValues { immutableSet(it.value.sorted()) })

	private fun immutableNullableNestedSets(values: Map<String?, List<String>>): Map<String?, Set<String>> {
		val ordered = LinkedHashMap<String?, Set<String>>()
		values.entries.sortedWith(compareBy(nullsFirst()) { it.key }).forEach { (key, captureIds) ->
			ordered[key] = immutableSet(captureIds.sorted())
		}
		return immutableMap(ordered)
	}
}
