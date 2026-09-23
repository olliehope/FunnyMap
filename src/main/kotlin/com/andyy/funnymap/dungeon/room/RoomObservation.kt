package com.andyy.funnymap.dungeon.room

import com.andyy.funnymap.dungeon.model.GridPosition
import com.andyy.funnymap.dungeon.model.RoomFootprint
import com.andyy.funnymap.dungeon.model.RoomRotation
import java.util.Collections
import java.util.LinkedHashMap
import java.util.LinkedHashSet

data class GameDataVersion(
	val gameVersion: String,
	val dataVersion: Int? = null,
	val dataVersionSeries: String? = null,
) {
	init {
		require(gameVersion.isNotBlank()) { "Game version must not be blank" }
		require(dataVersion == null || dataVersion >= 0) { "Data version must not be negative" }
		require(dataVersionSeries == null || dataVersionSeries.isNotBlank()) {
			"Data version series must not be blank"
		}
	}
}

data class WorldCoordinate(
	val x: Int,
	val y: Int,
	val z: Int,
)

enum class VerticalDatum {
	ROOM_FLOOR,
	WORLD_ORIGIN_Y,
	DEVELOPER_DECLARED,
}

enum class HorizontalDatum {
	NORTH_WEST_FOOTPRINT_BOUNDARY,
	DEVELOPER_DECLARED,
}

data class RoomLocalDatum(
	val worldOrigin: WorldCoordinate,
	val verticalDatum: VerticalDatum = VerticalDatum.ROOM_FLOOR,
	val horizontalDatum: HorizontalDatum = HorizontalDatum.NORTH_WEST_FOOTPRINT_BOUNDARY,
)

data class CoverageRegion(
	val bounds: LocalBlockBounds,
	val sourceChunkX: Int? = null,
	val sourceChunkZ: Int? = null,
) {
	init {
		require((sourceChunkX == null) == (sourceChunkZ == null)) {
			"Coverage source chunk coordinates must either both be present or both be absent"
		}
	}
}

enum class UnavailableReason {
	CHUNK_UNAVAILABLE,
	OUTSIDE_CAPTURE_BOUNDS,
	CLIENT_READ_FAILED,
}

data class UnavailableRegion(
	val region: CoverageRegion,
	val reason: UnavailableReason,
)

class ObservationCoverage(
	availableRegions: Collection<CoverageRegion>,
	unavailableRegions: Collection<UnavailableRegion> = emptyList(),
	expectedPositions: Collection<LocalBlockPosition> = emptySet(),
) {
	val availableRegions: List<CoverageRegion> =
		Collections.unmodifiableList(availableRegions.sortedWith(regionComparator))
	val unavailableRegions: List<UnavailableRegion> =
		Collections.unmodifiableList(unavailableRegions.sortedWith(compareBy { it.region.bounds.min }))
	val expectedPositions: Set<LocalBlockPosition> =
		Collections.unmodifiableSet(LinkedHashSet(expectedPositions.sorted()))

	init {
		require(this.availableRegions.isNotEmpty() || this.unavailableRegions.isNotEmpty()) {
			"Coverage must describe at least one available or unavailable region"
		}
		val allRegions = this.availableRegions.map(CoverageRegion::bounds) +
			this.unavailableRegions.map { it.region.bounds }
		allRegions.forEachIndexed { index, left ->
			allRegions.drop(index + 1).forEach { right ->
				require(!left.overlaps(right)) { "Coverage regions must not overlap: $left and $right" }
			}
		}
	}

	val availableVolume: Long
		get() = availableRegions.sumOf { it.bounds.volume }

	val unavailableVolume: Long
		get() = unavailableRegions.sumOf { it.region.bounds.volume }

	val coverageRatio: Double
		get() {
			if (expectedPositions.isNotEmpty()) {
				return expectedPositions.count(::isAvailable).toDouble() / expectedPositions.size
			}
			val total = availableVolume + unavailableVolume
			return if (total == 0L) 0.0 else availableVolume.toDouble() / total
		}

	val isComplete: Boolean
		get() = unavailableRegions.isEmpty() && coverageRatio == 1.0

	fun isAvailable(position: LocalBlockPosition): Boolean =
		availableRegions.any { position in it.bounds }

	fun unavailableReasonAt(position: LocalBlockPosition): UnavailableReason? =
		unavailableRegions.firstOrNull { position in it.region.bounds }?.reason

	override fun equals(other: Any?): Boolean =
		other is ObservationCoverage &&
			availableRegions == other.availableRegions &&
			unavailableRegions == other.unavailableRegions &&
			expectedPositions == other.expectedPositions

	override fun hashCode(): Int =
		31 * (31 * availableRegions.hashCode() + unavailableRegions.hashCode()) + expectedPositions.hashCode()

	override fun toString(): String =
		"ObservationCoverage(availableRegions=$availableRegions, unavailableRegions=$unavailableRegions, " +
			"expectedPositions=$expectedPositions)"

	companion object {
		fun complete(bounds: LocalBlockBounds): ObservationCoverage =
			ObservationCoverage(availableRegions = listOf(CoverageRegion(bounds)))

		private val regionComparator = compareBy<CoverageRegion>({ it.bounds.min }, { it.bounds.max })
	}
}

