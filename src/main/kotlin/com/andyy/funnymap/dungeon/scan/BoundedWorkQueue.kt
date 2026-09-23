package com.andyy.funnymap.dungeon.scan

import java.util.ArrayDeque

/** FIFO queue with stable de-duplication and a hard memory bound. */
class BoundedWorkQueue<T, K>(
	private val maximumSize: Int,
	private val keyOf: (T) -> K,
) {
	private val queue = ArrayDeque<T>()
	private val keys = LinkedHashSet<K>()

	init {
		require(maximumSize > 0) { "Maximum work queue size must be positive" }
	}

	val size: Int
		get() = queue.size

	fun enqueue(value: T): Boolean {
		val key = keyOf(value)
		if (key in keys || queue.size >= maximumSize) return false
		keys += key
		queue += value
		return true
	}

	fun poll(): T? {
		val value = queue.pollFirst() ?: return null
		keys.remove(keyOf(value))
		return value
	}

	fun removeIf(predicate: (T) -> Boolean): Int {
		var removed = 0
		val iterator = queue.iterator()
		while (iterator.hasNext()) {
			val value = iterator.next()
			if (predicate(value)) {
				iterator.remove()
				keys.remove(keyOf(value))
				removed++
			}
		}
		return removed
	}

	fun clear() {
		queue.clear()
		keys.clear()
	}
}
