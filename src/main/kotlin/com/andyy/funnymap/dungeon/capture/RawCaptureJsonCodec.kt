package com.andyy.funnymap.dungeon.capture

import com.andyy.funnymap.dungeon.model.GridPosition
import com.andyy.funnymap.dungeon.model.RoomFootprint
import com.andyy.funnymap.dungeon.model.RoomId
import com.andyy.funnymap.dungeon.model.RoomRotation
import com.andyy.funnymap.dungeon.model.RoomShape
import com.andyy.funnymap.dungeon.model.RoomType
import com.andyy.funnymap.dungeon.room.CoverageRegion
import com.andyy.funnymap.dungeon.room.GameDataVersion
import com.andyy.funnymap.dungeon.room.HorizontalDatum
import com.andyy.funnymap.dungeon.room.LocalBlockBounds
import com.andyy.funnymap.dungeon.room.LocalBlockPosition
import com.andyy.funnymap.dungeon.room.ObservationCoverage
import com.andyy.funnymap.dungeon.room.ObservationProvenance
import com.andyy.funnymap.dungeon.room.ObservationSource
import com.andyy.funnymap.dungeon.room.RoomLocalDatum
import com.andyy.funnymap.dungeon.room.RoomObservation
import com.andyy.funnymap.dungeon.room.RotationIssue
import com.andyy.funnymap.dungeon.room.RotationIssueReason
import com.andyy.funnymap.dungeon.room.StructuralSample
import com.andyy.funnymap.dungeon.room.UnavailableReason
import com.andyy.funnymap.dungeon.room.UnavailableRegion
import com.andyy.funnymap.dungeon.room.VerticalDatum
import com.andyy.funnymap.dungeon.room.WorldCoordinate
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import java.time.DateTimeException
import java.time.Instant

class RawCaptureValidationException(
	val sourceDescription: String,
	val path: String,
	message: String,
	cause: Throwable? = null,
) : IllegalArgumentException("Invalid raw capture '$sourceDescription' at $path: $message", cause)

/** Strict, separately versioned JSON codec for development-only capture artifacts. */
object RawCaptureJsonCodec : RawCaptureCodec {
	private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()

	override fun encode(capture: RawRoomCapture): String {
		require(RawCaptureIntegrity.verify(capture)) {
			"Raw capture '${capture.captureId}' failed its integrity check"
		}
		return gson.toJson(captureElement(capture)) + "\n"
	}

	override fun decode(json: String, sourceDescription: String): RawRoomCapture {
		val root = try {
			JsonParser.parseString(json).objectAt(sourceDescription, "$")
		} catch (error: RawCaptureValidationException) {
			throw error
		} catch (error: JsonParseException) {
			throw invalid(sourceDescription, "$", "Malformed JSON: ${error.message}", error)
		} catch (error: IllegalStateException) {
			throw invalid(sourceDescription, "$", error.message ?: "Malformed JSON", error)
		}

		return try {
			root.onlyKeys(
				sourceDescription,
				"$",
				setOf(
					"schemaVersion", "captureId", "room", "observation", "fingerprintPolicyVersion",
					"policyExclusions", "rotationIssues", "developerNotes", "capturedAt", "integrityDigest",
				),
			)
			val capture = RawRoomCapture(
				schemaVersion = root.requiredInt("schemaVersion", sourceDescription, "$"),
				captureId = root.requiredString("captureId", sourceDescription, "$"),
				metadata = parseMetadata(root.requiredObject("room", sourceDescription, "$"), sourceDescription, "$.room"),
				observation = parseObservation(
					root.requiredObject("observation", sourceDescription, "$"),
					sourceDescription,
					"$.observation",
				),
				fingerprintPolicyVersion = root.requiredString("fingerprintPolicyVersion", sourceDescription, "$"),
				policyExclusions = root.requiredArray("policyExclusions", sourceDescription, "$" )
					.mapIndexed { index, element ->
						parsePolicyExclusion(element.objectAt(sourceDescription, "$.policyExclusions[$index]"), sourceDescription, "$.policyExclusions[$index]")
					},
				rotationIssues = root.requiredArray("rotationIssues", sourceDescription, "$" )
					.mapIndexed { index, element ->
						parseRotationIssue(element.objectAt(sourceDescription, "$.rotationIssues[$index]"), sourceDescription, "$.rotationIssues[$index]")
					},
				developerNotes = root.optionalString("developerNotes", sourceDescription, "$"),
				capturedAt = parseInstant(root.requiredString("capturedAt", sourceDescription, "$"), sourceDescription, "$.capturedAt"),
				integrityDigest = root.requiredString("integrityDigest", sourceDescription, "$"),
			)
			if (!RawCaptureIntegrity.verify(capture)) {
				throw invalid(sourceDescription, "$.integrityDigest", "Digest does not match the decoded capture contents")
			}
			capture
		} catch (error: RawCaptureValidationException) {
			throw error
		} catch (error: IllegalArgumentException) {
			throw invalid(sourceDescription, "$", error.message ?: "Invalid raw capture", error)
		}
	}

