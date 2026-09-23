package com.andyy.funnymap.client.hud

import com.andyy.funnymap.FunnyMap
import com.andyy.funnymap.client.detection.DungeonDetectionService
import com.andyy.funnymap.client.dungeon.DungeonSnapshotStore
import com.andyy.funnymap.detection.DetectionStatus
import com.andyy.funnymap.dungeon.render.DungeonMapLayoutEngine
import com.andyy.funnymap.dungeon.render.MapLayoutSpec
import com.andyy.funnymap.dungeon.render.MapRenderPlan
import com.andyy.funnymap.dungeon.render.TextMetrics
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements
import net.minecraft.client.DeltaTracker
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor

object DungeonMapHud {
	private var cachedPlan: CachedPlan? = null

	fun initialize() {
		HudElementRegistry.attachElementBefore(
			VanillaHudElements.CHAT,
			FunnyMap.id("dungeon_map"),
			::render,
		)
	}

	@Suppress("UNUSED_PARAMETER")
	private fun render(graphics: GuiGraphicsExtractor, deltaTracker: DeltaTracker) {
		if (DungeonDetectionService.context.catacombs != DetectionStatus.DETECTED) return

		val client = Minecraft.getInstance()
		val view = DungeonSnapshotStore.view()
		val key = CacheKey(
			view.session.generation,
			view.snapshot.revision,
			System.identityHashCode(client.font),
			DEBUG_ENABLED,
		)
		val plan = cachedPlan?.takeIf { it.key == key }?.plan ?: DungeonMapLayoutEngine.layout(
			snapshot = view.snapshot,
			textMetrics = TextMetrics { value -> client.font.width(value) },
			spec = MAP_SPEC,
			debug = DEBUG_ENABLED,
		).also { cachedPlan = CachedPlan(key, it) }

		MinecraftMapPainter.paint(graphics, client.font, plan)
	}

	private data class CacheKey(
		val sessionGeneration: Long,
		val snapshotRevision: Long,
		val fontIdentity: Int,
		val debug: Boolean,
	)

	private data class CachedPlan(
		val key: CacheKey,
		val plan: MapRenderPlan,
	)

	private val MAP_SPEC = MapLayoutSpec()
	private val DEBUG_ENABLED = HudDebugSettings.enabled
}
