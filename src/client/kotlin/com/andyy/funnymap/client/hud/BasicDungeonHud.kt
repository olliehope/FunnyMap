package com.andyy.funnymap.client.hud

import com.andyy.funnymap.FunnyMap
import com.andyy.funnymap.client.detection.DungeonDetectionService
import com.andyy.funnymap.detection.CatacombsFloor
import com.andyy.funnymap.detection.DetectionStatus
import com.andyy.funnymap.detection.DungeonContext
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements
import net.minecraft.client.DeltaTracker
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor

object BasicDungeonHud {
	fun initialize() {
		HudElementRegistry.attachElementBefore(
			VanillaHudElements.CHAT,
			FunnyMap.id("dungeon_status"),
			::render,
		)
	}

	@Suppress("UNUSED_PARAMETER")
	private fun render(graphics: GuiGraphicsExtractor, deltaTracker: DeltaTracker) {
		val context = DungeonDetectionService.context
		if (context.hypixel != DetectionStatus.DETECTED) return
		if (context.catacombs == DetectionStatus.DETECTED) return

		val client = Minecraft.getInstance()
		if (client.player == null || client.options.hideGui) return

		val status = statusText(context)
		val accent = accentColor(context)
		val width = maxOf(PANEL_MIN_WIDTH, client.font.width(status) + PANEL_HORIZONTAL_PADDING)

		graphics.fill(PANEL_X, PANEL_Y, PANEL_X + width, PANEL_Y + PANEL_HEIGHT, BACKGROUND_COLOR)
		graphics.fill(PANEL_X, PANEL_Y, PANEL_X + width, PANEL_Y + 1, BORDER_COLOR)
		graphics.fill(PANEL_X, PANEL_Y, PANEL_X + 3, PANEL_Y + PANEL_HEIGHT, accent)
		graphics.text(client.font, "FunnyMap", PANEL_X + 9, PANEL_Y + 6, TITLE_COLOR, false)
		graphics.text(client.font, status, PANEL_X + 9, PANEL_Y + 19, TEXT_COLOR, false)
	}

	private fun statusText(context: DungeonContext): String = when {
		context.catacombs == DetectionStatus.DETECTED && context.floor != CatacombsFloor.UNKNOWN -> {
			"Catacombs ${context.floor.token}"
		}
		context.catacombs == DetectionStatus.DETECTED -> "Catacombs - floor Unknown"
		context.skyBlock == DetectionStatus.DETECTED -> "SkyBlock - no dungeon"
		context.skyBlock == DetectionStatus.NOT_DETECTED -> "Hypixel - no SkyBlock"
		else -> "Hypixel - mode Unknown"
	}

	private fun accentColor(context: DungeonContext): Int = when {
		context.catacombs == DetectionStatus.DETECTED -> 0xFF48D6C7.toInt()
		context.skyBlock == DetectionStatus.DETECTED -> 0xFFF2C14E.toInt()
		else -> 0xFF8A939B.toInt()
	}

	private const val PANEL_X = 8
	private const val PANEL_Y = 8
	private const val PANEL_HEIGHT = 34
	private const val PANEL_MIN_WIDTH = 132
	private const val PANEL_HORIZONTAL_PADDING = 18
	private const val BACKGROUND_COLOR = 0xD914181B.toInt()
	private const val BORDER_COLOR = 0xFF3A4248.toInt()
	private const val TITLE_COLOR = 0xFFF4F7F8.toInt()
	private const val TEXT_COLOR = 0xFFD3D9DC.toInt()
}