	private fun parseMetadata(root: JsonObject, source: String, path: String): CaptureRoomMetadata {
		root.onlyKeys(source, path, setOf("id", "name", "type", "shape", "secrets", "crypts"))
		val typeName = root.requiredString("type", source, path)
		val type = RoomType.entries.firstOrNull { it.name == typeName }
			?: throw invalid(source, "$path.type", "Unknown room type '$typeName'")
		return CaptureRoomMetadata(
			roomId = RoomId(root.requiredString("id", source, path)),
			displayName = root.requiredString("name", source, path),
			type = type,
			footprint = parseFootprint(root.requiredObject("shape", source, path), source, "$path.shape"),
			secretCount = root.requiredInt("secrets", source, path),
			cryptCount = root.requiredInt("crypts", source, path),
		)
	}

	private fun parseFootprint(root: JsonObject, source: String, path: String): RoomFootprint {
		root.onlyKeys(source, path, setOf("kind", "cells"))
		val kind = root.requiredString("kind", source, path)
		val expected = when (kind) {
			"1x1" -> RoomShape.ONE_BY_ONE
			"1x2" -> RoomShape.ONE_BY_TWO
			"1x3" -> RoomShape.ONE_BY_THREE
			"1x4" -> RoomShape.ONE_BY_FOUR
			"2x2" -> RoomShape.TWO_BY_TWO
			"l-shaped" -> RoomShape.L_SHAPED
			else -> throw invalid(source, "$path.kind", "Unsupported room shape '$kind'")
		}
		val cells = root.requiredArray("cells", source, path).mapIndexed { index, element ->
			val pairPath = "$path.cells[$index]"
			val pair = element.arrayAt(source, pairPath)
			if (pair.size() != 2) throw invalid(source, pairPath, "Footprint cell must contain two integers")
			GridPosition(pair[0].intAt(source, "$pairPath[0]"), pair[1].intAt(source, "$pairPath[1]"))
		}
		val footprint = RoomFootprint.of(cells)
		if (footprint.shape != expected) {
			throw invalid(source, path, "Shape '$kind' does not match occupied cells (${footprint.shape})")
		}
		return footprint
	}

