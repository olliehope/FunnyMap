package com.andyy.funnymap.tools

import com.andyy.funnymap.build.BuildInfo
import com.andyy.funnymap.build.BuildMode
import com.andyy.funnymap.dungeon.room.RoomDatabase
import com.google.gson.JsonParser
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile

/** Verifies that a distributable jar contains only reviewed production resources. */
object ReleaseArtifactVerifierCli {
	@JvmStatic
	fun main(arguments: Array<String>) {
		require(arguments.size == 2) { "Usage: ReleaseArtifactVerifierCli <jar> <development|release>" }
		val jar = Path.of(arguments[0]).toAbsolutePath().normalize()
		val expectedMode = arguments[1]
		require(Files.isRegularFile(jar)) { "Built jar does not exist: $jar" }

		ZipFile(jar.toFile()).use { zip ->
			val names = zip.entries().asSequence().map { it.name }.toList()
			val forbidden = names.filter(::isForbiddenPackagedPath)
			check(forbidden.isEmpty()) {
				"Release jar contains forbidden development/capture resources: ${forbidden.joinToString()}"
			}
			check(names.any { it == "LICENSE_funnymap" }) { "Release jar does not contain LICENSE_funnymap" }

			val databaseEntry = requireNotNull(zip.getEntry(ROOM_DATABASE_PATH)) {
				"Release jar does not contain $ROOM_DATABASE_PATH"
			}
			val database = zip.getInputStream(databaseEntry).bufferedReader().use(RoomDatabase::load)
			val syntheticIds = database.definitions.map { it.id.value }.filter(::isSyntheticRoomId)
			check(syntheticIds.isEmpty()) {
				"Production database contains synthetic/test room ids: ${syntheticIds.joinToString()}"
			}

			val buildEntry = requireNotNull(zip.getEntry(BuildInfo.RESOURCE_PATH.removePrefix("/"))) {
				"Release jar does not contain generated build metadata"
			}
			val buildInfo = zip.getInputStream(buildEntry).use(BuildInfo::parse)
			check(buildInfo.mode == expectedMode.parseMode()) {
				"Jar build mode ${buildInfo.mode} does not match expected mode '$expectedMode'"
			}
			if (buildInfo.mode == BuildMode.RELEASE) {
				check(buildInfo.version == buildInfo.baseVersion) {
					"Release version must equal its base version"
				}
			} else {
				check("-dev+" in buildInfo.version) { "Development version must contain '-dev+'" }
			}

			val modEntry = requireNotNull(zip.getEntry("fabric.mod.json")) { "Release jar lacks fabric.mod.json" }
			val modVersion = zip.getInputStream(modEntry).bufferedReader().use { reader ->
				JsonParser.parseReader(reader).asJsonObject.get("version").asString
			}
			check(modVersion == buildInfo.version) {
				"fabric.mod.json version '$modVersion' differs from build metadata '${buildInfo.version}'"
			}

			println("RELEASE_RESOURCES_VALID=true")
			println("ARTIFACT=${jar.fileName}")
			println("BUILD_VERSION=${buildInfo.version}")
			println("BUILD_MODE=${buildInfo.mode.name.lowercase()}")
			println("BUILD_COMMIT=${buildInfo.commit}")
			println("MINECRAFT_VERSION=${buildInfo.minecraftVersion}")
			println("ROOM_DATABASE_DIGEST=${database.cacheIdentity}")
			println("ROOM_DATABASE_ROOMS=${database.definitions.size}")
		}
	}

	internal fun isForbiddenPackagedPath(path: String): Boolean {
		val normalized = path.replace('\\', '/').lowercase().trim('/')
		val segments = normalized.split('/')
		return "funnymap-room-captures" in normalized ||
			segments.any(FORBIDDEN_PATH_SEGMENTS::contains) ||
			FORBIDDEN_FILE_SUFFIXES.any(normalized::endsWith)
	}

	internal fun isSyntheticRoomId(id: String): Boolean = id.split('/').any { segment ->
		SYNTHETIC_ID_MARKERS.any { marker -> segment == marker || segment.startsWith("$marker-") }
	}

	private fun String.parseMode(): BuildMode = when (lowercase()) {
		"development" -> BuildMode.DEVELOPMENT
		"release" -> BuildMode.RELEASE
		else -> error("Unsupported expected build mode '$this'")
	}

	private const val ROOM_DATABASE_PATH = "assets/funnymap/rooms.json"
	private val FORBIDDEN_PATH_SEGMENTS = setOf(
		"raw",
		"raw-captures",
		"capture-artifacts",
		"reports",
		"exports",
		"fixtures",
		"test-fixtures",
	)
	private val FORBIDDEN_FILE_SUFFIXES = setOf(
		".room-capture.json",
		".dev.json",
	)
	private val SYNTHETIC_ID_MARKERS = setOf("synthetic", "fixture", "fixtures", "test", "example")
}
