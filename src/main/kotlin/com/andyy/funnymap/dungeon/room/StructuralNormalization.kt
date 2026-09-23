package com.andyy.funnymap.dungeon.room

import com.andyy.funnymap.dungeon.model.RoomFootprint
import com.andyy.funnymap.dungeon.model.RoomRotation
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Collections

enum class RotationIssueReason {
	UNSUPPORTED_DIRECTIONAL_PROPERTY,
	INVALID_DIRECTIONAL_VALUE,
}

data class RotationIssue(
	val position: LocalBlockPosition,
	val propertyName: String,
	val propertyValue: String,
	val reason: RotationIssueReason,
) {
	init {
		require(Identifiers.isPropertyName(propertyName)) { "Invalid directional property name: $propertyName" }
		require(Identifiers.isPropertyValue(propertyValue)) {
			"Invalid directional value '$propertyValue' for property '$propertyName'"
		}
	}
}

data class StructuralRotation(
	val samples: List<StructuralSample>,
	val bounds: LocalBlockBounds,
	val issues: List<RotationIssue>,
)

object StructuralNormalizer {
	private const val ENCODING_VERSION = 1

	fun normalizeSamples(samples: Collection<StructuralSample>): List<StructuralSample> {
		val sorted = samples.sortedBy(StructuralSample::position)
		val duplicate = sorted.zipWithNext().firstOrNull { (left, right) -> left.position == right.position }
		require(duplicate == null) {
			"Structural samples contain duplicate position ${duplicate?.first?.position}"
		}
		return Collections.unmodifiableList(sorted)
	}

	fun encode(bounds: LocalBlockBounds, samples: Collection<StructuralSample>): ByteArray {
		val normalized = normalizeSamples(samples)
		val output = ByteArrayOutputStream()
		DataOutputStream(output).use { data ->
			data.writeInt(ENCODING_VERSION)
			writePosition(data, bounds.min)
			writePosition(data, bounds.max)
			data.writeInt(normalized.size)
			normalized.forEach { sample ->
				writePosition(data, sample.position)
				writeUtf8(data, sample.blockId)
				data.writeInt(sample.properties.size)
				sample.properties.forEach { (name, value) ->
					writeUtf8(data, name)
					writeUtf8(data, value)
				}
			}
		}
		return output.toByteArray()
	}

	fun sha256(bounds: LocalBlockBounds, samples: Collection<StructuralSample>): String {
		val bytes = MessageDigest.getInstance("SHA-256").digest(encode(bounds, samples))
		return "sha256:" + bytes.joinToString(separator = "") {
			(it.toInt() and 0xff).toString(16).padStart(2, '0')
		}
	}

	fun rotate(
		samples: Collection<StructuralSample>,
		bounds: LocalBlockBounds,
		rotation: RoomRotation,
		policy: FingerprintPolicy,
	): StructuralRotation {
		val normalizedBounds = bounds.normalizedHorizontally()
		val issues = mutableListOf<RotationIssue>()
		val rotatedSamples = samples.map { sample ->
			require(sample.position in bounds) {
				"Structural sample ${sample.position} lies outside $bounds"
			}
			val relativeX = sample.position.x - bounds.min.x
			val relativeZ = sample.position.z - bounds.min.z
			val position = when (rotation) {
				RoomRotation.DEGREES_0 -> LocalBlockPosition(relativeX, sample.position.y, relativeZ)
				RoomRotation.DEGREES_90 -> LocalBlockPosition(bounds.depth - 1 - relativeZ, sample.position.y, relativeX)
				RoomRotation.DEGREES_180 -> LocalBlockPosition(
					bounds.width - 1 - relativeX,
					sample.position.y,
					bounds.depth - 1 - relativeZ,
				)
				RoomRotation.DEGREES_270 -> LocalBlockPosition(relativeZ, sample.position.y, bounds.width - 1 - relativeX)
			}
			val properties = sample.properties.mapValues { (name, value) ->
				rotateProperty(position, name, value, rotation, policy, issues)
			}
			StructuralSample(position, sample.blockId, properties)
		}
		val rotatedBounds = when (rotation) {
			RoomRotation.DEGREES_0, RoomRotation.DEGREES_180 -> normalizedBounds
			RoomRotation.DEGREES_90, RoomRotation.DEGREES_270 -> LocalBlockBounds(
				min = LocalBlockPosition(0, bounds.min.y, 0),
				max = LocalBlockPosition(bounds.depth - 1, bounds.max.y, bounds.width - 1),
			)
		}
		return StructuralRotation(
			samples = normalizeSamples(rotatedSamples),
			bounds = rotatedBounds,
			issues = Collections.unmodifiableList(issues.toList()),
		)
	}

