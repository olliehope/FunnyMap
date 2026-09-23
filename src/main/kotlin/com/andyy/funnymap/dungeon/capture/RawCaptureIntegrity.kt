package com.andyy.funnymap.dungeon.capture

import com.andyy.funnymap.dungeon.room.LocalBlockBounds
import com.andyy.funnymap.dungeon.room.LocalBlockPosition
import com.andyy.funnymap.dungeon.room.RoomObservation
import com.andyy.funnymap.dungeon.room.RotationIssue
import com.andyy.funnymap.dungeon.room.StructuralSample
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant

internal data class UnsignedRawCapture(
	val schemaVersion: Int,
	val captureId: String,
	val metadata: CaptureRoomMetadata,
	val observation: RoomObservation,
	val fingerprintPolicyVersion: String,
	val policyExclusions: List<PolicyExcludedSample>,
	val rotationIssues: List<RotationIssue>,
	val developerNotes: String?,
	val capturedAt: Instant,
) {
	fun toCapture(integrityDigest: String): RawRoomCapture = RawRoomCapture(
		schemaVersion = schemaVersion,
		captureId = captureId,
		metadata = metadata,
		observation = observation,
		fingerprintPolicyVersion = fingerprintPolicyVersion,
		policyExclusions = policyExclusions,
		rotationIssues = rotationIssues,
		developerNotes = developerNotes,
		capturedAt = capturedAt,
		integrityDigest = integrityDigest,
	)
}

object RawCaptureIntegrity {
	fun verify(capture: RawRoomCapture): Boolean =
		capture.integrityDigest == sha256(capture.toUnsigned())

	internal fun sha256(capture: UnsignedRawCapture): String {
		val output = ByteArrayOutputStream()
		DataOutputStream(output).use { data -> write(data, capture) }
		val digest = MessageDigest.getInstance("SHA-256").digest(output.toByteArray())
		return "sha256:" + digest.joinToString(separator = "") { "%02x".format(it) }
	}

	private fun write(output: DataOutputStream, capture: UnsignedRawCapture) {
		output.writeInt(INTEGRITY_ENCODING_VERSION)
		output.writeInt(capture.schemaVersion)
		writeString(output, capture.captureId)
		writeString(output, capture.metadata.roomId.value)
		writeString(output, capture.metadata.displayName)
		writeString(output, capture.metadata.type.name)
		output.writeInt(capture.metadata.secretCount)
		output.writeInt(capture.metadata.cryptCount)
		capture.metadata.footprint.localCells.sorted().let { cells ->
			output.writeInt(cells.size)
			cells.forEach { cell ->
				output.writeInt(cell.column)
				output.writeInt(cell.row)
			}
		}
		writeObservation(output, capture.observation)
		writeString(output, capture.fingerprintPolicyVersion)
		val exclusions = capture.policyExclusions.sortedWith(
			compareBy<PolicyExcludedSample>({ it.sample.position }, { it.sample.blockId }, { it.reason }),
		)
		output.writeInt(exclusions.size)
		exclusions.forEach { exclusion ->
			writeSample(output, exclusion.sample)
			writeString(output, exclusion.reason)
			writeStringMap(output, exclusion.droppedProperties)
		}
		val issues = capture.rotationIssues.sortedWith(
			compareBy<RotationIssue>({ it.position }, { it.propertyName }, { it.propertyValue }, { it.reason.name }),
		)
		output.writeInt(issues.size)
		issues.forEach { issue ->
			writePosition(output, issue.position)
			writeString(output, issue.propertyName)
			writeString(output, issue.propertyValue)
			writeString(output, issue.reason.name)
		}
		writeNullableString(output, capture.developerNotes)
		writeString(output, capture.capturedAt.toString())
	}

