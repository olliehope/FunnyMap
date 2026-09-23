package com.andyy.funnymap.dungeon.match

import com.andyy.funnymap.dungeon.room.RoomObservation
import com.andyy.funnymap.dungeon.room.StructuralNormalizer
import java.security.MessageDigest
import java.util.LinkedHashMap

data class MatchDataDependency(
	val id: String,
	val revision: Long,
) : Comparable<MatchDataDependency> {
	init {
		require(id.isNotBlank()) { "Match dependency id must not be blank" }
		require(revision >= 0) { "Match dependency revision must not be negative" }
	}

	override fun compareTo(other: MatchDataDependency): Int = compareValuesBy(
		this,
		other,
		MatchDataDependency::id,
		MatchDataDependency::revision,
	)
}

enum class MatchCacheStatus {
	EXACT_HIT,
	FINGERPRINT_HIT,
	MISS,
}

data class CachedRoomMatch(
	val result: RoomMatchResult,
	val status: MatchCacheStatus,
)

data class MatchCacheStats(
	val exactHits: Long,
	val fingerprintHits: Long,
	val misses: Long,
	val entries: Int,
)

/**
 * Session-scoped pure match cache. Partial observations are revision-exact; complete observations
 * may reuse a verified content fingerprint while dependency revisions remain identical.
 */
class RoomMatchCache(
	private val maximumEntries: Int = 512,
) {
	private val entries = object : LinkedHashMap<CacheKey, CacheEntry>(16, 0.75f, true) {
		override fun removeEldestEntry(eldest: MutableMap.MutableEntry<CacheKey, CacheEntry>?): Boolean =
			size > maximumEntries
	}
	private var exactHits = 0L
	private var fingerprintHits = 0L
	private var misses = 0L

	init {
		require(maximumEntries > 0) { "Maximum match cache entries must be positive" }
	}

	@Synchronized
	fun resolve(
		observation: RoomObservation,
		databaseIdentity: String,
		dependencies: Collection<MatchDataDependency>,
		match: () -> RoomMatchResult,
	): CachedRoomMatch {
		require(databaseIdentity.isNotBlank()) { "Database cache identity must not be blank" }
		val normalizedDependencies = dependencies.distinct().sorted()
		val exactKey = CacheKey.Exact(
			observation.worldSessionGeneration,
			observation.observationId,
			observation.revision,
			databaseIdentity,
			observation.fingerprintPolicyVersion,
			normalizedDependencies,
		)
		entries[exactKey]?.let { entry ->
			exactHits++
			return CachedRoomMatch(entry.result.retarget(observation), MatchCacheStatus.EXACT_HIT)
		}

		val fingerprintKey = observation.takeIf { it.coverage.isComplete }?.let {
			CacheKey.Fingerprint(
				sessionGeneration = it.worldSessionGeneration,
				databaseIdentity = databaseIdentity,
				policyVersion = it.fingerprintPolicyVersion,
				observationFingerprint = observationFingerprint(it),
				dependencies = normalizedDependencies,
			)
		}
		fingerprintKey?.let { key ->
			entries[key]?.let { entry ->
				fingerprintHits++
				val retargeted = entry.result.retarget(observation)
				entries[exactKey] = CacheEntry(retargeted, normalizedDependencies.map { it.id }.toSet())
				return CachedRoomMatch(retargeted, MatchCacheStatus.FINGERPRINT_HIT)
			}
		}

		misses++
		val result = match()
		require(result.worldSessionGeneration == observation.worldSessionGeneration) {
			"Matcher result session does not match its observation"
		}
		require(result.observationRevision == observation.revision) {
			"Matcher result revision does not match its observation"
		}
		val dependencyIds = normalizedDependencies.map { it.id }.toSet()
		entries[exactKey] = CacheEntry(result, dependencyIds)
		fingerprintKey?.let { entries[it] = CacheEntry(result, dependencyIds) }
		return CachedRoomMatch(result, MatchCacheStatus.MISS)
	}

	@Synchronized
	fun invalidateDependency(dependencyId: String) {
		entries.entries.removeIf { dependencyId in it.value.dependencyIds }
	}

	@Synchronized
	fun retainSession(sessionGeneration: Long) {
		entries.keys.removeIf { it.sessionGeneration != sessionGeneration }
	}

	@Synchronized
	fun retainDatabase(databaseIdentity: String) {
		entries.keys.removeIf { it.databaseIdentity != databaseIdentity }
	}

	@Synchronized
	fun clear() {
		entries.clear()
	}

	@Synchronized
	fun stats(): MatchCacheStats = MatchCacheStats(exactHits, fingerprintHits, misses, entries.size)

	private sealed interface CacheKey {
		val sessionGeneration: Long
		val databaseIdentity: String

		data class Exact(
			override val sessionGeneration: Long,
			val observationId: String,
			val observationRevision: Long,
			override val databaseIdentity: String,
			val policyVersion: String,
			val dependencies: List<MatchDataDependency>,
		) : CacheKey

		data class Fingerprint(
			override val sessionGeneration: Long,
			override val databaseIdentity: String,
			val policyVersion: String,
			val observationFingerprint: String,
			val dependencies: List<MatchDataDependency>,
		) : CacheKey
	}

	private data class CacheEntry(
		val result: RoomMatchResult,
		val dependencyIds: Set<String>,
	)
}

private fun observationFingerprint(observation: RoomObservation): String {
	val structure = StructuralNormalizer.sha256(observation.bounds, observation.samples)
	val footprint = observation.footprint.localCells.sorted().joinToString(";") { "${it.column},${it.row}" }
	val canonical = "$structure|$footprint|${observation.bounds}"
	val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
	return "sha256:" + digest.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
}

private fun RoomMatchResult.retarget(observation: RoomObservation): RoomMatchResult = when (this) {
	is RoomMatchResult.Known -> copy(
		observationId = observation.observationId,
		worldSessionGeneration = observation.worldSessionGeneration,
		observationRevision = observation.revision,
	)
	is RoomMatchResult.Unknown -> copy(
		observationId = observation.observationId,
		worldSessionGeneration = observation.worldSessionGeneration,
		observationRevision = observation.revision,
	)
}
