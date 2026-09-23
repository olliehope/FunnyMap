package com.andyy.funnymap.dungeon.match

import com.andyy.funnymap.dungeon.model.RoomFootprint
import com.andyy.funnymap.dungeon.model.RoomId
import com.andyy.funnymap.dungeon.model.RoomRotation
import com.andyy.funnymap.dungeon.room.FingerprintPolicy
import com.andyy.funnymap.dungeon.room.LocalBlockBounds
import com.andyy.funnymap.dungeon.room.LocalBlockPosition
import com.andyy.funnymap.dungeon.room.PolicyDecision
import com.andyy.funnymap.dungeon.room.RoomDatabase
import com.andyy.funnymap.dungeon.room.RoomDefinition
import com.andyy.funnymap.dungeon.room.RoomFingerprint
import com.andyy.funnymap.dungeon.room.RoomObservation
import com.andyy.funnymap.dungeon.room.RotationIssue
import com.andyy.funnymap.dungeon.room.StructuralNormalizer
import com.andyy.funnymap.dungeon.room.StructuralSample
import java.util.Collections
import java.util.TreeMap

class StructuralToken(
	val position: LocalBlockPosition,
	val blockId: String,
	properties: Map<String, String>,
) {
	val properties: Map<String, String> = Collections.unmodifiableMap(TreeMap(properties))

	constructor(sample: StructuralSample) : this(sample.position, sample.blockId, sample.properties)

	override fun equals(other: Any?): Boolean =
		other is StructuralToken &&
			position == other.position &&
			blockId == other.blockId &&
			properties == other.properties

	override fun hashCode(): Int = 31 * (31 * position.hashCode() + blockId.hashCode()) + properties.hashCode()
}

data class CandidateViewKey(
	val roomId: RoomId,
	val fingerprintId: String,
	val rotation: RoomRotation,
)

class CandidateView internal constructor(
	val key: CandidateViewKey,
	val definition: RoomDefinition,
	val fingerprint: RoomFingerprint,
	val footprint: RoomFootprint,
	val bounds: LocalBlockBounds,
	samples: Collection<StructuralSample>,
	rotationIssues: Collection<RotationIssue>,
) {
	val samples: List<StructuralSample> = Collections.unmodifiableList(ArrayList(samples))
	val rotationIssues: List<RotationIssue> = Collections.unmodifiableList(ArrayList(rotationIssues))

	val tokens: Set<StructuralToken> = Collections.unmodifiableSet(
		LinkedHashSet(this.samples.map(::StructuralToken)),
	)
}

data class UnsupportedCandidateRotation(
	val key: CandidateViewKey,
	val issues: List<RotationIssue>,
)

data class CandidateHit(
	val candidate: CandidateView,
	val matchedTokenCount: Int,
	val observedTokenCount: Int,
	val candidateTokenCount: Int,
	val availableCandidateTokenCount: Int,
	val weightedScore: Double,
	val observedTokenCoverage: Double,
	val availableDefinitionCoverage: Double,
)

class CandidateShortlist(
	val observationId: String,
	hits: Collection<CandidateHit>,
	val indexedObservedTokenCount: Int,
	val policyExcludedSampleCount: Int,
) {
	val hits: List<CandidateHit> = Collections.unmodifiableList(ArrayList(hits))
}

/**
 * Immutable inverted index used only to narrow candidates. Its scores are not room matches and
 * must be followed by explicit bidirectional verification in Milestone 4.
 */
