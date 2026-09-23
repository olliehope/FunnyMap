package com.andyy.funnymap.client.capture

object RoomCaptureSettings {
	val enabled: Boolean by lazy {
		readBoolean(System.getProperty(PROPERTY)) ?: readBoolean(System.getenv(ENVIRONMENT)) ?: false
	}

	private fun readBoolean(value: String?): Boolean? = when (value?.trim()?.lowercase()) {
		null, "" -> null
		"1", "true", "yes", "on" -> true
		"0", "false", "no", "off" -> false
		else -> null
	}

	private const val PROPERTY = "funnymap.devTools"
	private const val ENVIRONMENT = "FUNNYMAP_DEV_TOOLS"
}
