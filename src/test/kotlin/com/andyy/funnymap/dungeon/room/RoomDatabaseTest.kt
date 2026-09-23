package com.andyy.funnymap.dungeon.room

import com.andyy.funnymap.dungeon.model.GridPosition
import com.andyy.funnymap.dungeon.model.RoomFootprint
import com.andyy.funnymap.dungeon.model.RoomId
import com.andyy.funnymap.dungeon.model.RoomRotation
import com.andyy.funnymap.dungeon.model.RoomType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class RoomDatabaseTest {
	@Test
	fun `database JSON round trips validated definition`() {
		val definition = syntheticDefinition()
		val database = RoomDatabase.create(definitions = listOf(definition))

		val decoded = RoomDatabase.load(RoomDatabaseJson.encodeDatabase(database))

		assertEquals(database.schemaVersion, decoded.schemaVersion)
		assertEquals(database.fingerprintPolicyVersion, decoded.fingerprintPolicyVersion)
		assertEquals(listOf(definition), decoded.definitions)
		assertEquals(4, decoded.candidateIndex.views.size)
	}

	@Test
	fun `bundled empty production database loads`() {
		val database = RoomDatabase.loadResource()

		assertTrue(database.definitions.isEmpty())
		assertEquals(FingerprintPolicy.DEFAULT.version, database.fingerprintPolicyVersion)
		assertTrue(database.candidateIndex.views.isEmpty())
	}

	@Test
	fun `database cache identity includes room metadata`() {
		val first = RoomDatabase.create(definitions = listOf(syntheticDefinition(displayName = "First Name")))
		val renamed = RoomDatabase.create(definitions = listOf(syntheticDefinition(displayName = "Second Name")))

		assertNotEquals(first.cacheIdentity, renamed.cacheIdentity)
	}

	@Test
	fun `candidate definition JSON is accepted by database loader`() {
		val definition = syntheticDefinition()

		val database = RoomDatabase.load(RoomDatabaseJson.encodeCandidateDatabase(definition))

		assertEquals(definition, database[definition.id])
	}

	@Test
	fun `loader rejects malformed canonical room ids with path`() {
		val json = RoomDatabaseJson.encodeCandidateDatabase(syntheticDefinition())
			.replace("catacombs/synthetic", "catacombs//synthetic")

		val error = assertFailsWith<RoomDatabaseValidationException> { RoomDatabase.load(json) }

		assertTrue(error.message.orEmpty().contains("stable room id"))
		assertTrue(error.message.orEmpty().contains("$.rooms[0].id"))
	}

	@Test
	fun `loader rejects malformed block identifiers`() {
		val json = RoomDatabaseJson.encodeCandidateDatabase(syntheticDefinition())
			.replaceFirst("minecraft:stone", "Minecraft Stone")

		val error = assertFailsWith<RoomDatabaseValidationException> { RoomDatabase.load(json) }

		assertTrue(error.message.orEmpty().contains("block registry identifier"))
	}

	@Test
	fun `loader rejects fingerprint samples outside canonical bounds`() {
		val root = com.google.gson.JsonParser.parseString(
			RoomDatabaseJson.encodeCandidateDatabase(syntheticDefinition()),
		).asJsonObject
		val sample = root.getAsJsonArray("rooms")[0].asJsonObject
			.getAsJsonArray("fingerprints")[0].asJsonObject
			.getAsJsonArray("samples")[0].asJsonObject
		sample.add("pos", com.google.gson.JsonArray().apply { add(99); add(0); add(0) })

		val error = assertFailsWith<RoomDatabaseValidationException> { RoomDatabase.load(root.toString()) }

		assertTrue(error.message.orEmpty().contains("outside"))
		assertTrue(error.message.orEmpty().contains("$.rooms[0].fingerprints[0]"))
	}

	@Test
	fun `loader rejects malformed fingerprint ids`() {
		val json = RoomDatabaseJson.encodeCandidateDatabase(syntheticDefinition())
			.replaceFirst("\"id\": \"base\"", "\"id\": \"bad/id\"")

		val error = assertFailsWith<RoomDatabaseValidationException> { RoomDatabase.load(json) }

		assertTrue(error.message.orEmpty().contains("fingerprint id", ignoreCase = true))
	}

	@Test
	fun `loader rejects invalid block properties`() {
		val json = RoomDatabaseJson.encodeCandidateDatabase(syntheticDefinition())
			.replaceFirst("\"axis\"", "\"Invalid Property\"")

		val error = assertFailsWith<RoomDatabaseValidationException> { RoomDatabase.load(json) }

		assertTrue(error.message.orEmpty().contains("property name"))
	}

	@Test
	fun `loader rejects unsupported declared shapes`() {
		val json = RoomDatabaseJson.encodeCandidateDatabase(syntheticDefinition())
			.replaceFirst("\"1x2\"", "\"3x3\"")

		val error = assertFailsWith<RoomDatabaseValidationException> { RoomDatabase.load(json) }

		assertTrue(error.message.orEmpty().contains("Unsupported room shape"))
	}

	@Test
	fun `loader rejects malformed or stale digests`() {
		val json = RoomDatabaseJson.encodeCandidateDatabase(syntheticDefinition())
			.replace(Regex("sha256:[0-9a-f]{64}"), "sha256:${"0".repeat(64)}")

		val error = assertFailsWith<RoomDatabaseValidationException> { RoomDatabase.load(json) }

		assertTrue(error.message.orEmpty().contains("digest does not match"))
	}

	@Test
	fun `loader rejects duplicate fingerprint ids`() {
		val definition = com.google.gson.JsonParser.parseString(
			RoomDatabaseJson.encodeDefinition(syntheticDefinition()),
		).asJsonObject
		val fingerprints = definition.getAsJsonArray("fingerprints")
		fingerprints.add(fingerprints[0].deepCopy())
		val json = """{"schemaVersion":1,"fingerprintPolicy":"${FingerprintPolicy.DEFAULT.version}","rooms":[$definition]}"""

		val error = assertFailsWith<RoomDatabaseValidationException> { RoomDatabase.load(json) }

		assertTrue(
			error.message.orEmpty().contains("duplicate fingerprint ids", ignoreCase = true),
			error.message,
		)
	}

	@Test
	fun `database rejects duplicate room ids`() {
		val definition = syntheticDefinition()

		val error = assertFailsWith<RoomDatabaseValidationException> {
			RoomDatabase.create(definitions = listOf(definition, definition))
		}

		assertTrue(error.message.orEmpty().contains("Duplicate room id"))
	}

	@Test
	fun `database rejects fingerprints below policy evidence floor`() {
		val bounds = LocalBlockBounds(LocalBlockPosition(0, 0, 0), LocalBlockPosition(0, 0, 0))
		val fingerprint = RoomFingerprint.create(
			id = "base",
			bounds = bounds,
			samples = listOf(StructuralSample(bounds.min, "minecraft:stone")),
			policyVersion = FingerprintPolicy.DEFAULT.version,
		)
		val definition = RoomDefinition(
			id = RoomId("catacombs/too-small"),
			displayName = "Too Small",
			type = RoomType.NORMAL,
			footprint = RoomFootprint.of(GridPosition(0, 0)),
			secretCount = 0,
			cryptCount = 0,
			fingerprints = listOf(fingerprint),
		)

		val error = assertFailsWith<RoomDatabaseValidationException> {
			RoomDatabase.create(definitions = listOf(definition))
		}

		assertTrue(error.message.orEmpty().contains("policy requires"))
	}

	@Test
	fun `database rejects rotation-equivalent variants`() {
		val base = syntheticFingerprint("base")
		val rotated = StructuralNormalizer.rotate(
			base.samples,
			base.bounds,
			RoomRotation.DEGREES_90,
			FingerprintPolicy.DEFAULT,
		)
		val duplicate = RoomFingerprint.create(
			id = "rotated-copy",
			bounds = rotated.bounds,
			samples = rotated.samples,
			policyVersion = FingerprintPolicy.DEFAULT.version,
		)
		val definition = syntheticDefinition(listOf(base, duplicate))

		val error = assertFailsWith<RoomDatabaseValidationException> {
			RoomDatabase.create(definitions = listOf(definition))
		}

		assertTrue(error.message.orEmpty().contains("rotation-equivalent"))
	}

	@Test
	fun `diagnostics are defensively copied`() {
		val diagnostics = mutableListOf(DatabaseDiagnostic("$", "failure"))
		val error = RoomDatabaseValidationException(diagnostics)
		diagnostics.clear()

		assertEquals(1, error.diagnostics.size)
		assertNotNull(error.message)
	}

	private fun syntheticDefinition(
		fingerprints: Collection<RoomFingerprint> = listOf(syntheticFingerprint("base")),
		displayName: String = "Synthetic Gallery",
	): RoomDefinition = RoomDefinition(
		id = RoomId("catacombs/synthetic"),
		displayName = displayName,
		type = RoomType.NORMAL,
		footprint = RoomFootprint.of(GridPosition(0, 0), GridPosition(1, 0)),
		secretCount = 2,
		cryptCount = 1,
		fingerprints = fingerprints,
		provenance = RoomDataProvenance(
			gameVersions = setOf("26.1.2"),
			dataVersions = setOf(5000),
			captureIds = setOf("capture-a", "capture-b"),
			notes = "Synthetic fixture",
		),
	)

	private fun syntheticFingerprint(id: String): RoomFingerprint {
		val bounds = LocalBlockBounds(LocalBlockPosition(0, 0, 0), LocalBlockPosition(3, 1, 3))
		val samples = listOf(
			StructuralSample(LocalBlockPosition(0, 0, 0), "minecraft:stone"),
			StructuralSample(LocalBlockPosition(1, 0, 0), "minecraft:stone_bricks"),
			StructuralSample(LocalBlockPosition(2, 0, 0), "minecraft:mossy_stone_bricks"),
			StructuralSample(LocalBlockPosition(3, 0, 1), "minecraft:cracked_stone_bricks"),
			StructuralSample(LocalBlockPosition(0, 1, 2), "minecraft:oak_log", mapOf("axis" to "y")),
			StructuralSample(LocalBlockPosition(1, 1, 2), "minecraft:oak_stairs", mapOf("facing" to "north")),
			StructuralSample(LocalBlockPosition(2, 1, 3), "minecraft:polished_andesite"),
			StructuralSample(LocalBlockPosition(3, 1, 3), "minecraft:chiseled_stone_bricks"),
		)
		return RoomFingerprint.create(
			id = id,
			bounds = bounds,
			samples = samples,
			policyVersion = FingerprintPolicy.DEFAULT.version,
		)
	}
}
