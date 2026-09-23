package com.andyy.funnymap.dungeon.capture

import com.andyy.funnymap.dungeon.model.RoomRotation
import com.andyy.funnymap.dungeon.room.WorldCoordinate
import java.util.Locale

data class CapturePreview(
	val captureId: String,
	val metadata: CaptureRoomMetadata,
	val observedRotation: RoomRotation,
	val worldMinimum: WorldCoordinate,
	val worldMaximum: WorldCoordinate,
	val availableChunks: Int,
	val unavailableChunks: Int,
	val failedChunkReads: Int,
	val coverage: Double,
	val eligibleStructuralSamples: Int,
	val policyExclusions: Int,
) {
	init {
		require(captureId.isNotBlank()) { "Capture preview id must not be blank" }
		require(worldMinimum.x <= worldMaximum.x && worldMinimum.y <= worldMaximum.y && worldMinimum.z <= worldMaximum.z) {
			"Capture preview minimum must not exceed maximum"
		}
		listOf(
			availableChunks,
			unavailableChunks,
			failedChunkReads,
			eligibleStructuralSamples,
			policyExclusions,
		).forEach { require(it >= 0) { "Capture preview counts must not be negative" } }
		require(coverage.isFinite() && coverage in 0.0..1.0) { "Capture preview coverage must be between 0 and 1" }
	}

	val totalChunks: Int
		get() = availableChunks + unavailableChunks + failedChunkReads
}

object CapturePreviewFormatter {
	fun lines(preview: CapturePreview): List<String> = listOf(
		"Room: ${preview.metadata.displayName}",
		"Room ID: ${preview.metadata.roomId.value}",
		"Footprint: ${preview.metadata.footprint.shape.name} ${preview.metadata.footprint.localCells}",
		"Observed rotation: ${preview.observedRotation.degrees} degrees",
		"Bounds: ${coordinate(preview.worldMinimum)} .. ${coordinate(preview.worldMaximum)}",
		"Chunks available: ${preview.availableChunks}/${preview.totalChunks}",
		"Coverage: ${percent(preview.coverage)}",
		"Eligible structural samples: ${preview.eligibleStructuralSamples}",
		"Excluded variable/runtime samples or properties: ${preview.policyExclusions}",
		if (preview.unavailableChunks + preview.failedChunkReads == 0) {
			"Availability: complete"
		} else {
			"Availability: INCOMPLETE (${preview.unavailableChunks} unavailable, ${preview.failedChunkReads} read failures)"
		},
	)

	private fun coordinate(value: WorldCoordinate): String = "${value.x},${value.y},${value.z}"

	private fun percent(value: Double): String = "%.1f%%".format(Locale.ROOT, value * 100.0)
}
