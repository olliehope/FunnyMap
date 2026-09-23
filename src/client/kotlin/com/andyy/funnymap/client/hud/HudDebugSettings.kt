package com.andyy.funnymap.client.hud

internal object HudDebugSettings {
	val enabled: Boolean = sequenceOf(
		System.getProperty(DEBUG_PROPERTY),
		System.getenv(DEBUG_ENVIRONMENT_VARIABLE),
	)
		.filterNotNull()
		.map(String::trim)
		.any(::isEnabledValue)

	private fun isEnabledValue(value: String): Boolean = value.equals("true", ignoreCase = true) ||
		value.equals("yes", ignoreCase = true) ||
		value.equals("on", ignoreCase = true) ||
		value == "1"

	private const val DEBUG_PROPERTY = "funnymap.debugHud"
	private const val DEBUG_ENVIRONMENT_VARIABLE = "FUNNYMAP_DEBUG_HUD"
}
