package com.andyy.funnymap.dungeon.room

import com.andyy.funnymap.dungeon.model.GridPosition
import com.andyy.funnymap.dungeon.model.RoomFootprint
import com.andyy.funnymap.dungeon.model.RoomId
import com.andyy.funnymap.dungeon.model.RoomShape
import com.andyy.funnymap.dungeon.model.RoomType
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonParser

object RoomDatabaseJson {
	private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()

	fun decode(
		json: String,
		policies: Map<String, FingerprintPolicy> = RoomDatabase.defaultPolicies(),
	): RoomDatabase {
		val root = try {
			JsonParser.parseString(json).objectAt("$")
		} catch (error: RoomDatabaseValidationException) {
			throw error
		} catch (error: JsonParseException) {
			throw failure("$", "Malformed JSON: ${error.message}", error)
		} catch (error: IllegalStateException) {
			throw failure("$", error.message ?: "Malformed JSON", error)
		}
		return try {
			root.onlyKeys("$", setOf("schemaVersion", "fingerprintPolicy", "rooms"))
			val schemaVersion = root.requiredInt("schemaVersion", "$")
			val policyVersion = root.requiredString("fingerprintPolicy", "$")
			val rooms = root.requiredArray("rooms", "$").mapIndexed { index, element ->
				parseDefinition(element.objectAt("$.rooms[$index]"), "$.rooms[$index]", policyVersion)
			}
			RoomDatabase.parsed(schemaVersion, policyVersion, rooms, policies)
		} catch (error: RoomDatabaseValidationException) {
			throw error
		} catch (error: SchemaProblem) {
			throw failure(error.path, error.message ?: "Invalid room database", error)
		} catch (error: IllegalArgumentException) {
			throw failure("$", error.message ?: "Invalid room database", error)
		}
	}

	fun encodeDatabase(database: RoomDatabase): String {
		val root = JsonObject()
		root.addProperty("schemaVersion", database.schemaVersion)
		root.addProperty("fingerprintPolicy", database.fingerprintPolicyVersion)
		root.add("rooms", JsonArray().also { array ->
			database.definitions.forEach { array.add(definitionElement(it)) }
		})
		return gson.toJson(root) + "\n"
	}

	fun encodeDefinition(definition: RoomDefinition): String =
		gson.toJson(definitionElement(definition)) + "\n"

	fun encodeCandidateDatabase(
		definition: RoomDefinition,
		schemaVersion: Int = RoomDatabase.CURRENT_SCHEMA_VERSION,
		fingerprintPolicyVersion: String = definition.fingerprints.singleOrNull()?.policyVersion
			?: FingerprintPolicy.DEFAULT.version,
	): String {
		val root = JsonObject()
		root.addProperty("schemaVersion", schemaVersion)
		root.addProperty("fingerprintPolicy", fingerprintPolicyVersion)
		root.add("rooms", JsonArray().also { it.add(definitionElement(definition)) })
		return gson.toJson(root) + "\n"
	}

	private fun parseDefinition(root: JsonObject, path: String, databasePolicy: String): RoomDefinition {
		root.onlyKeys(
			path,
			setOf("id", "name", "type", "shape", "secrets", "crypts", "fingerprints", "provenance"),
		)
		val id = root.requiredString("id", path)
		if (!Identifiers.isStableRoomId(id)) problem("$path.id", "Malformed stable room id '$id'")
		val name = root.requiredString("name", path)
		val typeName = root.requiredString("type", path)
		val type = RoomType.entries.firstOrNull { it.name == typeName }
			?: problem("$path.type", "Unknown room type '$typeName'")
		val footprint = parseFootprint(root.requiredObject("shape", path), "$path.shape")
		val fingerprints = root.requiredArray("fingerprints", path).mapIndexed { index, element ->
			parseFingerprint(
				element.objectAt("$path.fingerprints[$index]"),
				"$path.fingerprints[$index]",
				databasePolicy,
			)
		}
		return try {
			RoomDefinition(
				id = RoomId(id),
				displayName = name,
				type = type,
				footprint = footprint,
				secretCount = root.requiredInt("secrets", path),
				cryptCount = root.requiredInt("crypts", path),
				fingerprints = fingerprints,
				provenance = root.optionalObject("provenance", path)?.let {
					parseProvenance(it, "$path.provenance")
				},
			)
		} catch (error: IllegalArgumentException) {
			problem(path, error.message ?: "Invalid room definition", error)
		}
	}

