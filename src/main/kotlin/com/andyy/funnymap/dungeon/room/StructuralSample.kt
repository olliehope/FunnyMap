package com.andyy.funnymap.dungeon.room

import java.util.Collections
import java.util.TreeMap

class StructuralSample(
	val position: LocalBlockPosition,
	val blockId: String,
	properties: Map<String, String> = emptyMap(),
) {
	val properties: Map<String, String> =
		Collections.unmodifiableMap(TreeMap(properties))

	init {
		require(Identifiers.isBlockId(blockId)) { "Invalid block registry identifier: $blockId" }
		this.properties.forEach { (name, value) ->
			require(Identifiers.isPropertyName(name)) { "Invalid block property name: $name" }
			require(Identifiers.isPropertyValue(value)) { "Invalid value '$value' for block property '$name'" }
		}
	}

	fun withProperties(properties: Map<String, String>): StructuralSample =
		StructuralSample(position, blockId, properties)

	override fun equals(other: Any?): Boolean =
		other is StructuralSample &&
			position == other.position &&
			blockId == other.blockId &&
			properties == other.properties

	override fun hashCode(): Int = 31 * (31 * position.hashCode() + blockId.hashCode()) + properties.hashCode()

	override fun toString(): String = "StructuralSample(position=$position, blockId=$blockId, properties=$properties)"
}

object Identifiers {
	private val resourceLocation = Regex("^[a-z0-9_.-]+:[a-z0-9/._-]+$")
	private val propertyName = Regex("^[a-z0-9_]+$")
	private val propertyValue = Regex("^[a-z0-9_.-]+$")
	private val stableId = Regex("^[a-z0-9][a-z0-9._-]*(?:/[a-z0-9][a-z0-9._-]*)+$")
	private val variantId = Regex("^[a-z0-9][a-z0-9._-]{0,63}$")
	private val sha256 = Regex("^sha256:[0-9a-f]{64}$")

	fun isBlockId(value: String): Boolean = resourceLocation.matches(value)
	fun isPropertyName(value: String): Boolean = propertyName.matches(value)
	fun isPropertyValue(value: String): Boolean = propertyValue.matches(value)
	fun isStableRoomId(value: String): Boolean = stableId.matches(value)
	fun isFingerprintId(value: String): Boolean = variantId.matches(value)
	fun isSha256(value: String): Boolean = sha256.matches(value)
}
