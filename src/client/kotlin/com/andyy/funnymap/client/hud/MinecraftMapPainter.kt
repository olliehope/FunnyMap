package com.andyy.funnymap.client.hud

import com.andyy.funnymap.dungeon.render.MapRenderCommand
import com.andyy.funnymap.dungeon.render.MapRenderPlan
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor

/** Minecraft-only adapter for an already-computed, world-independent render plan. */
object MinecraftMapPainter {
	fun paint(graphics: GuiGraphicsExtractor, font: Font, plan: MapRenderPlan) {
		for (command in plan.commands) {
			when (command) {
				is MapRenderCommand.FillRect -> graphics.fill(
					command.x,
					command.y,
					command.x + command.width,
					command.y + command.height,
					command.color,
				)
				is MapRenderCommand.OutlineRect -> graphics.outline(
					command.x,
					command.y,
					command.width,
					command.height,
					command.color,
				)
				is MapRenderCommand.Text -> graphics.text(
					font,
					command.value,
					command.x,
					command.y,
					command.color,
					false,
				)
			}
		}
	}
}