	private fun parseFootprint(root: JsonObject, path: String): RoomFootprint {
		root.onlyKeys(path, setOf("kind", "cells"))
		val kind = root.requiredString("kind", path)
		val expectedShape = when (kind) {
			"1x1" -> RoomShape.ONE_BY_ONE
			"1x2" -> RoomShape.ONE_BY_TWO
			"1x3" -> RoomShape.ONE_BY_THREE
			"1x4" -> RoomShape.ONE_BY_FOUR
			"2x2" -> RoomShape.TWO_BY_TWO
			"l-shaped" -> RoomShape.L_SHAPED
			else -> problem("$path.kind", "Unsupported room shape '$kind'")
		}
		val cells = root.requiredArray("cells", path).mapIndexed { index, element ->
			val pair = element.arrayAt("$path.cells[$index]")
			if (pair.size() != 2) problem("$path.cells[$index]", "Footprint cell must contain exactly two integers")
			GridPosition(
				column = pair[0].intAt("$path.cells[$index][0]"),
				row = pair[1].intAt("$path.cells[$index][1]"),
			)
		}
		val footprint = try {
			RoomFootprint.of(cells)
		} catch (error: IllegalArgumentException) {
			problem(path, error.message ?: "Unsupported footprint", error)
		}
		if (footprint.shape != expectedShape) {
			problem(path, "Shape kind '$kind' does not match occupied cells (${footprint.shape})")
		}
		if (footprint.anchor != GridPosition(0, 0)) {
			problem("$path.cells", "Canonical footprint coordinates must have a zero minimum")
		}
		return footprint
	}

	private fun parseFingerprint(root: JsonObject, path: String, databasePolicy: String): RoomFingerprint {
		root.onlyKeys(path, setOf("id", "origin", "bounds", "samples", "digest", "policyVersion", "provenance"))
		val policyVersion = root.optionalString("policyVersion", path) ?: databasePolicy
		val bounds = parseBounds(root.requiredObject("bounds", path), "$path.bounds")
		val samples = root.requiredArray("samples", path).mapIndexed { index, element ->
			parseSample(element.objectAt("$path.samples[$index]"), "$path.samples[$index]")
		}
		return try {
			RoomFingerprint.validated(
				id = root.requiredString("id", path),
				bounds = bounds,
				origin = parsePosition(root.requiredArray("origin", path), "$path.origin"),
				samples = samples,
				digest = root.requiredString("digest", path),
				policyVersion = policyVersion,
				provenance = root.optionalObject("provenance", path)?.let {
					parseProvenance(it, "$path.provenance")
				},
			)
		} catch (error: IllegalArgumentException) {
			problem(path, error.message ?: "Invalid room fingerprint", error)
		}
	}

	private fun parseBounds(root: JsonObject, path: String): LocalBlockBounds {
		root.onlyKeys(path, setOf("min", "max"))
		return try {
			LocalBlockBounds(
				min = parsePosition(root.requiredArray("min", path), "$path.min"),
				max = parsePosition(root.requiredArray("max", path), "$path.max"),
			)
		} catch (error: IllegalArgumentException) {
			problem(path, error.message ?: "Invalid local bounds", error)
		}
	}

	private fun parseSample(root: JsonObject, path: String): StructuralSample {
		root.onlyKeys(path, setOf("pos", "block", "properties"))
		val propertiesRoot = root.optionalObject("properties", path) ?: JsonObject()
		val properties = propertiesRoot.entrySet().associate { (name, element) ->
			name to element.stringAt("$path.properties.$name")
		}
		return try {
			StructuralSample(
				position = parsePosition(root.requiredArray("pos", path), "$path.pos"),
				blockId = root.requiredString("block", path),
				properties = properties,
			)
		} catch (error: IllegalArgumentException) {
			problem(path, error.message ?: "Invalid structural sample", error)
		}
	}

	private fun parsePosition(array: JsonArray, path: String): LocalBlockPosition {
		if (array.size() != 3) problem(path, "Block position must contain exactly three integers")
		return LocalBlockPosition(
			x = array[0].intAt("$path[0]"),
			y = array[1].intAt("$path[1]"),
			z = array[2].intAt("$path[2]"),
		)
	}

	private fun parseProvenance(root: JsonObject, path: String): RoomDataProvenance {
		root.onlyKeys(path, setOf("gameVersions", "dataVersions", "captureIds", "notes"))
		return try {
			RoomDataProvenance(
				gameVersions = root.optionalArray("gameVersions", path)?.mapIndexed { index, element ->
					element.stringAt("$path.gameVersions[$index]")
				}.orEmpty(),
				dataVersions = root.optionalArray("dataVersions", path)?.mapIndexed { index, element ->
					element.intAt("$path.dataVersions[$index]")
				}.orEmpty(),
				captureIds = root.optionalArray("captureIds", path)?.mapIndexed { index, element ->
					element.stringAt("$path.captureIds[$index]")
				}.orEmpty(),
				notes = root.optionalString("notes", path),
			)
		} catch (error: IllegalArgumentException) {
			problem(path, error.message ?: "Invalid provenance", error)
		}
	}

	private fun definitionElement(definition: RoomDefinition): JsonObject = JsonObject().apply {
		addProperty("id", definition.id.value)
		addProperty("name", definition.displayName)
		addProperty("type", definition.type.name)
		add("shape", JsonObject().also { shape ->
			shape.addProperty("kind", definition.shape.jsonName)
			shape.add("cells", JsonArray().also { cells ->
				definition.footprint.localCells.sorted().forEach { cell ->
					cells.add(JsonArray().also { pair ->
						pair.add(cell.column)
						pair.add(cell.row)
					})
				}
			})
		})
		addProperty("secrets", definition.secretCount)
		addProperty("crypts", definition.cryptCount)
		add("fingerprints", JsonArray().also { fingerprints ->
			definition.fingerprints.forEach { fingerprints.add(fingerprintElement(it)) }
		})
		definition.provenance?.let { add("provenance", provenanceElement(it)) }
	}

