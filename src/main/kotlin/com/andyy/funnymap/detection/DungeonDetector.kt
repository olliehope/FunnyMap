package com.andyy.funnymap.detection

import java.util.Locale

/** Classifies client-visible connection and sidebar signals without touching world data. */
class DungeonDetector {
	fun detect(signals: ClientDungeonSignals): DungeonContext {
		val hypixel = detectHypixel(signals)
		val sidebarText = buildList {
			add(normalize(signals.sidebar.title))
			signals.sidebar.lines.mapTo(this, ::normalize)
		}.filter(String::isNotBlank)

		val catacombsMatch = if (hypixel == DetectionStatus.DETECTED) {
			sidebarText.firstNotNullOfOrNull(CATACOMBS_PATTERN::find)
		} else {
			null
		}

		val skyBlock = when {
			hypixel == DetectionStatus.NOT_DETECTED -> DetectionStatus.NOT_DETECTED
			hypixel == DetectionStatus.UNKNOWN -> DetectionStatus.UNKNOWN
			catacombsMatch != null -> DetectionStatus.DETECTED
			sidebarText.any { it.contains("SKYBLOCK", ignoreCase = true) } -> DetectionStatus.DETECTED
			!signals.sidebar.available || sidebarText.isEmpty() -> DetectionStatus.UNKNOWN
			else -> DetectionStatus.NOT_DETECTED
		}

		val catacombs = when {
			hypixel == DetectionStatus.NOT_DETECTED || skyBlock == DetectionStatus.NOT_DETECTED -> {
				DetectionStatus.NOT_DETECTED
			}
			catacombsMatch != null -> DetectionStatus.DETECTED
			skyBlock == DetectionStatus.DETECTED -> DetectionStatus.NOT_DETECTED
			else -> DetectionStatus.UNKNOWN
		}

		return DungeonContext(
			connection = signals.connection,
			hypixel = hypixel,
			skyBlock = skyBlock,
			catacombs = catacombs,
			floor = if (catacombs == DetectionStatus.DETECTED) {
				CatacombsFloor.fromToken(catacombsMatch?.groups?.get(1)?.value)
			} else {
				CatacombsFloor.UNKNOWN
			},
		)
	}

	private fun detectHypixel(signals: ClientDungeonSignals): DetectionStatus = when (signals.connection) {
		ConnectionKind.DISCONNECTED -> DetectionStatus.UNKNOWN
		ConnectionKind.SINGLEPLAYER -> DetectionStatus.NOT_DETECTED
		ConnectionKind.REMOTE -> when {
			isOfficialHypixelAddress(signals.serverAddress) -> {
				DetectionStatus.DETECTED
			}
			!signals.serverAddress.isNullOrBlank() &&
				!signals.serverBrand.isNullOrBlank() &&
				!isHypixelBrand(signals.serverBrand) -> {
				DetectionStatus.NOT_DETECTED
			}
			else -> DetectionStatus.UNKNOWN
		}
	}

	private fun isOfficialHypixelAddress(address: String?): Boolean {
		val host = address?.let(::extractHost) ?: return false
		return OFFICIAL_HYPIXEL_DOMAINS.any { domain -> host == domain || host.endsWith(".$domain") }
	}

	private fun extractHost(address: String): String {
		val value = address.trim().lowercase(Locale.ROOT)
		val host = when {
			value.startsWith("[") -> value.substringAfter('[').substringBefore(']')
			value.count { it == ':' } == 1 -> value.substringBefore(':')
			else -> value
		}
		return host.removeSuffix(".")
	}

	private fun isHypixelBrand(brand: String?): Boolean = brand
		?.let(HYPIXEL_BRAND_PATTERN::containsMatchIn)
		?: false

	private fun normalize(value: String): String = value
		.replace(FORMATTING_PATTERN, "")
		.replace('\u00a0', ' ')
		.replace(FORMAT_CHAR_PATTERN, "")
		.replace(WHITESPACE_PATTERN, " ")
		.trim()

	private companion object {
		val OFFICIAL_HYPIXEL_DOMAINS = setOf("hypixel.net", "hypixel.io")
		val CATACOMBS_PATTERN = Regex(
			pattern = """\b(?:THE\s+)?CATACOMBS\b(?:\s*[-:])?(?:\s*\(([A-Z]\d{0,2}|E)\))?""",
			option = RegexOption.IGNORE_CASE,
		)
		val HYPIXEL_BRAND_PATTERN = Regex("""(^|[^a-z0-9])hypixel([^a-z0-9]|$)""", RegexOption.IGNORE_CASE)
		val FORMATTING_PATTERN = Regex("""\u00a7.""")
		val FORMAT_CHAR_PATTERN = Regex("""\p{Cf}""")
		val WHITESPACE_PATTERN = Regex("""\s+""")
	}
}
