package com.andyy.funnymap.tools

import com.andyy.funnymap.dungeon.room.RoomDatabase
import java.nio.file.Files
import java.nio.file.Path

/** Headless production database validation entry point used by local builds and CI. */
object RoomDatabaseValidatorCli {
	@JvmStatic
	fun main(arguments: Array<String>) {
		require(arguments.size == 1) { "Usage: RoomDatabaseValidatorCli <rooms.json>" }
		val source = Path.of(arguments.single()).toAbsolutePath().normalize()
		require(Files.isRegularFile(source)) { "Room database does not exist: $source" }
		val database = Files.newBufferedReader(source).use(RoomDatabase::load)
		val fingerprintCount = database.definitions.sumOf { it.fingerprints.size }

		println("ROOM_DATABASE_VALID=true")
		println("ROOM_DATABASE_SCHEMA=${database.schemaVersion}")
		println("ROOM_DATABASE_POLICY=${database.fingerprintPolicyVersion}")
		println("ROOM_DATABASE_DIGEST=${database.cacheIdentity}")
		println("ROOM_DATABASE_ROOMS=${database.definitions.size}")
		println("ROOM_DATABASE_FINGERPRINTS=$fingerprintCount")
	}
}
