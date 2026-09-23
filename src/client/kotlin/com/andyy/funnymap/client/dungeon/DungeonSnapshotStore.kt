package com.andyy.funnymap.client.dungeon

import com.andyy.funnymap.dungeon.model.DungeonSnapshot
import com.andyy.funnymap.dungeon.model.DungeonSnapshotExchange
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLevelEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents

/** Publishes complete immutable snapshots to client-side consumers. */
object DungeonSnapshotStore {
	private val exchange = DungeonSnapshotExchange()
	private var initialized = false

	fun initialize() {
		check(!initialized) { "DungeonSnapshotStore is already initialized" }
		initialized = true
		ClientLevelEvents.AFTER_CLIENT_LEVEL_CHANGE.register { _, _ -> beginSession() }
		ClientPlayConnectionEvents.DISCONNECT.register { _, _ -> beginSession() }
	}

	fun snapshot(): DungeonSnapshot = exchange.view().snapshot

	fun session(): DungeonSnapshotExchange.Session = exchange.view().session

	fun view(): DungeonSnapshotExchange.View = exchange.view()

	/** Returns false for an old session or an older revision in the active session. */
	fun publish(session: DungeonSnapshotExchange.Session, snapshot: DungeonSnapshot): Boolean =
		exchange.publish(session, snapshot)

	/** Starts a new world/session and invalidates every outstanding producer token. */
	fun beginSession(): DungeonSnapshotExchange.Session = exchange.beginSession()
}
