package com.andyy.funnymap.client.detection

import com.andyy.funnymap.detection.DungeonContext
import com.andyy.funnymap.detection.DungeonDetector
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.Minecraft

object DungeonDetectionService {
	@Volatile
	var context: DungeonContext = DungeonContext.UNKNOWN
		private set

	private val detector = DungeonDetector()
	private var ticksUntilRefresh = 0

	fun initialize() {
		ClientTickEvents.END_CLIENT_TICK.register(::onEndTick)
	}

	private fun onEndTick(client: Minecraft) {
		if (client.level == null || ticksUntilRefresh-- <= 0) {
			context = detector.detect(MinecraftDungeonSignals.read(client))
			ticksUntilRefresh = REFRESH_INTERVAL_TICKS - 1
		}
	}

	private const val REFRESH_INTERVAL_TICKS = 20
}
