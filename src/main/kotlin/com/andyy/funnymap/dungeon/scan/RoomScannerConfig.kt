package com.andyy.funnymap.dungeon.scan

import com.andyy.funnymap.dungeon.model.GridBounds

data class CatacombsLatticeProfile(
	val cellSpacingBlocks: Int = 32,
	val logicalBounds: GridBounds = GridBounds(),
) {
	init {
		require(cellSpacingBlocks > 0) { "Dungeon lattice spacing must be positive" }
	}
}

data class RoomScannerConfig(
	val blockReadsPerTick: Int = 4_096,
	val matchesPerTick: Int = 2,
	val maximumQueuedWork: Int = 512,
	val chunkDiscoveryRadius: Int = 6,
	val fallbackDiscoveryIntervalTicks: Int = 40,
	val successfulRevalidationTicks: Int = 200,
	val failedRetryTicks: Int = 100,
	val minimumAnchorVotes: Int = 4,
	val maximumAnchorSignatureReferences: Int = 64,
	val maximumAnchorLookupsPerBlock: Int = 64,
	val maximumObservationVolume: Long = 524_288,
	val lattice: CatacombsLatticeProfile = CatacombsLatticeProfile(),
) {
	init {
		require(blockReadsPerTick > 0) { "Block reads per tick must be positive" }
		require(matchesPerTick > 0) { "Matches per tick must be positive" }
		require(maximumQueuedWork > 0) { "Maximum queued work must be positive" }
		require(chunkDiscoveryRadius >= 0) { "Chunk discovery radius must not be negative" }
		require(fallbackDiscoveryIntervalTicks > 0) { "Fallback discovery interval must be positive" }
		require(successfulRevalidationTicks > 0) { "Successful revalidation interval must be positive" }
		require(failedRetryTicks > 0) { "Failed retry interval must be positive" }
		require(minimumAnchorVotes > 0) { "Minimum anchor votes must be positive" }
		require(maximumAnchorSignatureReferences > 0) { "Maximum anchor signature references must be positive" }
		require(maximumAnchorLookupsPerBlock > 0) { "Maximum anchor lookups per block must be positive" }
		require(maximumObservationVolume > 0) { "Maximum observation volume must be positive" }
	}
}
