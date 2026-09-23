package com.andyy.funnymap.client.detection

import com.andyy.funnymap.detection.ClientDungeonSignals
import com.andyy.funnymap.detection.ConnectionKind
import com.andyy.funnymap.detection.SidebarSnapshot
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.world.scores.DisplaySlot
import net.minecraft.world.scores.PlayerTeam

internal object MinecraftDungeonSignals {
	fun read(client: Minecraft): ClientDungeonSignals = ClientDungeonSignals(
		connection = connectionKind(client),
		serverAddress = client.currentServer?.ip,
		serverBrand = client.connection?.serverBrand()?.takeIf(String::isNotBlank),
		sidebar = readSidebar(client),
	)

	private fun connectionKind(client: Minecraft): ConnectionKind = when {
		client.level == null -> ConnectionKind.DISCONNECTED
		client.isSingleplayer -> ConnectionKind.SINGLEPLAYER
		client.connection != null -> ConnectionKind.REMOTE
		else -> ConnectionKind.DISCONNECTED
	}

	private fun readSidebar(client: Minecraft): SidebarSnapshot {
		val level: ClientLevel? = client.level
		level ?: return SidebarSnapshot.UNAVAILABLE
		val scoreboard = level.scoreboard
		val teamSidebar = client.player?.team?.color
			?.let(DisplaySlot::teamColorToSlot)
			?.let(scoreboard::getDisplayObjective)
		val objective = teamSidebar ?: scoreboard.getDisplayObjective(DisplaySlot.SIDEBAR)
			?: return SidebarSnapshot.UNAVAILABLE

		val lines = scoreboard.listPlayerScores(objective)
			.asSequence()
			.filterNot { it.isHidden }
			.sortedByDescending { it.value }
			.take(MAX_SIDEBAR_LINES)
			.map { entry ->
				val team = scoreboard.getPlayersTeam(entry.owner)
				PlayerTeam.formatNameForTeam(team, entry.ownerName()).string
			}
			.toList()

		return SidebarSnapshot(
			available = true,
			title = objective.displayName.string,
			lines = lines,
		)
	}

	private const val MAX_SIDEBAR_LINES = 15
}