	private fun parseObservation(root: JsonObject, source: String, path: String): RoomObservation {
		root.onlyKeys(
			source,
			path,
			setOf(
				"id", "sessionGeneration", "revision", "gameDataVersion", "fingerprintPolicyVersion",
				"footprint", "bounds", "datum", "samples", "coverage", "provenance", "observedRotation",
			),
		)
		val rotationDegrees = root.requiredInt("observedRotation", source, path)
		val rotation = RoomRotation.entries.firstOrNull { it.degrees == rotationDegrees }
			?: throw invalid(source, "$path.observedRotation", "Rotation must be 0, 90, 180, or 270")
		return RoomObservation(
			observationId = root.requiredString("id", source, path),
			worldSessionGeneration = root.requiredLong("sessionGeneration", source, path),
			revision = root.requiredLong("revision", source, path),
			gameDataVersion = parseGameDataVersion(
				root.requiredObject("gameDataVersion", source, path), source, "$path.gameDataVersion",
			),
			fingerprintPolicyVersion = root.requiredString("fingerprintPolicyVersion", source, path),
			footprint = parseFootprint(root.requiredObject("footprint", source, path), source, "$path.footprint"),
			bounds = parseBounds(root.requiredObject("bounds", source, path), source, "$path.bounds"),
			datum = parseDatum(root.requiredObject("datum", source, path), source, "$path.datum"),
			samples = root.requiredArray("samples", source, path).mapIndexed { index, element ->
				parseSample(element.objectAt(source, "$path.samples[$index]"), source, "$path.samples[$index]")
			},
			coverage = parseCoverage(root.requiredObject("coverage", source, path), source, "$path.coverage"),
			provenance = parseProvenance(root.requiredObject("provenance", source, path), source, "$path.provenance"),
			observedRotation = rotation,
		)
	}

	private fun parseGameDataVersion(root: JsonObject, source: String, path: String): GameDataVersion {
		root.onlyKeys(source, path, setOf("gameVersion", "dataVersion", "dataVersionSeries"))
		return GameDataVersion(
			gameVersion = root.requiredString("gameVersion", source, path),
			dataVersion = root.optionalInt("dataVersion", source, path),
			dataVersionSeries = root.optionalString("dataVersionSeries", source, path),
		)
	}

	private fun parseDatum(root: JsonObject, source: String, path: String): RoomLocalDatum {
		root.onlyKeys(source, path, setOf("worldOrigin", "vertical", "horizontal"))
		val origin = parsePosition(root.requiredArray("worldOrigin", source, path), source, "$path.worldOrigin")
		return RoomLocalDatum(
			worldOrigin = WorldCoordinate(origin.x, origin.y, origin.z),
			verticalDatum = enumValue(root.requiredString("vertical", source, path), source, "$path.vertical"),
			horizontalDatum = enumValue(root.requiredString("horizontal", source, path), source, "$path.horizontal"),
		)
	}

	private fun parseCoverage(root: JsonObject, source: String, path: String): ObservationCoverage {
		root.onlyKeys(source, path, setOf("availableRegions", "unavailableRegions", "expectedPositions"))
		return ObservationCoverage(
			availableRegions = root.requiredArray("availableRegions", source, path).mapIndexed { index, element ->
				parseRegion(element.objectAt(source, "$path.availableRegions[$index]"), source, "$path.availableRegions[$index]")
			},
			unavailableRegions = root.requiredArray("unavailableRegions", source, path).mapIndexed { index, element ->
				val itemPath = "$path.unavailableRegions[$index]"
				val item = element.objectAt(source, itemPath)
				item.onlyKeys(source, itemPath, setOf("region", "reason"))
				UnavailableRegion(
					region = parseRegion(item.requiredObject("region", source, itemPath), source, "$itemPath.region"),
					reason = enumValue(item.requiredString("reason", source, itemPath), source, "$itemPath.reason"),
				)
			},
			expectedPositions = root.requiredArray("expectedPositions", source, path).mapIndexed { index, element ->
				parsePosition(element.arrayAt(source, "$path.expectedPositions[$index]"), source, "$path.expectedPositions[$index]")
			},
		)
	}

	private fun parseRegion(root: JsonObject, source: String, path: String): CoverageRegion {
		root.onlyKeys(source, path, setOf("bounds", "sourceChunk"))
		val chunk = root.optionalArray("sourceChunk", source, path)
		if (chunk != null && chunk.size() != 2) throw invalid(source, "$path.sourceChunk", "Chunk coordinate must contain two integers")
		return CoverageRegion(
			bounds = parseBounds(root.requiredObject("bounds", source, path), source, "$path.bounds"),
			sourceChunkX = chunk?.get(0)?.intAt(source, "$path.sourceChunk[0]"),
			sourceChunkZ = chunk?.get(1)?.intAt(source, "$path.sourceChunk[1]"),
		)
	}

