package com.andyy.funnymap.dungeon.room

import com.andyy.funnymap.dungeon.model.GridPosition
import com.andyy.funnymap.dungeon.model.RoomFootprint
import com.andyy.funnymap.dungeon.model.RoomId
import com.andyy.funnymap.dungeon.model.RoomShape
import com.andyy.funnymap.dungeon.model.RoomType
import java.util.Collections
import java.util.LinkedHashSet

class RoomDataProvenance(
	gameVersions: Collection<String> = emptySet(),
	dataVersions: Collection<Int> = emptySet(),
	captureIds: Collection<String> = emptySet(),
	val notes: String? = null,
) {
	val gameVersions: Set<String> = immutableSet(gameVersions.sorted())
	val dataVersions: Set<Int> = immutableSet(dataVersions.sorted())
	val captureIds: Set<String> = immutableSet(captureIds.sorted())

	init {
		require(this.gameVersions.none(String::isBlank)) { "Provenance game versions must not be blank" }
		require(this.dataVersions.none { it < 0 }) { "Provenance data versions must not be negative" }
		require(this.captureIds.none(String::isBlank)) { "Provenance capture ids must not be blank" }
		require(notes == null || notes.isNotBlank()) { "Provenance notes must not be blank" }
	}

	override fun equals(other: Any?): Boolean =
		other is RoomDataProvenance &&
			gameVersions == other.gameVersions &&
			dataVersions == other.dataVersions &&
			captureIds == other.captureIds &&
			notes == other.notes

	override fun hashCode(): Int =
		31 * (31 * (31 * gameVersions.hashCode() + dataVersions.hashCode()) + captureIds.hashCode()) +
			(notes?.hashCode() ?: 0)
}

class RoomFingerprint private constructor(
	val id: String,
	val bounds: LocalBlockBounds,
	val origin: LocalBlockPosition,
	samples: Collection<StructuralSample>,
	val digest: String,
	val policyVersion: String,
	val provenance: RoomDataProvenance? = null,
) {
	val samples: List<StructuralSample> = StructuralNormalizer.normalizeSamples(samples)

	init {
		require(Identifiers.isFingerprintId(id)) { "Invalid fingerprint id: $id" }
		require(Identifiers.isFingerprintId(policyVersion)) { "Invalid fingerprint policy version: $policyVersion" }
		require(origin.x == 0 && origin.z == 0) {
			"Canonical fingerprint origin must use x=0 and z=0"
		}
		require(bounds.min.x == 0 && bounds.min.z == 0) {
			"Canonical fingerprint bounds must use a zero horizontal minimum"
		}
		require(this.samples.isNotEmpty()) { "Fingerprint must contain structural samples" }
		this.samples.forEach { sample ->
			require(sample.position in bounds) { "Fingerprint sample ${sample.position} lies outside $bounds" }
		}
		require(Identifiers.isSha256(digest)) { "Malformed fingerprint digest: $digest" }
		require(digest == StructuralNormalizer.sha256(bounds, this.samples)) {
			"Fingerprint digest does not match normalized structural samples"
		}
	}

	override fun equals(other: Any?): Boolean =
		other is RoomFingerprint &&
			id == other.id &&
			bounds == other.bounds &&
			origin == other.origin &&
			samples == other.samples &&
			digest == other.digest &&
			policyVersion == other.policyVersion &&
			provenance == other.provenance

	override fun hashCode(): Int {
		var result = id.hashCode()
		result = 31 * result + bounds.hashCode()
		result = 31 * result + origin.hashCode()
		result = 31 * result + samples.hashCode()
		result = 31 * result + digest.hashCode()
		result = 31 * result + policyVersion.hashCode()
		return 31 * result + (provenance?.hashCode() ?: 0)
	}

	companion object {
		fun create(
			id: String,
			bounds: LocalBlockBounds,
			origin: LocalBlockPosition = LocalBlockPosition(0, 0, 0),
			samples: Collection<StructuralSample>,
			policyVersion: String,
			provenance: RoomDataProvenance? = null,
		): RoomFingerprint {
			val normalized = StructuralNormalizer.normalizeSamples(samples)
			return RoomFingerprint(
				id = id,
				bounds = bounds,
				origin = origin,
				samples = normalized,
				digest = StructuralNormalizer.sha256(bounds, normalized),
				policyVersion = policyVersion,
				provenance = provenance,
			)
		}

		fun validated(
			id: String,
			bounds: LocalBlockBounds,
			origin: LocalBlockPosition,
			samples: Collection<StructuralSample>,
			digest: String,
			policyVersion: String,
			provenance: RoomDataProvenance? = null,
		): RoomFingerprint = RoomFingerprint(
			id = id,
			bounds = bounds,
			origin = origin,
			samples = samples,
			digest = digest,
			policyVersion = policyVersion,
			provenance = provenance,
		)
	}
}

class RoomDefinition(
	val id: RoomId,
	val displayName: String,
	val type: RoomType,
	val footprint: RoomFootprint,
	val secretCount: Int,
	val cryptCount: Int,
	fingerprints: Collection<RoomFingerprint>,
	val provenance: RoomDataProvenance? = null,
) {
	val shape: RoomShape
		get() = footprint.shape
	val fingerprints: List<RoomFingerprint> =
		Collections.unmodifiableList(fingerprints.sortedBy(RoomFingerprint::id))

	init {
		require(Identifiers.isStableRoomId(id.value)) { "Invalid stable room id: ${id.value}" }
		require(displayName.isNotBlank()) { "Room display name must not be blank" }
		require(type != RoomType.UNKNOWN) { "Production room definition type must not be UNKNOWN" }
		require(footprint.anchor == GridPosition(0, 0)) {
			"Production room footprint must be normalized to a zero minimum"
		}
		require(secretCount >= 0) { "Secret count must not be negative" }
		require(cryptCount >= 0) { "Crypt count must not be negative" }
		require(this.fingerprints.isNotEmpty()) { "Room definition must contain at least one fingerprint" }
		require(this.fingerprints.map(RoomFingerprint::id).distinct().size == this.fingerprints.size) {
			"Room definition contains duplicate fingerprint ids"
		}
	}

	override fun equals(other: Any?): Boolean =
		other is RoomDefinition &&
			id == other.id &&
			displayName == other.displayName &&
			type == other.type &&
			footprint == other.footprint &&
			secretCount == other.secretCount &&
			cryptCount == other.cryptCount &&
			fingerprints == other.fingerprints &&
			provenance == other.provenance

	override fun hashCode(): Int {
		var result = id.hashCode()
		result = 31 * result + displayName.hashCode()
		result = 31 * result + type.hashCode()
		result = 31 * result + footprint.hashCode()
		result = 31 * result + secretCount
		result = 31 * result + cryptCount
		result = 31 * result + fingerprints.hashCode()
		return 31 * result + (provenance?.hashCode() ?: 0)
	}
}

private fun <T> immutableSet(values: Collection<T>): Set<T> =
	Collections.unmodifiableSet(LinkedHashSet(values))