class RoomCandidateIndex private constructor(
	val policy: FingerprintPolicy,
	views: Collection<CandidateView>,
	unsupportedRotations: Collection<UnsupportedCandidateRotation>,
	postings: Map<StructuralToken, Set<CandidateViewKey>>,
) {
	val views: List<CandidateView> = Collections.unmodifiableList(ArrayList(views))
	val unsupportedRotations: List<UnsupportedCandidateRotation> =
		Collections.unmodifiableList(ArrayList(unsupportedRotations))

	private val viewsByKey: Map<CandidateViewKey, CandidateView> = Collections.unmodifiableMap(
		LinkedHashMap(this.views.associateBy(CandidateView::key)),
	)
	private val postings: Map<StructuralToken, Set<CandidateViewKey>> = Collections.unmodifiableMap(
		TreeMap<StructuralToken, Set<CandidateViewKey>>(tokenComparator).also { copy ->
			postings.forEach { (token, keys) ->
				copy[token] = Collections.unmodifiableSet(LinkedHashSet(keys))
			}
		},
	)

	fun shortlist(
		observation: RoomObservation,
		limit: Int = DEFAULT_LIMIT,
		minimumTokenMatches: Int = 1,
	): CandidateShortlist {
		require(limit > 0) { "Candidate shortlist limit must be positive" }
		require(minimumTokenMatches > 0) { "Minimum token matches must be positive" }
		require(observation.fingerprintPolicyVersion == policy.version) {
			"Observation policy '${observation.fingerprintPolicyVersion}' does not match index policy '${policy.version}'"
		}

		val excluded = ArrayList<StructuralSample>()
		val observedTokens = observation.samples.mapNotNull { sample ->
			when (val decision = policy.apply(sample)) {
				is PolicyDecision.Accepted -> StructuralToken(decision.sample)
				is PolicyDecision.Excluded -> {
					excluded += sample
					null
				}
			}
		}.toSet()
		val shapeCompatible = views.asSequence()
			.filter { it.footprint.localCells == observation.footprint.localCells }
			.filter { it.bounds == observation.bounds }
			.map(CandidateView::key)
			.toHashSet()
		val matchedTokens = HashMap<CandidateViewKey, MutableSet<StructuralToken>>()
		val scores = HashMap<CandidateViewKey, Double>()

		for (token in observedTokens) {
			val candidates = postings[token].orEmpty().filter(shapeCompatible::contains)
			if (candidates.isEmpty()) continue
			val weight = 1.0 / candidates.size
			for (candidate in candidates) {
				matchedTokens.getOrPut(candidate, ::LinkedHashSet).add(token)
				scores[candidate] = scores.getOrDefault(candidate, 0.0) + weight
			}
		}

		val hits = matchedTokens.mapNotNull { (key, matched) ->
			if (matched.size < minimumTokenMatches) return@mapNotNull null
			val candidate = viewsByKey.getValue(key)
			val availableCandidateCount = candidate.samples.count {
				observation.coverage.isAvailable(it.position)
			}
			CandidateHit(
				candidate = candidate,
				matchedTokenCount = matched.size,
				observedTokenCount = observedTokens.size,
				candidateTokenCount = candidate.tokens.size,
				availableCandidateTokenCount = availableCandidateCount,
				weightedScore = scores.getValue(key),
				observedTokenCoverage = ratio(matched.size, observedTokens.size),
				availableDefinitionCoverage = ratio(matched.size, availableCandidateCount),
			)
		}.sortedWith(hitComparator).take(limit)

		return CandidateShortlist(
			observationId = observation.observationId,
			hits = hits,
			indexedObservedTokenCount = observedTokens.size,
			policyExcludedSampleCount = excluded.size,
		)
	}

	companion object {
		const val DEFAULT_LIMIT = 64

		fun build(
			database: RoomDatabase,
			policies: Map<String, FingerprintPolicy> = RoomDatabase.defaultPolicies(),
		): RoomCandidateIndex {
			val policy = requireNotNull(policies[database.fingerprintPolicyVersion]) {
				"No policy '${database.fingerprintPolicyVersion}' is available for candidate indexing"
			}
			val views = ArrayList<CandidateView>()
			val unsupported = ArrayList<UnsupportedCandidateRotation>()
			val postings = LinkedHashMap<StructuralToken, MutableSet<CandidateViewKey>>()

			for (definition in database.definitions) {
				for (fingerprint in definition.fingerprints) {
					for (rotation in RoomRotation.entries) {
						val key = CandidateViewKey(definition.id, fingerprint.id, rotation)
						val rotated = StructuralNormalizer.rotate(
							fingerprint.samples,
							fingerprint.bounds,
							rotation,
							policy,
						)
						if (rotated.issues.isNotEmpty()) {
							unsupported += UnsupportedCandidateRotation(key, rotated.issues)
							continue
						}
						val view = CandidateView(
							key = key,
							definition = definition,
							fingerprint = fingerprint,
							footprint = StructuralNormalizer.rotateFootprint(definition.footprint, rotation),
							bounds = rotated.bounds,
							samples = rotated.samples,
							rotationIssues = rotated.issues,
						)
						views += view
						for (token in view.tokens) {
							postings.getOrPut(token, ::LinkedHashSet).add(key)
						}
					}
				}
			}
			return RoomCandidateIndex(policy, views, unsupported, postings)
		}
	}
}

private fun ratio(numerator: Int, denominator: Int): Double =
	if (denominator == 0) 0.0 else numerator.toDouble() / denominator

private val tokenComparator = compareBy<StructuralToken>(
	{ it.position },
	{ it.blockId },
	{ it.properties.entries.joinToString(separator = "\u0000") { entry -> "${entry.key}=${entry.value}" } },
)

private val hitComparator = compareByDescending<CandidateHit>(CandidateHit::weightedScore)
	.thenByDescending(CandidateHit::matchedTokenCount)
	.thenBy { it.candidate.key.roomId.value }
	.thenBy { it.candidate.key.fingerprintId }
	.thenBy { it.candidate.key.rotation.ordinal }
