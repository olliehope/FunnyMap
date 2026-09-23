package com.andyy.funnymap.dungeon.scan

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BoundedWorkQueueTest {
	@Test
	fun `queue deduplicates bounds and preserves FIFO order`() {
		val queue = BoundedWorkQueue<String, String>(2) { it }

		assertTrue(queue.enqueue("first"))
		assertFalse(queue.enqueue("first"))
		assertTrue(queue.enqueue("second"))
		assertFalse(queue.enqueue("third"))
		assertEquals("first", queue.poll())
		assertTrue(queue.enqueue("third"))
		assertEquals("second", queue.poll())
		assertEquals("third", queue.poll())
		assertNull(queue.poll())
	}

	@Test
	fun `predicate invalidation also clears deduplication key`() {
		val queue = BoundedWorkQueue<String, String>(4) { it }
		queue.enqueue("chunk-a")
		queue.enqueue("chunk-b")

		assertEquals(1, queue.removeIf { it.endsWith("a") })
		assertTrue(queue.enqueue("chunk-a"))
	}
}
