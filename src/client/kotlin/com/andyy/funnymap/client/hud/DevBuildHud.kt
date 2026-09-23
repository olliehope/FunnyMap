package com.andyy.funnymap.client.hud

import com.andyy.funnymap.FunnyMap
import com.andyy.funnymap.build.BuildInfo
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements
import net.minecraft.client.DeltaTracker
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor

/** Small persistent marker that prevents development jars being mistaken for releases. */
object DevBuildHud {
	fun initialize() {
		if (!BuildInfo.current.isDevelopment) return
		HudElementRegistry.attachElementBefore(
			VanillaHudElements.CHAT,
			FunnyMap.id("dev_build_indicator"),
			::render,
		)
	}

	@Suppress("UNUSED_PARAMETER")
	private fun render(graphics: GuiGraphicsExtractor, deltaTracker: DeltaTracker) {
		val client = Minecraft.getInstance()
		val width = client.font.width(LABEL) + HORIZONTAL_PADDING * 2
		val x = graphics.guiWidth() - width - MARGIN
		graphics.fill(x, MARGIN, x + width, MARGIN + HEIGHT, BACKGROUND)
		graphics.text(client.font, LABEL, x + HORIZONTAL_PADDING, MARGIN + 2, TEXT, false)
	}

	private const val LABEL = "DEV"
	private const val MARGIN = 5
	private const val HEIGHT = 12
	private const val HORIZONTAL_PADDING = 4
	private const val BACKGROUND = 0xD92B3034.toInt()
	private const val TEXT = 0xFFFFD166.toInt()
}
