package com.andyy.funnymap.detection

import kotlin.test.Test
import kotlin.test.assertEquals

class DungeonDetectorTest {
	private val detector = DungeonDetector()

	@Test
	fun `detects a normal Catacombs floor from visible sidebar text`() {
		val result = detector.detect(
			remoteHypixel(
				title = "SKYBLOCK",
				lines = listOf("Late Winter 12th", "The Catacombs (F7)"),
			),
		)

		assertEquals(DetectionStatus.DETECTED, result.hypixel)
		assertEquals(DetectionStatus.DETECTED, result.skyBlock)
		assertEquals(DetectionStatus.DETECTED, result.catacombs)
		assertEquals(CatacombsFloor.F7, result.floor)
	}

	@Test
	fun `normalizes formatting and detects Master Mode`() {
		val result = detector.detect(
			remoteHypixel(
				title = "\u00a76\u00a7lSKYBLOCK",
				lines = listOf("\u00a77The Catacombs \u00a7c(M7)"),
			),
		)

		assertEquals(CatacombsFloor.M7, result.floor)
	}

	@Test
	fun `keeps an unfamiliar floor token Unknown without losing dungeon detection`() {
		val result = detector.detect(
			remoteHypixel(title = "SKYBLOCK", lines = listOf("The Catacombs (F8)")),
		)

		assertEquals(DetectionStatus.DETECTED, result.catacombs)
		assertEquals(CatacombsFloor.UNKNOWN, result.floor)
	}

	@Test
	fun `missing sidebar evidence remains Unknown`() {
		val result = detector.detect(
			ClientDungeonSignals(
				connection = ConnectionKind.REMOTE,
				serverAddress = "mc.hypixel.net",
				serverBrand = "Hypixel BungeeCord",
			),
		)

		assertEquals(DetectionStatus.DETECTED, result.hypixel)
		assertEquals(DetectionStatus.UNKNOWN, result.skyBlock)
		assertEquals(DetectionStatus.UNKNOWN, result.catacombs)
		assertEquals(CatacombsFloor.UNKNOWN, result.floor)
	}

	@Test
	fun `a visible Hypixel lobby scoreboard is not SkyBlock`() {
		val result = detector.detect(
			remoteHypixel(title = "HYPIXEL", lines = listOf("Prototype Lobby")),
		)

		assertEquals(DetectionStatus.NOT_DETECTED, result.skyBlock)
		assertEquals(DetectionStatus.NOT_DETECTED, result.catacombs)
	}

	@Test
	fun `does not accept a lookalike Hypixel domain`() {
		val result = detector.detect(
			ClientDungeonSignals(
				connection = ConnectionKind.REMOTE,
				serverAddress = "hypixel.net.example.org:25565",
				serverBrand = "vanilla",
				sidebar = SidebarSnapshot(available = true, title = "SKYBLOCK"),
			),
		)

		assertEquals(DetectionStatus.NOT_DETECTED, result.hypixel)
		assertEquals(DetectionStatus.NOT_DETECTED, result.skyBlock)
	}

	@Test
	fun `a received Hypixel brand cannot prove a custom address`() {
		val result = detector.detect(
			ClientDungeonSignals(
				connection = ConnectionKind.REMOTE,
				serverAddress = "play.example.org",
				serverBrand = "Hypixel BungeeCord",
				sidebar = SidebarSnapshot(available = true, title = "SKYBLOCK"),
			),
		)

		assertEquals(DetectionStatus.UNKNOWN, result.hypixel)
		assertEquals(DetectionStatus.UNKNOWN, result.skyBlock)
	}

	@Test
	fun `accepts the official hypixel io domain`() {
		val result = detector.detect(
			ClientDungeonSignals(
				connection = ConnectionKind.REMOTE,
				serverAddress = "alpha.hypixel.io.",
				serverBrand = "Hypixel BungeeCord",
				sidebar = SidebarSnapshot(available = true, title = "SKYBLOCK"),
			),
		)

		assertEquals(DetectionStatus.DETECTED, result.hypixel)
	}

	@Test
	fun `disconnected state is Unknown`() {
		val result = detector.detect(ClientDungeonSignals(ConnectionKind.DISCONNECTED))

		assertEquals(DungeonContext.UNKNOWN, result)
	}

	private fun remoteHypixel(title: String, lines: List<String>) = ClientDungeonSignals(
		connection = ConnectionKind.REMOTE,
		serverAddress = "mc.hypixel.net:25565",
		serverBrand = "Hypixel BungeeCord",
		sidebar = SidebarSnapshot(available = true, title = title, lines = lines),
	)
}
