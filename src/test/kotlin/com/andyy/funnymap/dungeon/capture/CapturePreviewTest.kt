package com.andyy.funnymap.dungeon.capture

import com.andyy.funnymap.dungeon.model.GridPosition
import com.andyy.funnymap.dungeon.model.RoomFootprint
import com.andyy.funnymap.dungeon.model.RoomId
import com.andyy.funnymap.dungeon.model.RoomRotation
import com.andyy.funnymap.dungeon.model.RoomType
import com.andyy.funnymap.dungeon.room.WorldCoordinate
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class CapturePreviewTest {
	@Test
	fun `preview makes incomplete coverage and non-writing confirmation explicit`() {
		val preview = CapturePreview(
			captureId = "s4:c1",
			metadata = CaptureRoomMetadata(
				roomId = RoomId("catacombs/alpha-one"),
				displayName = "Alpha One",
				type = RoomType.NORMAL,
				footprint = RoomFootprint.of(GridPosition(0, 0)),
				secretCount = 2,
				cryptCount = 1,
			),
			observedRotation = RoomRotation.DEGREES_90,
			worldMinimum = WorldCoordinate(-16, 40, 32),
			worldMaximum = WorldCoordinate(15, 79, 63),
			availableChunks = 3,
			unavailableChunks = 1,
			failedChunkReads = 0,
			coverage = 0.75,
			eligibleStructuralSamples = 184,
			policyExclusions = 63,
		)

		val lines = CapturePreviewFormatter.lines(preview)

		assertEquals(4, preview.totalChunks)
		assertContains(lines, "Observed rotation: 90 degrees")
		assertContains(lines, "Chunks available: 3/4")
		assertContains(lines, "Coverage: 75.0%")
		assertContains(lines, "Availability: INCOMPLETE (1 unavailable, 0 read failures)")
	}
}
