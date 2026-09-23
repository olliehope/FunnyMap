package com.andyy.funnymap.dungeon.room

/** Strictly combines reviewed development definitions with the bundled database. */
object RoomDatabaseOverlay {
	fun merge(bundled: RoomDatabase, overlay: RoomDatabase): RoomDatabase {
		require(overlay.schemaVersion == bundled.schemaVersion) {
			"Overlay schema ${overlay.schemaVersion} does not match bundled schema ${bundled.schemaVersion}"
		}
		require(overlay.fingerprintPolicyVersion == bundled.fingerprintPolicyVersion) {
			"Overlay policy '${overlay.fingerprintPolicyVersion}' does not match bundled policy " +
				"'${bundled.fingerprintPolicyVersion}'"
		}
		return RoomDatabase.create(
			schemaVersion = bundled.schemaVersion,
			fingerprintPolicyVersion = bundled.fingerprintPolicyVersion,
			definitions = bundled.definitions + overlay.definitions,
		)
	}
}