	fun rotateFootprint(footprint: RoomFootprint, rotation: RoomRotation): RoomFootprint =
		RoomGeometry.rotateFootprint(footprint, rotation)

	private fun rotateProperty(
		position: LocalBlockPosition,
		name: String,
		value: String,
		rotation: RoomRotation,
		policy: FingerprintPolicy,
		issues: MutableList<RotationIssue>,
	): String {
		if (rotation == RoomRotation.DEGREES_0) return value
		return when (policy.directionalProperties[name]) {
			DirectionalPropertyKind.CARDINAL_FACING -> rotateFacing(position, name, value, rotation, issues)
			DirectionalPropertyKind.HORIZONTAL_AXIS -> rotateAxis(position, name, value, rotation, issues)
			DirectionalPropertyKind.ROTATION_16 -> rotateSixteenth(position, name, value, rotation, issues)
			DirectionalPropertyKind.UNSUPPORTED -> {
				issues += RotationIssue(position, name, value, RotationIssueReason.UNSUPPORTED_DIRECTIONAL_PROPERTY)
				value
			}
			null -> value
		}
	}

	private fun rotateFacing(
		position: LocalBlockPosition,
		name: String,
		value: String,
		rotation: RoomRotation,
		issues: MutableList<RotationIssue>,
	): String {
		if (value == "up" || value == "down") return value
		val values = listOf("north", "east", "south", "west")
		val index = values.indexOf(value)
		if (index < 0) {
			issues += RotationIssue(position, name, value, RotationIssueReason.INVALID_DIRECTIONAL_VALUE)
			return value
		}
		return values[(index + rotation.quarterTurns) % values.size]
	}

	private fun rotateAxis(
		position: LocalBlockPosition,
		name: String,
		value: String,
		rotation: RoomRotation,
		issues: MutableList<RotationIssue>,
	): String = when (value) {
		"y" -> value
		"x" -> if (rotation.quarterTurns % 2 == 0) "x" else "z"
		"z" -> if (rotation.quarterTurns % 2 == 0) "z" else "x"
		else -> {
			issues += RotationIssue(position, name, value, RotationIssueReason.INVALID_DIRECTIONAL_VALUE)
			value
		}
	}

	private fun rotateSixteenth(
		position: LocalBlockPosition,
		name: String,
		value: String,
		rotation: RoomRotation,
		issues: MutableList<RotationIssue>,
	): String {
		val parsed = value.toIntOrNull()
		if (parsed == null || parsed !in 0..15) {
			issues += RotationIssue(position, name, value, RotationIssueReason.INVALID_DIRECTIONAL_VALUE)
			return value
		}
		return ((parsed + rotation.quarterTurns * 4) % 16).toString()
	}

	private fun writePosition(output: DataOutputStream, position: LocalBlockPosition) {
		output.writeInt(position.x)
		output.writeInt(position.y)
		output.writeInt(position.z)
	}

	private fun writeUtf8(output: DataOutputStream, value: String) {
		val encoded = value.toByteArray(StandardCharsets.UTF_8)
		output.writeInt(encoded.size)
		output.write(encoded)
	}
}

val RoomRotation.quarterTurns: Int
	get() = degrees / 90

fun RoomRotation.inverse(): RoomRotation = when (this) {
	RoomRotation.DEGREES_0 -> RoomRotation.DEGREES_0
	RoomRotation.DEGREES_90 -> RoomRotation.DEGREES_270
	RoomRotation.DEGREES_180 -> RoomRotation.DEGREES_180
	RoomRotation.DEGREES_270 -> RoomRotation.DEGREES_90
}