	private fun parseProvenance(root: JsonObject, source: String, path: String): ObservationProvenance {
		root.onlyKeys(source, path, setOf("source", "captureId", "notes", "attributes"))
		return ObservationProvenance(
			source = enumValue(root.requiredString("source", source, path), source, "$path.source"),
			captureId = root.optionalString("captureId", source, path),
			notes = root.optionalString("notes", source, path),
			attributes = parseStringMap(root.requiredObject("attributes", source, path), source, "$path.attributes"),
		)
	}

	private fun parsePolicyExclusion(root: JsonObject, source: String, path: String): PolicyExcludedSample {
		root.onlyKeys(source, path, setOf("sample", "reason", "droppedProperties"))
		return PolicyExcludedSample(
			sample = parseSample(root.requiredObject("sample", source, path), source, "$path.sample"),
			reason = root.requiredString("reason", source, path),
			droppedProperties = parseStringMap(
				root.requiredObject("droppedProperties", source, path), source, "$path.droppedProperties",
			),
		)
	}

	private fun parseRotationIssue(root: JsonObject, source: String, path: String): RotationIssue {
		root.onlyKeys(source, path, setOf("position", "property", "value", "reason"))
		return RotationIssue(
			position = parsePosition(root.requiredArray("position", source, path), source, "$path.position"),
			propertyName = root.requiredString("property", source, path),
			propertyValue = root.requiredString("value", source, path),
			reason = enumValue(root.requiredString("reason", source, path), source, "$path.reason"),
		)
	}

	private fun parseSample(root: JsonObject, source: String, path: String): StructuralSample {
		root.onlyKeys(source, path, setOf("pos", "block", "properties"))
		return StructuralSample(
			position = parsePosition(root.requiredArray("pos", source, path), source, "$path.pos"),
			blockId = root.requiredString("block", source, path),
			properties = parseStringMap(root.requiredObject("properties", source, path), source, "$path.properties"),
		)
	}

	private fun parseBounds(root: JsonObject, source: String, path: String): LocalBlockBounds {
		root.onlyKeys(source, path, setOf("min", "max"))
		return LocalBlockBounds(
			min = parsePosition(root.requiredArray("min", source, path), source, "$path.min"),
			max = parsePosition(root.requiredArray("max", source, path), source, "$path.max"),
		)
	}

	private fun parsePosition(array: JsonArray, source: String, path: String): LocalBlockPosition {
		if (array.size() != 3) throw invalid(source, path, "Position must contain three integers")
		return LocalBlockPosition(
			array[0].intAt(source, "$path[0]"),
			array[1].intAt(source, "$path[1]"),
			array[2].intAt(source, "$path[2]"),
		)
	}

	private fun parseStringMap(root: JsonObject, source: String, path: String): Map<String, String> =
		root.entrySet().associate { (key, value) -> key to value.stringAt(source, "$path.$key") }

	private inline fun <reified T : Enum<T>> enumValue(value: String, source: String, path: String): T =
		enumValues<T>().firstOrNull { it.name == value }
			?: throw invalid(source, path, "Unknown ${T::class.simpleName} value '$value'")

	private fun parseInstant(value: String, source: String, path: String): Instant = try {
		Instant.parse(value)
	} catch (error: DateTimeException) {
		throw invalid(source, path, "Invalid ISO-8601 timestamp '$value'", error)
	}

	private fun captureElement(capture: RawRoomCapture): JsonObject = JsonObject().apply {
		addProperty("schemaVersion", capture.schemaVersion)
		addProperty("captureId", capture.captureId)
		add("room", metadataElement(capture.metadata))
		add("observation", observationElement(capture.observation))
		addProperty("fingerprintPolicyVersion", capture.fingerprintPolicyVersion)
		add("policyExclusions", JsonArray().also { array ->
			capture.policyExclusions.forEach { exclusion ->
				array.add(JsonObject().apply {
					add("sample", sampleElement(exclusion.sample))
					addProperty("reason", exclusion.reason)
					add("droppedProperties", stringMapElement(exclusion.droppedProperties))
				})
			}
		})
		add("rotationIssues", JsonArray().also { array ->
			capture.rotationIssues.forEach { issue ->
				array.add(JsonObject().apply {
					add("position", positionElement(issue.position))
					addProperty("property", issue.propertyName)
					addProperty("value", issue.propertyValue)
					addProperty("reason", issue.reason.name)
				})
			}
		})
		capture.developerNotes?.let { addProperty("developerNotes", it) }
		addProperty("capturedAt", capture.capturedAt.toString())
		addProperty("integrityDigest", capture.integrityDigest)
	}

