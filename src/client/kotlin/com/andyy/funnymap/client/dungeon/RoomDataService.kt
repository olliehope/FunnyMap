package com.andyy.funnymap.client.dungeon

import com.andyy.funnymap.FunnyMap
import com.andyy.funnymap.build.BuildInfo
import com.andyy.funnymap.client.capture.RoomCaptureSettings
import com.andyy.funnymap.dungeon.room.RoomDatabase
import com.andyy.funnymap.dungeon.room.RoomDatabaseOverlay
import net.minecraft.client.Minecraft
import org.slf4j.LoggerFactory
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

data class RoomDataStatus(
	val databaseIdentity: String,
	val policyVersion: String,
	val bundledRoomCount: Int,
	val overlayRoomCount: Int,
	val totalRoomCount: Int,
	val fingerprintCount: Int,
	val overlayPath: Path,
	val overlayLoaded: Boolean,
	val lastReloadError: String?,
)

/** Owns the immutable bundled room database and its load-time candidate index. */
object RoomDataService {
	lateinit var database: RoomDatabase
		private set
	private lateinit var bundledDatabase: RoomDatabase
	private lateinit var developmentOverlayPath: Path
	private var overlayRoomCount = 0
	private var overlayLoaded = false
	private var lastReloadError: String? = null

	fun initialize() {
		check(!::database.isInitialized) { "RoomDataService is already initialized" }
		bundledDatabase = RoomDatabase.loadResource()
		database = bundledDatabase
		developmentOverlayPath = Minecraft.getInstance().gameDirectory.toPath()
			.resolve(OUTPUT_DIRECTORY)
			.resolve(DEVELOPMENT_OVERLAY_FILE)
		if (developmentOverlayEnabled && Files.isRegularFile(developmentOverlayPath)) {
			try {
				applyDevelopmentOverlay()
			} catch (error: Exception) {
				lastReloadError = error.message ?: error.javaClass.simpleName
				SCANNER_LOGGER.error(
					"event=database_overlay_rejected path={} reason={}",
					developmentOverlayPath,
					lastReloadError,
					error,
				)
			}
		}
		logDatabase("database_load")
	}

	/** Transactionally reloads the optional local database; a rejected file leaves the active database unchanged. */
	fun reloadDevelopmentOverlay(): RoomDataStatus {
		check(BuildInfo.current.isDevelopment) { "The local room overlay is disabled in release builds" }
		check(RoomCaptureSettings.enabled) { "Developer tools must be enabled to reload the local room overlay" }
		val previousDatabase = database
		val previousOverlayCount = overlayRoomCount
		val previousOverlayLoaded = overlayLoaded
		return try {
			bundledDatabase = RoomDatabase.loadResource()
			if (Files.isRegularFile(developmentOverlayPath)) applyDevelopmentOverlay()
			else {
				database = bundledDatabase
				overlayRoomCount = 0
				overlayLoaded = false
			}
			lastReloadError = null
			logDatabase("database_reload")
			status()
		} catch (error: Exception) {
			database = previousDatabase
			overlayRoomCount = previousOverlayCount
			overlayLoaded = previousOverlayLoaded
			lastReloadError = error.message ?: error.javaClass.simpleName
			SCANNER_LOGGER.warn(
				"event=database_reload_rejected path={} reason={}",
				developmentOverlayPath,
				lastReloadError,
				error,
			)
			throw error
		}
	}

	fun status(): RoomDataStatus = RoomDataStatus(
		databaseIdentity = database.cacheIdentity,
		policyVersion = database.fingerprintPolicyVersion,
		bundledRoomCount = bundledDatabase.definitions.size,
		overlayRoomCount = overlayRoomCount,
		totalRoomCount = database.definitions.size,
		fingerprintCount = database.definitions.sumOf { it.fingerprints.size },
		overlayPath = developmentOverlayPath,
		overlayLoaded = overlayLoaded,
		lastReloadError = lastReloadError,
	)

	private fun applyDevelopmentOverlay() {
		val overlay = Files.newBufferedReader(developmentOverlayPath, StandardCharsets.UTF_8).use(RoomDatabase::load)
		val merged = RoomDatabaseOverlay.merge(bundledDatabase, overlay)
		database = merged
		overlayRoomCount = overlay.definitions.size
		overlayLoaded = true
	}

	private fun logDatabase(event: String) {
		SCANNER_LOGGER.info(
			"event={} identity={} policy={} bundledRooms={} overlayRooms={} totalRooms={} fingerprints={} views={}",
			event,
			database.cacheIdentity,
			database.fingerprintPolicyVersion,
			bundledDatabase.definitions.size,
			overlayRoomCount,
			database.definitions.size,
			database.definitions.sumOf { it.fingerprints.size },
			database.candidateIndex.views.size,
		)
	}

	private val developmentOverlayEnabled: Boolean
		get() = BuildInfo.current.isDevelopment && RoomCaptureSettings.enabled

	private const val OUTPUT_DIRECTORY = "funnymap-room-captures"
	private const val DEVELOPMENT_OVERLAY_FILE = "dev-rooms.json"
	private val SCANNER_LOGGER = LoggerFactory.getLogger("${FunnyMap.MOD_ID}/scanner")
}
