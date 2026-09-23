package com.andyy.funnymap.build

import java.io.InputStream
import java.util.Properties

enum class BuildMode {
	DEVELOPMENT,
	RELEASE,
}

data class FunnyMapBuildInfo(
	val version: String,
	val baseVersion: String,
	val commit: String,
	val mode: BuildMode,
	val minecraftVersion: String,
) {
	val isDevelopment: Boolean
		get() = mode == BuildMode.DEVELOPMENT
}

/** Build identity generated from the authoritative Gradle version during resource processing. */
object BuildInfo {
	const val RESOURCE_PATH = "/assets/funnymap/build-info.properties"

	val current: FunnyMapBuildInfo by lazy {
		val stream = BuildInfo::class.java.getResourceAsStream(RESOURCE_PATH)
			?: error("Missing FunnyMap build metadata resource: $RESOURCE_PATH")
		stream.use(::parse)
	}

	fun parse(input: InputStream): FunnyMapBuildInfo {
		val properties = Properties().apply { load(input) }
		return FunnyMapBuildInfo(
			version = properties.required("version"),
			baseVersion = properties.required("baseVersion"),
			commit = properties.required("commit"),
			mode = when (properties.required("mode")) {
				"development" -> BuildMode.DEVELOPMENT
				"release" -> BuildMode.RELEASE
				else -> error("Unsupported FunnyMap build mode")
			},
			minecraftVersion = properties.required("minecraftVersion"),
		)
	}
}

private fun Properties.required(name: String): String =
	requireNotNull(getProperty(name)?.trim()?.takeIf(String::isNotEmpty)) {
		"Missing FunnyMap build metadata property '$name'"
	}