	private fun metadataElement(metadata: CaptureRoomMetadata): JsonObject = JsonObject().apply {
		addProperty("id", metadata.roomId.value)
		addProperty("name", metadata.displayName)
		addProperty("type", metadata.type.name)
		add("shape", footprintElement(metadata.footprint))
		addProperty("secrets", metadata.secretCount)
		addProperty("crypts", metadata.cryptCount)
	}

	private fun observationElement(observation: RoomObservation): JsonObject = JsonObject().apply {
		addProperty("id", observation.observationId)
		addProperty("sessionGeneration", observation.worldSessionGeneration)
		addProperty("revision", observation.revision)
		add("gameDataVersion", JsonObject().apply {
			addProperty("gameVersion", observation.gameDataVersion.gameVersion)
			observation.gameDataVersion.dataVersion?.let { addProperty("dataVersion", it) }
			observation.gameDataVersion.dataVersionSeries?.let { addProperty("dataVersionSeries", it) }
		})
		addProperty("fingerprintPolicyVersion", observation.fingerprintPolicyVersion)
		add("footprint", footprintElement(observation.footprint))
		add("bounds", boundsElement(observation.bounds))
		add("datum", JsonObject().apply {
			add("worldOrigin", positionElement(observation.datum.worldOrigin.let { LocalBlockPosition(it.x, it.y, it.z) }))
			addProperty("vertical", observation.datum.verticalDatum.name)
			addProperty("horizontal", observation.datum.horizontalDatum.name)
		})
		add("samples", JsonArray().also { array -> observation.samples.forEach { array.add(sampleElement(it)) } })
		add("coverage", coverageElement(observation.coverage))
		add("provenance", JsonObject().apply {
			addProperty("source", observation.provenance.source.name)
			observation.provenance.captureId?.let { addProperty("captureId", it) }
			observation.provenance.notes?.let { addProperty("notes", it) }
			add("attributes", stringMapElement(observation.provenance.attributes))
		})
		addProperty("observedRotation", requireNotNull(observation.observedRotation).degrees)
	}

	private fun coverageElement(coverage: ObservationCoverage): JsonObject = JsonObject().apply {
		add("availableRegions", JsonArray().also { array ->
			coverage.availableRegions.forEach { array.add(regionElement(it)) }
		})
		add("unavailableRegions", JsonArray().also { array ->
			coverage.unavailableRegions.forEach { unavailable ->
				array.add(JsonObject().apply {
					add("region", regionElement(unavailable.region))
					addProperty("reason", unavailable.reason.name)
				})
			}
		})
		add("expectedPositions", JsonArray().also { array ->
			coverage.expectedPositions.sorted().forEach { array.add(positionElement(it)) }
		})
	}

	private fun regionElement(region: CoverageRegion): JsonObject = JsonObject().apply {
		add("bounds", boundsElement(region.bounds))
		if (region.sourceChunkX != null) {
			add("sourceChunk", JsonArray().apply {
				add(region.sourceChunkX)
				add(region.sourceChunkZ)
			})
		}
	}

	private fun footprintElement(footprint: RoomFootprint): JsonObject = JsonObject().apply {
		addProperty("kind", footprint.shape.jsonName)
		add("cells", JsonArray().also { array ->
			footprint.localCells.sorted().forEach { cell ->
				array.add(JsonArray().apply { add(cell.column); add(cell.row) })
			}
		})
	}

	private fun sampleElement(sample: StructuralSample): JsonObject = JsonObject().apply {
		add("pos", positionElement(sample.position))
		addProperty("block", sample.blockId)
		add("properties", stringMapElement(sample.properties))
	}

