package com.andyy.funnymap.dungeon.room

import java.util.Collections
import java.util.TreeMap
import java.util.TreeSet

enum class DirectionalPropertyKind {
	CARDINAL_FACING,
	HORIZONTAL_AXIS,
	ROTATION_16,
	UNSUPPORTED,
}

enum class PolicyExclusionReason {
	BLOCK_NAMESPACE_NOT_ALLOWED,
	BLOCK_NOT_ELIGIBLE,
	RUNTIME_VARIABLE_BLOCK,
}

sealed interface PolicyDecision {
	data class Accepted(
		val sample: StructuralSample,
		val droppedProperties: Set<String>,
	) : PolicyDecision

	data class Excluded(
		val sample: StructuralSample,
		val reason: PolicyExclusionReason,
	) : PolicyDecision
}

class FingerprintPolicy(
	val version: String,
	allowedNamespaces: Set<String>,
	eligibleBlockIds: Set<String> = emptySet(),
	excludedBlockIds: Set<String> = emptySet(),
	participatingProperties: Set<String> = emptySet(),
	propertiesByBlock: Map<String, Set<String>> = emptyMap(),
	directionalProperties: Map<String, DirectionalPropertyKind> = emptyMap(),
	val minimumSamples: Int,
) {
	val allowedNamespaces: Set<String> = immutableSortedSet(allowedNamespaces)
	val eligibleBlockIds: Set<String> = immutableSortedSet(eligibleBlockIds)
	val excludedBlockIds: Set<String> = immutableSortedSet(excludedBlockIds)
	val participatingProperties: Set<String> = immutableSortedSet(participatingProperties)
	val propertiesByBlock: Map<String, Set<String>> = immutableNestedSetMap(propertiesByBlock)
	val directionalProperties: Map<String, DirectionalPropertyKind> =
		Collections.unmodifiableMap(TreeMap(directionalProperties))

	init {
		require(Identifiers.isFingerprintId(version)) { "Invalid fingerprint policy version: $version" }
		require(this.allowedNamespaces.isNotEmpty()) { "At least one block namespace must be allowed" }
		require(minimumSamples > 0) { "Minimum sample count must be positive" }
		this.allowedNamespaces.forEach {
			require(Regex("^[a-z0-9_.-]+$").matches(it)) { "Invalid namespace in policy: $it" }
		}
		(this.eligibleBlockIds + this.excludedBlockIds + this.propertiesByBlock.keys).forEach {
			require(Identifiers.isBlockId(it)) { "Invalid block id in policy: $it" }
		}
		(this.participatingProperties + this.propertiesByBlock.values.flatten() + this.directionalProperties.keys).forEach {
			require(Identifiers.isPropertyName(it)) { "Invalid property in policy: $it" }
		}
	}

	fun apply(sample: StructuralSample): PolicyDecision {
		val namespace = sample.blockId.substringBefore(':')
		if (namespace !in allowedNamespaces) {
			return PolicyDecision.Excluded(sample, PolicyExclusionReason.BLOCK_NAMESPACE_NOT_ALLOWED)
		}
		if (eligibleBlockIds.isNotEmpty() && sample.blockId !in eligibleBlockIds) {
			return PolicyDecision.Excluded(sample, PolicyExclusionReason.BLOCK_NOT_ELIGIBLE)
		}
		if (sample.blockId in excludedBlockIds) {
			return PolicyDecision.Excluded(sample, PolicyExclusionReason.RUNTIME_VARIABLE_BLOCK)
		}

		val allowedProperties = participatingProperties + propertiesByBlock.getOrDefault(sample.blockId, emptySet())
		val filtered = sample.properties.filterKeys(allowedProperties::contains)
		return PolicyDecision.Accepted(
			sample = sample.withProperties(filtered),
			droppedProperties = immutableSortedSet(sample.properties.keys - filtered.keys),
		)
	}

	companion object {
		val DEFAULT = FingerprintPolicy(
			version = "structural-blockstates-v1",
			allowedNamespaces = setOf("minecraft"),
			excludedBlockIds = setOf(
				"minecraft:air",
				"minecraft:cave_air",
				"minecraft:void_air",
				"minecraft:water",
				"minecraft:lava",
				"minecraft:fire",
				"minecraft:soul_fire",
				"minecraft:light",
				"minecraft:chest",
				"minecraft:trapped_chest",
				"minecraft:redstone_wire",
				"minecraft:lever",
			),
			participatingProperties = setOf(
				"axis",
				"facing",
				"half",
				"hinge",
				"horizontal_facing",
				"shape",
				"type",
				"rotation",
			),
			directionalProperties = mapOf(
				"facing" to DirectionalPropertyKind.CARDINAL_FACING,
				"horizontal_facing" to DirectionalPropertyKind.CARDINAL_FACING,
				"axis" to DirectionalPropertyKind.HORIZONTAL_AXIS,
				"rotation" to DirectionalPropertyKind.ROTATION_16,
			),
			minimumSamples = 8,
		)
	}
}

private fun immutableSortedSet(values: Collection<String>): Set<String> =
	Collections.unmodifiableSet(TreeSet(values))

private fun immutableNestedSetMap(values: Map<String, Set<String>>): Map<String, Set<String>> {
	val copy = TreeMap<String, Set<String>>()
	values.forEach { (key, properties) -> copy[key] = immutableSortedSet(properties) }
	return Collections.unmodifiableMap(copy)
}
