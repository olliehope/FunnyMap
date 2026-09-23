package com.andyy.funnymap.client.capture

import com.andyy.funnymap.build.BuildInfo

object RoomCaptureSettings {
	val enabled: Boolean by lazy {
		resolveEnabled(
			isDevelopment = BuildInfo.current.isDevelopment,
			propertyValue = System.getProperty(PROPERTY),
			environmentValue = System.getenv(ENVIRONMENT),
		)
	}

	/** Development builds default on; release builds remain off regardless of configuration. */
	fun resolveEnabled(
		isDevelopment: Boolean,
		propertyValue: String?,
		environmentValue: String?,
	): Boolean = isDevelopment && (readBoolean(propertyValue) ?: readBoolean(environmentValue) ?: true)

	private fun readBoolean(value: String?): Boolean? = when (value?.trim()?.lowercase()) {
		null, "" -> null
		"1", "true", "yes", "on" -> true
		"0", "false", "no", "off" -> false
		else -> null
	}

	private const val PROPERTY = "funnymap.devTools"
	private const val ENVIRONMENT = "FUNNYMAP_DEV_TOOLS"
}
