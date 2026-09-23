package com.andyy.funnymap.dungeon.match

import com.andyy.funnymap.dungeon.model.GridPosition
import com.andyy.funnymap.dungeon.model.RecognitionUnknownReason
import com.andyy.funnymap.dungeon.model.RoomFootprint
import com.andyy.funnymap.dungeon.model.RoomId
import com.andyy.funnymap.dungeon.model.RoomOrientation
import com.andyy.funnymap.dungeon.model.RoomRotation
import com.andyy.funnymap.dungeon.model.RoomType
import com.andyy.funnymap.dungeon.room.CoverageRegion
import com.andyy.funnymap.dungeon.room.FingerprintPolicy
import com.andyy.funnymap.dungeon.room.GameDataVersion
import com.andyy.funnymap.dungeon.room.LocalBlockBounds
import com.andyy.funnymap.dungeon.room.LocalBlockPosition
import com.andyy.funnymap.dungeon.room.ObservationCoverage
import com.andyy.funnymap.dungeon.room.ObservationProvenance
import com.andyy.funnymap.dungeon.room.ObservationSource
import com.andyy.funnymap.dungeon.room.RoomDatabase
import com.andyy.funnymap.dungeon.room.RoomDefinition
import com.andyy.funnymap.dungeon.room.RoomFingerprint
import com.andyy.funnymap.dungeon.room.RoomLocalDatum
import com.andyy.funnymap.dungeon.room.RoomObservation
import com.andyy.funnymap.dungeon.room.StructuralSample
import com.andyy.funnymap.dungeon.room.UnavailableReason
import com.andyy.funnymap.dungeon.room.UnavailableRegion
import com.andyy.funnymap.dungeon.room.WorldCoordinate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RoomMatcherTest {
	@Test
	fun `exact observation produces a verified room match`() {
		val database = database(definition("rooms/exact", samples = asymmetricSamples()))
		val result = RoomMatcher(database).match(observation(database, "rooms/exact"))

		val known = assertIs<RoomMatchResult.Known>(result)
		assertEquals("rooms/exact", known.definition.id.value)
		assertEquals(RoomOrientation.Known(RoomRotation.DEGREES_0), known.orientation)
		assertEquals(12, known.recognition.evidence.matchedSampleCount)
		assertEquals(0, known.recognition.evidence.conflictingSampleCount)
		assertEquals(1.0, known.recognition.evidence.candidateScore, 0.000_001)
	}

	@Test
	fun `partial but sufficiently covered observation is accepted`() {
		val database = database(definition("rooms/partial", samples = asymmetricSamples()))
		val result = RoomMatcher(database).match(observation(database, "rooms/partial", availableCount = 9))

		val known = assertIs<RoomMatchResult.Known>(result)
		assertEquals(9, known.recognition.evidence.matchedSampleCount)
		assertEquals(0.75, known.recognition.evidence.availableCoverage)
		assertEquals(0.75, known.recognition.evidence.totalDefinitionCoverage)
	}

	@Test
	fun `too few available samples distinguish insufficient evidence from unavailable chunks`() {
		val database = database(definition("rooms/evidence", samples = asymmetricSamples()))
		val insufficient = RoomMatcher(database).match(
			observation(database, "rooms/evidence", includedCount = 4),
		)
		val unavailable = RoomMatcher(database).match(
			observation(database, "rooms/evidence", availableCount = 6),
		)

		assertEquals(RecognitionUnknownReason.INSUFFICIENT_EVIDENCE, assertIs<RoomMatchResult.Unknown>(insufficient).reason)
		assertEquals(RecognitionUnknownReason.CHUNK_UNAVAILABLE, assertIs<RoomMatchResult.Unknown>(unavailable).reason)
	}

	@Test
	fun `unrelated structural observation has no database match`() {
		val database = database(definition("rooms/known", samples = asymmetricSamples()))
		val wrong = asymmetricSamples().map { StructuralSample(it.position, "minecraft:netherrack") }

		val result = RoomMatcher(database).match(observation(database, "rooms/known", overrideSamples = wrong))

		assertEquals(RecognitionUnknownReason.NO_DATABASE_MATCH, assertIs<RoomMatchResult.Unknown>(result).reason)
	}

	@Test
	fun `one conflict is tolerated but multiple conflicts are rejected`() {
		val database = database(definition("rooms/conflicts", samples = asymmetricSamples()))
		val base = view(database, "rooms/conflicts", RoomRotation.DEGREES_0).samples
		val oneConflict = base.mapIndexed { index, sample ->
			if (index == 0) StructuralSample(sample.position, "minecraft:netherrack") else sample
		}
		val threeConflicts = base.mapIndexed { index, sample ->
			if (index < 3) StructuralSample(sample.position, "minecraft:netherrack") else sample
		}

		val accepted = RoomMatcher(database).match(observation(database, "rooms/conflicts", overrideSamples = oneConflict))
		val rejected = RoomMatcher(database).match(observation(database, "rooms/conflicts", overrideSamples = threeConflicts))

		assertEquals(1, assertIs<RoomMatchResult.Known>(accepted).recognition.evidence.conflictingSampleCount)
		assertEquals(RecognitionUnknownReason.NO_DATABASE_MATCH, assertIs<RoomMatchResult.Unknown>(rejected).reason)
		assertEquals(3, rejected.recognition.evidence.conflictingSampleCount)
	}

	@Test
	fun `different room identities require a clear winner margin`() {
		val first = definition("rooms/winner", samples = asymmetricSamples())
		val altered = asymmetricSamples().mapIndexed { index, sample ->
			if (index < 3) StructuralSample(sample.position, "minecraft:netherrack") else sample
		}
		val second = definition("rooms/runner", samples = altered)
		val database = database(first, second)

		val result = RoomMatcher(database).match(observation(database, "rooms/winner"))

		val known = assertIs<RoomMatchResult.Known>(result)
		assertEquals("rooms/winner", known.definition.id.value)
		assertTrue(known.recognition.evidence.scoreMargin!! > 0.08)
		assertTrue(known.recognition.evidence.runnerUpScore!! < known.recognition.evidence.candidateScore)
	}

	@Test
	fun `identical candidates are ambiguous and near candidates can fail a configured margin`() {
		val samples = asymmetricSamples()
		val ambiguousDatabase = database(
			definition("rooms/ambiguous-a", samples = samples),
			definition("rooms/ambiguous-b", samples = samples),
		)
		val ambiguous = RoomMatcher(ambiguousDatabase).match(observation(ambiguousDatabase, "rooms/ambiguous-a"))
		assertEquals(RecognitionUnknownReason.AMBIGUOUS_MATCH, assertIs<RoomMatchResult.Unknown>(ambiguous).reason)

		val nearSamples = samples.mapIndexed { index, sample ->
			if (index == 0) StructuralSample(sample.position, "minecraft:netherrack") else sample
		}
		val nearDatabase = database(
			definition("rooms/near-a", samples = samples),
			definition("rooms/near-b", samples = nearSamples),
		)
		val near = RoomMatcher(nearDatabase, RoomMatchConfig(minimumWinnerMargin = 0.10))
			.match(observation(nearDatabase, "rooms/near-a"))
		assertEquals(RecognitionUnknownReason.AMBIGUOUS_MATCH, assertIs<RoomMatchResult.Unknown>(near).reason)
	}

	@Test
	fun `all four rotations are evaluated without serialized copies`() {
		val database = database(definition("rooms/rotations", samples = asymmetricSamples()))
		for (rotation in RoomRotation.entries) {
			val result = RoomMatcher(database).match(observation(database, "rooms/rotations", rotation))
			val known = assertIs<RoomMatchResult.Known>(result)
			assertEquals(RoomOrientation.Known(rotation), known.orientation)
			assertEquals(rotation, known.recognition.evidence.candidateRotation)
		}
	}

	@Test
	fun `symmetric room keeps known identity with unknown orientation`() {
		val bounds = LocalBlockBounds(LocalBlockPosition(0, 0, 0), LocalBlockPosition(2, 0, 2))
		val samples = buildList {
			for (x in 0..2) for (z in 0..2) if (x != 1 || z != 1) {
				add(StructuralSample(LocalBlockPosition(x, 0, z), "minecraft:stone"))
			}
		}
		val database = database(definition("rooms/symmetric", bounds = bounds, samples = samples))

		val result = RoomMatcher(database).match(observation(database, "rooms/symmetric"))

		val known = assertIs<RoomMatchResult.Known>(result)
		assertEquals("rooms/symmetric", known.definition.id.value)
		assertEquals(RoomOrientation.Unknown, known.orientation)
	}

	@Test
	fun `non-square and L-shaped multi-cell footprints rotate compatibly`() {
		val rectangle = definition(
			"rooms/rectangle",
			footprint = RoomFootprint.of(GridPosition(0, 0), GridPosition(1, 0)),
			bounds = LocalBlockBounds(LocalBlockPosition(0, 0, 0), LocalBlockPosition(3, 0, 2)),
			samples = asymmetricSamples(),
		)
		val lShape = definition(
			"rooms/l-shape",
			footprint = RoomFootprint.of(GridPosition(0, 0), GridPosition(1, 0), GridPosition(0, 1)),
			bounds = LocalBlockBounds(LocalBlockPosition(0, 0, 0), LocalBlockPosition(3, 0, 2)),
			samples = asymmetricSamples(),
		)
		val database = database(rectangle, lShape)

		val rectangleResult = assertIs<RoomMatchResult.Known>(
			RoomMatcher(database).match(observation(database, "rooms/rectangle", RoomRotation.DEGREES_90)),
		)
		val lResult = assertIs<RoomMatchResult.Known>(
			RoomMatcher(database).match(observation(database, "rooms/l-shape", RoomRotation.DEGREES_270)),
		)

		assertEquals(setOf(GridPosition(0, 0), GridPosition(0, 1)), rectangleResult.observedFootprint.localCells)
		assertEquals(
			setOf(GridPosition(0, 0), GridPosition(0, 1), GridPosition(1, 1)),
			lResult.observedFootprint.localCells,
		)
	}

	@Test
	fun `footprint compatibility filters identical structural candidates`() {
		val oneCell = definition("rooms/one-cell", samples = asymmetricSamples())
		val twoCells = definition(
			"rooms/two-cells",
			footprint = RoomFootprint.of(GridPosition(0, 0), GridPosition(1, 0)),
			samples = asymmetricSamples(),
		)
		val database = database(oneCell, twoCells)

		val result = RoomMatcher(database).match(observation(database, "rooms/two-cells"))

		assertEquals("rooms/two-cells", assertIs<RoomMatchResult.Known>(result).definition.id.value)
	}

	@Test
	fun `policy mismatch is a typed failure rather than an exception`() {
		val database = database(definition("rooms/policy", samples = asymmetricSamples()))
		val original = observation(database, "rooms/policy")
		val mismatch = copyObservation(original, fingerprintPolicyVersion = "different-policy")

		val result = RoomMatcher(database).match(mismatch)

		assertEquals(RecognitionUnknownReason.INSUFFICIENT_EVIDENCE, assertIs<RoomMatchResult.Unknown>(result).reason)
		assertTrue(result.diagnostics.message.contains("policy", ignoreCase = true))
	}

	private fun database(vararg definitions: RoomDefinition): RoomDatabase = RoomDatabase.create(
		fingerprintPolicyVersion = POLICY.version,
		definitions = definitions.asList(),
		policies = mapOf(POLICY.version to POLICY),
	)

	private fun definition(
		id: String,
		footprint: RoomFootprint = RoomFootprint.of(GridPosition(0, 0)),
		bounds: LocalBlockBounds = BOUNDS,
		samples: List<StructuralSample>,
	): RoomDefinition = RoomDefinition(
		id = RoomId(id),
		displayName = id.substringAfter('/'),
		type = RoomType.NORMAL,
		footprint = footprint,
		secretCount = 2,
		cryptCount = 1,
		fingerprints = listOf(RoomFingerprint.create("base", bounds, samples = samples, policyVersion = POLICY.version)),
	)

	private fun observation(
		database: RoomDatabase,
		roomId: String,
		rotation: RoomRotation = RoomRotation.DEGREES_0,
		availableCount: Int? = null,
		includedCount: Int? = null,
		overrideSamples: List<StructuralSample>? = null,
	): RoomObservation {
		val view = view(database, roomId, rotation)
		val allSamples = overrideSamples ?: view.samples
		val availablePositions = view.samples.take(availableCount ?: view.samples.size).map { it.position }.toSet()
		val unavailablePositions = view.samples.map { it.position }.toSet() - availablePositions
		val included = allSamples.filter { it.position in availablePositions }.take(includedCount ?: Int.MAX_VALUE)
		val coverage = ObservationCoverage(
			availableRegions = availablePositions.map { CoverageRegion(LocalBlockBounds(it, it)) },
			unavailableRegions = unavailablePositions.map {
				UnavailableRegion(CoverageRegion(LocalBlockBounds(it, it)), UnavailableReason.CHUNK_UNAVAILABLE)
			},
			expectedPositions = view.samples.map { it.position },
		)
		return RoomObservation(
			observationId = "observation-$roomId-${rotation.degrees}",
			worldSessionGeneration = 7,
			revision = rotation.ordinal.toLong() + 1,
			gameDataVersion = GameDataVersion("26.1.2", 9999, "main"),
			fingerprintPolicyVersion = POLICY.version,
			footprint = view.footprint,
			bounds = view.bounds,
			datum = RoomLocalDatum(WorldCoordinate(0, 0, 0)),
			samples = included,
			coverage = coverage,
			provenance = ObservationProvenance(ObservationSource.HEADLESS_TEST),
			observedRotation = null,
		)
	}

	private fun view(database: RoomDatabase, roomId: String, rotation: RoomRotation): CandidateView =
		database.candidateIndex.views.single { it.key.roomId.value == roomId && it.key.rotation == rotation }

	private fun copyObservation(
		observation: RoomObservation,
		fingerprintPolicyVersion: String,
	): RoomObservation = RoomObservation(
		observation.observationId,
		observation.worldSessionGeneration,
		observation.revision,
		observation.gameDataVersion,
		fingerprintPolicyVersion,
		observation.footprint,
		observation.bounds,
		observation.datum,
		observation.samples,
		observation.coverage,
		observation.provenance,
		observation.observedRotation,
	)

	private fun asymmetricSamples(): List<StructuralSample> = BLOCKS.mapIndexed { index, block ->
		StructuralSample(LocalBlockPosition(index % 4, 0, index / 4), block)
	}

	private companion object {
		val POLICY = FingerprintPolicy(
			version = "matcher-test-v1",
			allowedNamespaces = setOf("minecraft"),
			minimumSamples = 8,
		)
		val BOUNDS = LocalBlockBounds(LocalBlockPosition(0, 0, 0), LocalBlockPosition(3, 0, 2))
		val BLOCKS = listOf(
			"minecraft:stone",
			"minecraft:cobblestone",
			"minecraft:granite",
			"minecraft:diorite",
			"minecraft:andesite",
			"minecraft:deepslate",
			"minecraft:tuff",
			"minecraft:calcite",
			"minecraft:bricks",
			"minecraft:obsidian",
			"minecraft:oak_planks",
			"minecraft:birch_planks",
		)
	}
}
