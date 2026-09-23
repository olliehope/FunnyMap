package com.andyy.funnymap.dungeon.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DungeonSnapshotExchangeTest {
	@Test
	fun `accepts only increasing revisions from the active session`() {
		val exchange = DungeonSnapshotExchange()
		val session = exchange.view().session

		assertTrue(exchange.publish(session, snapshot(1)))
		assertFalse(exchange.publish(session, snapshot(1)))
		assertFalse(exchange.publish(session, snapshot(0)))
		assertTrue(exchange.publish(session, snapshot(2)))
		assertEquals(2, exchange.view().snapshot.revision)
	}

	@Test
	fun `new session invalidates outstanding producers and clears state`() {
		val exchange = DungeonSnapshotExchange()
		val oldSession = exchange.view().session
		assertTrue(exchange.publish(oldSession, snapshot(1)))

		val newSession = exchange.beginSession()

		assertEquals(DungeonSnapshot.EMPTY, exchange.view().snapshot)
		assertFalse(exchange.publish(oldSession, snapshot(5)))
		assertTrue(exchange.publish(newSession, snapshot(1)))
		assertEquals(1, exchange.view().snapshot.revision)
	}

	private fun snapshot(revision: Long): DungeonSnapshot = DungeonSnapshot(
		revision = revision,
		grid = DungeonGrid.EMPTY,
	)
}
