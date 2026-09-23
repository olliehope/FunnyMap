package com.andyy.funnymap.dungeon.model

import java.util.concurrent.atomic.AtomicReference

/** Atomically exchanges whole immutable snapshots and rejects stale producer results. */
class DungeonSnapshotExchange {
	@JvmInline
	value class Session internal constructor(val generation: Long)

	data class View(
		val session: Session,
		val snapshot: DungeonSnapshot,
	)

	private val current = AtomicReference(
		View(session = Session(0), snapshot = DungeonSnapshot.EMPTY),
	)

	fun view(): View = current.get()

	/** A producer must advance the revision within the session captured before its work began. */
	fun publish(session: Session, snapshot: DungeonSnapshot): Boolean {
		while (true) {
			val previous = current.get()
			if (session != previous.session) return false
			if (snapshot.revision <= previous.snapshot.revision) return false
			if (current.compareAndSet(previous, View(session, snapshot))) return true
		}
	}

	/** Invalidates every outstanding producer token and resets the visible snapshot. */
	fun beginSession(): Session {
		while (true) {
			val previous = current.get()
			val next = View(
				session = Session(previous.session.generation + 1),
				snapshot = DungeonSnapshot.EMPTY,
			)
			if (current.compareAndSet(previous, next)) return next.session
		}
	}
}