	private fun fingerprintElement(fingerprint: RoomFingerprint): JsonObject = JsonObject().apply {
		addProperty("id", fingerprint.id)
		add("origin", positionElement(fingerprint.origin))
		add("bounds", JsonObject().also { bounds ->
			bounds.add("min", positionElement(fingerprint.bounds.min))
			bounds.add("max", positionElement(fingerprint.bounds.max))
		})
		add("samples", JsonArray().also { samples ->
			fingerprint.samples.forEach { sample ->
				samples.add(JsonObject().also { encoded ->
					encoded.add("pos", positionElement(sample.position))
					encoded.addProperty("block", sample.blockId)
					encoded.add("properties", JsonObject().also { properties ->
						sample.properties.forEach(properties::addProperty)
					})
				})
			}
		})
		addProperty("digest", fingerprint.digest)
		addProperty("policyVersion", fingerprint.policyVersion)
		fingerprint.provenance?.let { add("provenance", provenanceElement(it)) }
	}

	private fun provenanceElement(provenance: RoomDataProvenance): JsonObject = JsonObject().apply {
		if (provenance.gameVersions.isNotEmpty()) add("gameVersions", JsonArray().also { array ->
			provenance.gameVersions.forEach(array::add)
		})
		if (provenance.dataVersions.isNotEmpty()) add("dataVersions", JsonArray().also { array ->
			provenance.dataVersions.forEach(array::add)
		})
		if (provenance.captureIds.isNotEmpty()) add("captureIds", JsonArray().also { array ->
			provenance.captureIds.forEach(array::add)
		})
		provenance.notes?.let { addProperty("notes", it) }
	}

	private fun positionElement(position: LocalBlockPosition): JsonArray = JsonArray().apply {
		add(position.x)
		add(position.y)
		add(position.z)
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

	private fun failure(path: String, message: String, cause: Throwable? = null) =
		RoomDatabaseValidationException(listOf(DatabaseDiagnostic(path, message)), cause)

	private fun problem(path: String, message: String, cause: Throwable? = null): Nothing =
		throw SchemaProblem(path, message, cause)

	private class SchemaProblem(val path: String, message: String, cause: Throwable? = null) :
		IllegalArgumentException(message, cause)
}

private fun JsonElement.objectAt(path: String): JsonObject =
	if (isJsonObject) asJsonObject else throw schemaProblem(path, "Expected an object")

private fun JsonElement.arrayAt(path: String): JsonArray =
	if (isJsonArray) asJsonArray else throw schemaProblem(path, "Expected an array")

private fun JsonElement.stringAt(path: String): String =
	if (isJsonPrimitive && asJsonPrimitive.isString) asString else throw schemaProblem(path, "Expected a string")

private fun JsonElement.intAt(path: String): Int {
	if (!isJsonPrimitive || !asJsonPrimitive.isNumber || !toString().matches(Regex("^-?[0-9]+$"))) {
		throw schemaProblem(path, "Expected an integer")
	}
	return try {
		asInt
	} catch (error: NumberFormatException) {
		throw schemaProblem(path, "Integer is out of range", error)
	}
}

private fun JsonObject.onlyKeys(path: String, allowed: Set<String>) {
	entrySet().firstOrNull { it.key !in allowed }?.let {
		throw schemaProblem("$path.${it.key}", "Unknown field '${it.key}'")
	}
}

private fun JsonObject.required(name: String, path: String): JsonElement =
	get(name) ?: throw schemaProblem("$path.$name", "Required field is missing")

private fun JsonObject.requiredString(name: String, path: String): String =
	required(name, path).stringAt("$path.$name")

private fun JsonObject.optionalString(name: String, path: String): String? =
	get(name)?.let { if (it.isJsonNull) null else it.stringAt("$path.$name") }

private fun JsonObject.requiredInt(name: String, path: String): Int =
	required(name, path).intAt("$path.$name")

private fun JsonObject.requiredArray(name: String, path: String): JsonArray =
	required(name, path).arrayAt("$path.$name")

private fun JsonObject.optionalArray(name: String, path: String): JsonArray? =
	get(name)?.let { if (it.isJsonNull) null else it.arrayAt("$path.$name") }

private fun JsonObject.requiredObject(name: String, path: String): JsonObject =
	required(name, path).objectAt("$path.$name")

private fun JsonObject.optionalObject(name: String, path: String): JsonObject? =
	get(name)?.let { if (it.isJsonNull) null else it.objectAt("$path.$name") }

private fun schemaProblem(path: String, message: String, cause: Throwable? = null): IllegalArgumentException =
	RoomDatabaseValidationException(listOf(DatabaseDiagnostic(path, message)), cause)
