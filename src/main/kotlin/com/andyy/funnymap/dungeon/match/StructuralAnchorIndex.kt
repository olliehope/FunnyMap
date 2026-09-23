package com.andyy.funnymap.dungeon.match

import com.andyy.funnymap.dungeon.room.LocalBlockPosition
import java.util.Collections

data class StructuralAnchorReference(
	val candidate: CandidateView,
	val localPosition: LocalBlockPosition,
	val blockId: String,
	val requiredProperties: Map<String, String>,
	val signatureFrequency: Int,
)

/** Position-independent lookup used to propose world origins from loaded structural blocks. */
class StructuralAnchorIndex private constructor(
	private val byBlockId: Map<String, List<StructuralAnchorReference>>,
) {
	val blockIds: Set<String> = Collections.unmodifiableSet(byBlockId.keys.toSortedSet())

	fun containsBlock(blockId: String): Boolean = blockId in byBlockId

	fun referencesFor(
		blockId: String,
		observedProperties: Map<String, String>,
		limit: Int,
	): List<StructuralAnchorReference> {
		require(limit > 0) { "Structural anchor lookup limit must be positive" }
		return byBlockId[blockId].orEmpty().asSequence()
			.filter { reference ->
				reference.requiredProperties.all { (name, value) -> observedProperties[name] == value }
			}
			.sortedWith(anchorComparator)
			.take(limit)
			.toList()
	}

	companion object {
		fun build(
			candidateIndex: RoomCandidateIndex,
			maximumSignatureReferences: Int,
		): StructuralAnchorIndex {
			require(maximumSignatureReferences > 0) { "Maximum signature references must be positive" }
			val samples = candidateIndex.views.flatMap { view ->
				view.samples.map { sample -> PendingAnchor(view, sample.position, sample.blockId, sample.properties) }
			}
			val frequencies = samples.groupingBy { AnchorSignature(it.blockId, it.properties) }.eachCount()
			val grouped = samples.asSequence()
				.mapNotNull { pending ->
					val frequency = frequencies.getValue(AnchorSignature(pending.blockId, pending.properties))
					if (frequency > maximumSignatureReferences) null else StructuralAnchorReference(
						candidate = pending.candidate,
						localPosition = pending.localPosition,
						blockId = pending.blockId,
						requiredProperties = Collections.unmodifiableMap(pending.properties.toSortedMap()),
						signatureFrequency = frequency,
					)
				}
				.groupBy(StructuralAnchorReference::blockId)
				.mapValues { (_, references) -> Collections.unmodifiableList(references.sortedWith(anchorComparator)) }
			return StructuralAnchorIndex(Collections.unmodifiableMap(grouped.toSortedMap()))
		}
	}

	private data class PendingAnchor(
		val candidate: CandidateView,
		val localPosition: LocalBlockPosition,
		val blockId: String,
		val properties: Map<String, String>,
	)

	private data class AnchorSignature(
		val blockId: String,
		val properties: Map<String, String>,
	)
}

private val anchorComparator = compareBy<StructuralAnchorReference>(
	StructuralAnchorReference::signatureFrequency,
	{ it.candidate.key.roomId.value },
	{ it.candidate.key.fingerprintId },
	{ it.candidate.key.rotation.ordinal },
	StructuralAnchorReference::localPosition,
)
