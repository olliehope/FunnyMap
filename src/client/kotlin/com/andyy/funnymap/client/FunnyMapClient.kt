package com.andyy.funnymap.client

import com.andyy.funnymap.FunnyMap
import com.andyy.funnymap.client.capture.RoomCaptureSettings
import com.andyy.funnymap.client.capture.RoomCaptureTool
import com.andyy.funnymap.client.detection.DungeonDetectionService
import com.andyy.funnymap.client.dungeon.RoomDataService
import com.andyy.funnymap.client.dungeon.DungeonSnapshotStore
import com.andyy.funnymap.client.dungeon.RoomScannerService
import com.andyy.funnymap.client.hud.BasicDungeonHud
import com.andyy.funnymap.client.hud.DungeonMapHud
import com.andyy.funnymap.client.hud.DevBuildHud
import net.fabricmc.api.ClientModInitializer

object FunnyMapClient : ClientModInitializer {
	override fun onInitializeClient() {
		DungeonSnapshotStore.initialize()
		RoomDataService.initialize()
		DungeonDetectionService.initialize()
		RoomScannerService.initialize()
		BasicDungeonHud.initialize()
		DungeonMapHud.initialize()
		DevBuildHud.initialize()
		if (RoomCaptureSettings.enabled) {
			RoomCaptureTool.initialize()
		}
		FunnyMap.LOGGER.info(
			"FunnyMap Milestone 4 client services initialized (developer capture tools: {})",
			if (RoomCaptureSettings.enabled) "enabled" else "disabled",
		)
	}
}