	private fun writeObservation(output: DataOutputStream, observation: RoomObservation) {
		writeString(output, observation.observationId)
		output.writeLong(observation.worldSessionGeneration)
		output.writeLong(observation.revision)
		writeString(output, observation.gameDataVersion.gameVersion)
		output.writeBoolean(observation.gameDataVersion.dataVersion != null)
		observation.gameDataVersion.dataVersion?.let(output::writeInt)
		writeNullableString(output, observation.gameDataVersion.dataVersionSeries)
		writeString(output, observation.fingerprintPolicyVersion)
		observation.footprint.localCells.sorted().let { cells ->
			output.writeInt(cells.size)
			cells.forEach { cell ->
				output.writeInt(cell.column)
				output.writeInt(cell.row)
			}
		}
		writeBounds(output, observation.bounds)
		with(observation.datum.worldOrigin) {
			output.writeInt(x)
			output.writeInt(y)
			output.writeInt(z)
		}
		writeString(output, observation.datum.verticalDatum.name)
		writeString(output, observation.datum.horizontalDatum.name)
		output.writeInt(observation.samples.size)
		observation.samples.forEach { writeSample(output, it) }
		output.writeInt(observation.coverage.availableRegions.size)
		observation.coverage.availableRegions.forEach { region ->
			writeBounds(output, region.bounds)
			writeNullableInt(output, region.sourceChunkX)
			writeNullableInt(output, region.sourceChunkZ)
		}
		output.writeInt(observation.coverage.unavailableRegions.size)
		observation.coverage.unavailableRegions.forEach { unavailable ->
			writeBounds(output, unavailable.region.bounds)
			writeNullableInt(output, unavailable.region.sourceChunkX)
			writeNullableInt(output, unavailable.region.sourceChunkZ)
			writeString(output, unavailable.reason.name)
		}
		val expected = observation.coverage.expectedPositions.sorted()
		output.writeInt(expected.size)
		expected.forEach { writePosition(output, it) }
		writeString(output, observation.provenance.source.name)
		writeNullableString(output, observation.provenance.captureId)
		writeNullableString(output, observation.provenance.notes)
		writeStringMap(output, observation.provenance.attributes)
		writeNullableString(output, observation.observedRotation?.name)
	}

	private fun writeSample(output: DataOutputStream, sample: StructuralSample) {
		writePosition(output, sample.position)
		writeString(output, sample.blockId)
		writeStringMap(output, sample.properties)
	}

	private fun writeBounds(output: DataOutputStream, bounds: LocalBlockBounds) {
		writePosition(output, bounds.min)
		writePosition(output, bounds.max)
	}

	private fun writePosition(output: DataOutputStream, position: LocalBlockPosition) {
		output.writeInt(position.x)
		output.writeInt(position.y)
		output.writeInt(position.z)
	}

	private fun writeStringMap(output: DataOutputStream, values: Map<String, String>) {
		output.writeInt(values.size)
		values.toSortedMap().forEach { (key, value) ->
			writeString(output, key)
			writeString(output, value)
		}
	}

	private fun writeNullableInt(output: DataOutputStream, value: Int?) {
		output.writeBoolean(value != null)
		value?.let(output::writeInt)
	}

	private fun writeNullableString(output: DataOutputStream, value: String?) {
		output.writeBoolean(value != null)
		value?.let { writeString(output, it) }
	}

	private fun writeString(output: DataOutputStream, value: String) {
		val encoded = value.toByteArray(StandardCharsets.UTF_8)
		output.writeInt(encoded.size)
		output.write(encoded)
	}

	private fun RawRoomCapture.toUnsigned(): UnsignedRawCapture = UnsignedRawCapture(
		schemaVersion = schemaVersion,
		captureId = captureId,
		metadata = metadata,
		observation = observation,
		fingerprintPolicyVersion = fingerprintPolicyVersion,
		policyExclusions = policyExclusions,
		rotationIssues = rotationIssues,
		developerNotes = developerNotes,
		capturedAt = capturedAt,
	)

	private const val INTEGRITY_ENCODING_VERSION = 1
}