enum class ObservationSource {
	RUNTIME_SCANNER,
	DEVELOPER_CAPTURE,
	IMPORTED_CAPTURE,
	HEADLESS_TEST,
}

class ObservationProvenance(
	val source: ObservationSource,
	val captureId: String? = null,
	val notes: String? = null,
	attributes: Map<String, String> = emptyMap(),
) {
	val attributes: Map<String, String> = Collections.unmodifiableMap(LinkedHashMap(attributes.toSortedMap()))

	init {
		require(captureId == null || captureId.isNotBlank()) { "Capture id must not be blank" }
		require(notes == null || notes.isNotBlank()) { "Observation notes must not be blank" }
		this.attributes.forEach { (key, value) ->
			require(key.isNotBlank()) { "Provenance attribute key must not be blank" }
			require(value.isNotBlank()) { "Provenance attribute value must not be blank" }
		}
	}

	override fun equals(other: Any?): Boolean =
		other is ObservationProvenance &&
			source == other.source &&
			captureId == other.captureId &&
			notes == other.notes &&
			attributes == other.attributes

	override fun hashCode(): Int {
		var result = source.hashCode()
		result = 31 * result + (captureId?.hashCode() ?: 0)
		result = 31 * result + (notes?.hashCode() ?: 0)
		return 31 * result + attributes.hashCode()
	}
}

class RoomObservation(
	val observationId: String,
	val worldSessionGeneration: Long,
	val revision: Long,
	val gameDataVersion: GameDataVersion,
	val fingerprintPolicyVersion: String = FingerprintPolicy.DEFAULT.version,
	val footprint: RoomFootprint,
	val bounds: LocalBlockBounds,
	val datum: RoomLocalDatum,
	samples: Collection<StructuralSample>,
	val coverage: ObservationCoverage,
	val provenance: ObservationProvenance,
	val observedRotation: RoomRotation? = null,
) {
	val samples: List<StructuralSample> = StructuralNormalizer.normalizeSamples(samples)

	init {
		require(observationId.isNotBlank()) { "Observation id must not be blank" }
		require(worldSessionGeneration >= 0) { "World/session generation must not be negative" }
		require(revision >= 0) { "Observation revision must not be negative" }
		require(Identifiers.isFingerprintId(fingerprintPolicyVersion)) {
			"Invalid observation fingerprint policy version: $fingerprintPolicyVersion"
		}
		require(footprint.anchor == GridPosition(0, 0)) {
			"Observation footprint must be normalized to a zero minimum"
		}
		coverage.availableRegions.forEach {
			require(it.bounds.min in bounds && it.bounds.max in bounds) {
				"Available coverage region ${it.bounds} lies outside observation bounds"
			}
		}
		coverage.unavailableRegions.forEach {
			require(it.region.bounds.min in bounds && it.region.bounds.max in bounds) {
				"Unavailable coverage region ${it.region.bounds} lies outside observation bounds"
			}
		}
		if (coverage.expectedPositions.isEmpty()) {
			require(coverage.availableVolume + coverage.unavailableVolume == bounds.volume) {
				"Coverage regions must partition the complete observation bounds"
			}
		} else {
			coverage.expectedPositions.forEach { position ->
				require(position in bounds) { "Expected position $position lies outside observation bounds" }
				require(coverage.isAvailable(position) || coverage.unavailableReasonAt(position) != null) {
					"Expected position $position is not classified as available or unavailable"
				}
			}
		}
		this.samples.forEach { sample ->
			require(sample.position in bounds) { "Sample ${sample.position} lies outside observation bounds" }
			require(coverage.isAvailable(sample.position)) {
				"Sample ${sample.position} is not inside explicitly available coverage"
			}
		}
	}

	override fun equals(other: Any?): Boolean =
		other is RoomObservation &&
			observationId == other.observationId &&
			worldSessionGeneration == other.worldSessionGeneration &&
			revision == other.revision &&
			gameDataVersion == other.gameDataVersion &&
			fingerprintPolicyVersion == other.fingerprintPolicyVersion &&
			footprint == other.footprint &&
			bounds == other.bounds &&
			datum == other.datum &&
			samples == other.samples &&
			coverage == other.coverage &&
			provenance == other.provenance &&
			observedRotation == other.observedRotation

	override fun hashCode(): Int {
		var result = observationId.hashCode()
		result = 31 * result + worldSessionGeneration.hashCode()
		result = 31 * result + revision.hashCode()
		result = 31 * result + gameDataVersion.hashCode()
		result = 31 * result + fingerprintPolicyVersion.hashCode()
		result = 31 * result + footprint.hashCode()
		result = 31 * result + bounds.hashCode()
		result = 31 * result + datum.hashCode()
		result = 31 * result + samples.hashCode()
		result = 31 * result + coverage.hashCode()
		result = 31 * result + provenance.hashCode()
		return 31 * result + (observedRotation?.hashCode() ?: 0)
	}
}

private fun LocalBlockBounds.overlaps(other: LocalBlockBounds): Boolean =
	min.x <= other.max.x && max.x >= other.min.x &&
		min.y <= other.max.y && max.y >= other.min.y &&
		min.z <= other.max.z && max.z >= other.min.z
