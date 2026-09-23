package com.andyy.funnymap.dungeon.match

import com.andyy.funnymap.dungeon.model.GridPosition
import com.andyy.funnymap.dungeon.model.RoomFootprint
import com.andyy.funnymap.dungeon.model.RoomId
import com.andyy.funnymap.dungeon.model.RoomType
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
import com.andyy.funnymap.dungeon.room.WorldCoordinate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class RoomMatchCacheTest {
	@Test
	fun `cache distinguishes exact and complete fingerprint hits`() {
		val fixture = fixture("rooms/cache")
		val matcher = RoomMatcher(fixture.database)
		val cache = RoomMatchCache()
		var calls = 0
		val dependency = listOf(MatchDataDependency("chunk:1:2", 4))

		val first = cache.resolve(fixture.observation, fixture.database.cacheIdentity, dependency) {
			calls++
			matcher.match(fixture.observation)
		}
		val exact = cache.resolve(fixture.observation, fixture.database.cacheIdentity, dependency) {
			calls++
			matcher.match(fixture.observation)
		}
		val newer = copyObservation(fixture.observation, "new-observation", revision = 2)
		val fingerprint = cache.resolve(newer, fixture.database.cacheIdentity, dependency) {
			calls++
			matcher.match(newer)
		}

		assertEquals(MatchCacheStatus.MISS, first.status)
		assertEquals(MatchCacheStatus.EXACT_HIT, exact.status)
		assertEquals(MatchCacheStatus.FINGERPRINT_HIT, fingerprint.status)
		assertEquals(2, fingerprint.result.observationRevision)
		assertEquals(1, calls)
		assertEquals(MatchCacheStats(1, 1, 1, 3), cache.stats())
	}

	@Test
	fun `dependency database and session changes invalidate reuse`() {
		val firstFixture = fixture("rooms/cache-a")
		val secondFixture = fixture("rooms/cache-b", lastBlock = "minecraft:netherrack")
		val cache = RoomMatchCache()
		var calls = 0
		fun resolve(
			fixture: Fixture,
			observation: RoomObservation,
			dependencyRevision: Long,
		): CachedRoomMatch = cache.resolve(
			observation,
			fixture.database.cacheIdentity,
			listOf(MatchDataDependency("chunk:0:0", dependencyRevision)),
		) {
			calls++
			RoomMatcher(fixture.database).match(observation)
		}

		resolve(firstFixture, firstFixture.observation, 1)
		resolve(firstFixture, copyObservation(firstFixture.observation, "dependency-change", 2), 2)
		resolve(secondFixture, secondFixture.observation, 2)
		val newSession = copyObservation(firstFixture.observation, "session-change", 3, session = 99)
		resolve(firstFixture, newSession, 2)

		assertEquals(4, calls)
		cache.retainSession(99)
		cache.retainDatabase(firstFixture.database.cacheIdentity)
		assertEquals(2, cache.stats().entries)
		cache.invalidateDependency("chunk:0:0")
		assertEquals(0, cache.stats().entries)
	}

	@Test
	fun `cached failures remain typed and revision safe`() {
		val fixture = fixture("rooms/failure")
		val badSamples = fixture.observation.samples.take(2)
		val incomplete = copyObservation(fixture.observation, "bad", 10, samples = badSamples)
		val cache = RoomMatchCache()
		val matcher = RoomMatcher(fixture.database)
		val first = cache.resolve(incomplete, fixture.database.cacheIdentity, emptyList()) { matcher.match(incomplete) }
		val second = cache.resolve(incomplete, fixture.database.cacheIdentity, emptyList()) { error("must hit cache") }

		assertIs<RoomMatchResult.Unknown>(first.result)
		assertIs<RoomMatchResult.Unknown>(second.result)
		assertEquals(MatchCacheStatus.EXACT_HIT, second.status)
		assertEquals(10, second.result.observationRevision)
	}

	private fun fixture(id: String, lastBlock: String = "minecraft:obsidian"): Fixture {
		val blocks = listOf(
			"minecraft:stone", "minecraft:cobblestone", "minecraft:granite", "minecraft:diorite",
			"minecraft:andesite", "minecraft:deepslate", "minecraft:tuff", lastBlock,
		)
		val samples = blocks.mapIndexed { index, block ->
			StructuralSample(LocalBlockPosition(index, 0, 0), block)
		}
		val bounds = LocalBlockBounds(LocalBlockPosition(0, 0, 0), LocalBlockPosition(7, 0, 0))
		val fingerprint = RoomFingerprint.create("base", bounds, samples = samples, policyVersion = POLICY.version)
		val definition = RoomDefinition(
			RoomId(id), id.substringAfter('/'), RoomType.NORMAL,
			RoomFootprint.of(GridPosition(0, 0)), 0, 0, listOf(fingerprint),
		)
		val database = RoomDatabase.create(
			fingerprintPolicyVersion = POLICY.version,
			definitions = listOf(definition),
			policies = mapOf(POLICY.version to POLICY),
		)
		val observation = RoomObservation(
			"observation-$id", 1, 1, GameDataVersion("26.1.2"), POLICY.version,
			definition.footprint, bounds, RoomLocalDatum(WorldCoordinate(0, 0, 0)), samples,
			ObservationCoverage.complete(bounds), ObservationProvenance(ObservationSource.HEADLESS_TEST),
		)
		return Fixture(database, observation)
	}

	private fun copyObservation(
		source: RoomObservation,
		id: String,
		revision: Long,
		session: Long = source.worldSessionGeneration,
		samples: List<StructuralSample> = source.samples,
	): RoomObservation = RoomObservation(
		id, session, revision, source.gameDataVersion, source.fingerprintPolicyVersion,
		source.footprint, source.bounds, source.datum, samples, source.coverage, source.provenance,
	)

	private data class Fixture(val database: RoomDatabase, val observation: RoomObservation)

	private companion object {
		val POLICY = FingerprintPolicy("cache-test-v1", setOf("minecraft"), minimumSamples = 8)
	}
}