	private fun boundsElement(bounds: LocalBlockBounds): JsonObject = JsonObject().apply {
		add("min", positionElement(bounds.min))
		add("max", positionElement(bounds.max))
	}

	private fun positionElement(position: LocalBlockPosition): JsonArray = JsonArray().apply {
		add(position.x); add(position.y); add(position.z)
	}

	private fun stringMapElement(values: Map<String, String>): JsonObject = JsonObject().apply {
		values.toSortedMap().forEach(::addProperty)
	}

	private val RoomShape.jsonName: String
		get() = when (this) {
			RoomShape.ONE_BY_ONE -> "1x1"
			RoomShape.ONE_BY_TWO -> "1x2"
			RoomShape.ONE_BY_THREE -> "1x3"
			RoomShape.ONE_BY_FOUR -> "1x4"
			RoomShape.TWO_BY_TWO -> "2x2"
			RoomShape.L_SHAPED -> "l-shaped"
		}
}

private fun invalid(source: String, path: String, message: String, cause: Throwable? = null) =
	RawCaptureValidationException(source, path, message, cause)

private fun JsonElement.objectAt(source: String, path: String): JsonObject =
	if (isJsonObject) asJsonObject else throw invalid(source, path, "Expected an object")

private fun JsonElement.arrayAt(source: String, path: String): JsonArray =
	if (isJsonArray) asJsonArray else throw invalid(source, path, "Expected an array")

private fun JsonElement.stringAt(source: String, path: String): String =
	if (isJsonPrimitive && asJsonPrimitive.isString) asString else throw invalid(source, path, "Expected a string")

private fun JsonElement.intAt(source: String, path: String): Int {
	if (!isJsonPrimitive || !asJsonPrimitive.isNumber || !toString().matches(Regex("^-?[0-9]+$"))) {
		throw invalid(source, path, "Expected an integer")
	}
	return try { asInt } catch (error: NumberFormatException) {
		throw invalid(source, path, "Integer is out of range", error)
	}
}

private fun JsonElement.longAt(source: String, path: String): Long {
	if (!isJsonPrimitive || !asJsonPrimitive.isNumber || !toString().matches(Regex("^-?[0-9]+$"))) {
		throw invalid(source, path, "Expected an integer")
	}
	return try { asLong } catch (error: NumberFormatException) {
		throw invalid(source, path, "Integer is out of range", error)
	}
}

private fun JsonObject.onlyKeys(source: String, path: String, allowed: Set<String>) {
	entrySet().firstOrNull { it.key !in allowed }?.let {
		throw invalid(source, "$path.${it.key}", "Unknown field '${it.key}'")
	}
}

private fun JsonObject.required(name: String, source: String, path: String): JsonElement =
	get(name) ?: throw invalid(source, "$path.$name", "Required field is missing")

private fun JsonObject.requiredString(name: String, source: String, path: String): String =
	required(name, source, path).stringAt(source, "$path.$name")

private fun JsonObject.optionalString(name: String, source: String, path: String): String? =
	get(name)?.let { if (it.isJsonNull) null else it.stringAt(source, "$path.$name") }

private fun JsonObject.requiredInt(name: String, source: String, path: String): Int =
	required(name, source, path).intAt(source, "$path.$name")

private fun JsonObject.optionalInt(name: String, source: String, path: String): Int? =
	get(name)?.let { if (it.isJsonNull) null else it.intAt(source, "$path.$name") }

private fun JsonObject.requiredLong(name: String, source: String, path: String): Long =
	required(name, source, path).longAt(source, "$path.$name")

private fun JsonObject.requiredArray(name: String, source: String, path: String): JsonArray =
	required(name, source, path).arrayAt(source, "$path.$name")

private fun JsonObject.optionalArray(name: String, source: String, path: String): JsonArray? =
	get(name)?.let { if (it.isJsonNull) null else it.arrayAt(source, "$path.$name") }

private fun JsonObject.requiredObject(name: String, source: String, path: String): JsonObject =
	required(name, source, path).objectAt(source, "$path.$name")
