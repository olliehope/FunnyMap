package com.andyy.funnymap.client.dungeon

import com.andyy.funnymap.FunnyMap
import com.andyy.funnymap.dungeon.room.RoomDatabase

/** Owns the immutable bundled room database and its load-time candidate index. */
object RoomDataService {
	lateinit var database: RoomDatabase
		private set

	fun initialize() {
		check(!::database.isInitialized) { "RoomDataService is already initialized" }
		database = RoomDatabase.loadResource()
		FunnyMap.LOGGER.info(
			"Loaded {} room definitions and {} rotated candidate views",
			database.definitions.size,
			database.candidateIndex.views.size,
		)
	}
}
