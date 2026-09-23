package com.andyy.funnymap.client.capture

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RoomCaptureSettingsTest {
	@Test
	fun `development builds expose commands by default`() {
		assertTrue(RoomCaptureSettings.resolveEnabled(true, null, null))
	}

	@Test
	fun `development commands can be explicitly disabled`() {
		assertFalse(RoomCaptureSettings.resolveEnabled(true, "false", "true"))
		assertFalse(RoomCaptureSettings.resolveEnabled(true, null, "off"))
	}

	@Test
	fun `release builds cannot expose developer commands`() {
		assertFalse(RoomCaptureSettings.resolveEnabled(false, "true", "true"))
	}
}
