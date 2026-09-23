package com.andyy.funnymap.detection

enum class DetectionStatus {
	DETECTED,
	NOT_DETECTED,
	UNKNOWN,
}

enum class ConnectionKind {
	DISCONNECTED,
	SINGLEPLAYER,
	REMOTE,
}

enum class CatacombsFloor(val token: String?) {
	ENTRANCE("E"),
	F1("F1"),
	F2("F2"),
	F3("F3"),
	F4("F4"),
	F5("F5"),
	F6("F6"),
	F7("F7"),
	M1("M1"),
	M2("M2"),
	M3("M3"),
	M4("M4"),
	M5("M5"),
	M6("M6"),
	M7("M7"),
	UNKNOWN(null),
	;

	companion object {
		fun fromToken(token: String?): CatacombsFloor = entries.firstOrNull {
			it.token != null && it.token.equals(token?.trim(), ignoreCase = true)
		} ?: UNKNOWN
	}
}

data class SidebarSnapshot(
	val available: Boolean,
	val title: String = "",
	val lines: List<String> = emptyList(),
) {
	companion object {
		val UNAVAILABLE = SidebarSnapshot(available = false)
	}
}

data class ClientDungeonSignals(
	val connection: ConnectionKind,
	val serverAddress: String? = null,
	val serverBrand: String? = null,
	val sidebar: SidebarSnapshot = SidebarSnapshot.UNAVAILABLE,
)

data class DungeonContext(
	val connection: ConnectionKind,
	val hypixel: DetectionStatus,
	val skyBlock: DetectionStatus,
	val catacombs: DetectionStatus,
	val floor: CatacombsFloor,
) {
	companion object {
		val UNKNOWN = DungeonContext(
			connection = ConnectionKind.DISCONNECTED,
			hypixel = DetectionStatus.UNKNOWN,
			skyBlock = DetectionStatus.UNKNOWN,
			catacombs = DetectionStatus.UNKNOWN,
			floor = CatacombsFloor.UNKNOWN,
		)
	}
}
