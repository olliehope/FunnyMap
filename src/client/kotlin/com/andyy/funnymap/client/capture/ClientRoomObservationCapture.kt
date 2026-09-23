package com.andyy.funnymap.client.capture

import com.andyy.funnymap.dungeon.model.RoomFootprint
import com.andyy.funnymap.dungeon.model.RoomRotation
import com.andyy.funnymap.dungeon.room.CoverageRegion
import com.andyy.funnymap.dungeon.room.FingerprintPolicy
import com.andyy.funnymap.dungeon.room.GameDataVersion
import com.andyy.funnymap.dungeon.room.HorizontalDatum
import com.andyy.funnymap.dungeon.room.LocalBlockBounds
import com.andyy.funnymap.dungeon.room.LocalBlockPosition
import com.andyy.funnymap.dungeon.room.ObservationCoverage
import com.andyy.funnymap.dungeon.room.ObservationProvenance
import com.andyy.funnymap.dungeon.room.ObservationSource
import com.andyy.funnymap.dungeon.room.RoomGeometry
import com.andyy.funnymap.dungeon.room.RoomLocalDatum
import com.andyy.funnymap.dungeon.room.RoomObservation
import com.andyy.funnymap.dungeon.room.StructuralSample
import com.andyy.funnymap.dungeon.room.UnavailableReason
import com.andyy.funnymap.dungeon.room.UnavailableRegion
import com.andyy.funnymap.dungeon.room.VerticalDatum
import com.andyy.funnymap.dungeon.room.WorldCoordinate
import net.minecraft.SharedConstants
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.chunk.status.ChunkStatus

data class WorldBlockBounds(
	val minX: Int,
	val minY: Int,
	val minZ: Int,
	val maxX: Int,
	val maxY: Int,
	val maxZ: Int,
) {
	init {
		require(minX <= maxX && minY <= maxY && minZ <= maxZ) {
			"Capture minimum must not exceed maximum"
		}
	}

	val volume: Long
		get() = (maxX.toLong() - minX + 1) *
			(maxY.toLong() - minY + 1) *
			(maxZ.toLong() - minZ + 1)
}

data class ClientCaptureRequest(
	val observationId: String,
	val worldSessionGeneration: Long,
	val revision: Long,
	val canonicalFootprint: RoomFootprint,
	val observedRotation: RoomRotation,
	val worldBounds: WorldBlockBounds,
	val notes: String? = null,
)

data class ClientCaptureResult(
	val observation: RoomObservation,
	val availableChunkCount: Int,
	val unavailableChunkCount: Int,
	val readFailedChunkCount: Int,
)

/** The only capture component allowed to read live Minecraft world objects. */
object ClientRoomObservationCapture {
	fun capture(client: Minecraft, request: ClientCaptureRequest): ClientCaptureResult {
		check(client.isSameThread) { "Room observations must be captured on the Minecraft client thread" }
		val level = requireNotNull(client.level) { "No client world is loaded" }
		require(request.worldBounds.volume <= MAX_CAPTURE_VOLUME) {
			"Capture volume ${request.worldBounds.volume} exceeds the limit of $MAX_CAPTURE_VOLUME blocks"
		}
		require(request.worldBounds.minY >= level.minY && request.worldBounds.maxY < level.maxY) {
			"Capture Y range ${request.worldBounds.minY}..${request.worldBounds.maxY} is outside " +
				"client world height ${level.minY}..${level.maxY - 1}"
		}

		val samples = ArrayList<StructuralSample>()
		val availableRegions = ArrayList<CoverageRegion>()
		val unavailableRegions = ArrayList<UnavailableRegion>()
		val position = BlockPos.MutableBlockPos()
		val minChunkX = Math.floorDiv(request.worldBounds.minX, CHUNK_SIZE)
		val maxChunkX = Math.floorDiv(request.worldBounds.maxX, CHUNK_SIZE)
		val minChunkZ = Math.floorDiv(request.worldBounds.minZ, CHUNK_SIZE)
		val maxChunkZ = Math.floorDiv(request.worldBounds.maxZ, CHUNK_SIZE)

		for (chunkX in minChunkX..maxChunkX) {
			for (chunkZ in minChunkZ..maxChunkZ) {
				val worldMinX = maxOf(request.worldBounds.minX, chunkX * CHUNK_SIZE)
				val worldMaxX = minOf(request.worldBounds.maxX, chunkX * CHUNK_SIZE + CHUNK_MASK)
				val worldMinZ = maxOf(request.worldBounds.minZ, chunkZ * CHUNK_SIZE)
				val worldMaxZ = minOf(request.worldBounds.maxZ, chunkZ * CHUNK_SIZE + CHUNK_MASK)
				val region = CoverageRegion(
					bounds = LocalBlockBounds(
						min = localPosition(request.worldBounds, worldMinX, request.worldBounds.minY, worldMinZ),
						max = localPosition(request.worldBounds, worldMaxX, request.worldBounds.maxY, worldMaxZ),
					),
					sourceChunkX = chunkX,
					sourceChunkZ = chunkZ,
				)
				val chunk = level.chunkSource.getChunk(chunkX, chunkZ, ChunkStatus.FULL, false)
				if (chunk == null) {
					unavailableRegions += UnavailableRegion(region, UnavailableReason.CHUNK_UNAVAILABLE)
					continue
				}

				val regionSamples = ArrayList<StructuralSample>(region.bounds.volume.toInt())
				try {
					for (x in worldMinX..worldMaxX) {
						for (z in worldMinZ..worldMaxZ) {
							for (y in request.worldBounds.minY..request.worldBounds.maxY) {
								position.set(x, y, z)
								regionSamples += snapshot(
									state = chunk.getBlockState(position),
									position = localPosition(request.worldBounds, x, y, z),
								)
							}
						}
					}
				} catch (_: Exception) {
					unavailableRegions += UnavailableRegion(region, UnavailableReason.CLIENT_READ_FAILED)
					continue
				}
				availableRegions += region
				samples += regionSamples
			}
		}

		val version = SharedConstants.getCurrentVersion()
		val dataVersion = version.dataVersion()
		val localBounds = LocalBlockBounds(
			min = LocalBlockPosition(0, 0, 0),
			max = LocalBlockPosition(
				x = request.worldBounds.maxX - request.worldBounds.minX,
				y = request.worldBounds.maxY - request.worldBounds.minY,
				z = request.worldBounds.maxZ - request.worldBounds.minZ,
			),
		)
		val observation = RoomObservation(
			observationId = request.observationId,
			worldSessionGeneration = request.worldSessionGeneration,
			revision = request.revision,
			gameDataVersion = GameDataVersion(
				gameVersion = version.id(),
				dataVersion = dataVersion.version(),
				dataVersionSeries = dataVersion.series(),
			),
			fingerprintPolicyVersion = FingerprintPolicy.DEFAULT.version,
			footprint = RoomGeometry.rotateFootprint(request.canonicalFootprint, request.observedRotation),
			bounds = localBounds,
			datum = RoomLocalDatum(
				worldOrigin = WorldCoordinate(
					request.worldBounds.minX,
					request.worldBounds.minY,
					request.worldBounds.minZ,
				),
				verticalDatum = VerticalDatum.DEVELOPER_DECLARED,
				horizontalDatum = HorizontalDatum.DEVELOPER_DECLARED,
			),
			samples = samples,
			coverage = ObservationCoverage(
				availableRegions = availableRegions,
				unavailableRegions = unavailableRegions,
			),
			provenance = ObservationProvenance(
				source = ObservationSource.DEVELOPER_CAPTURE,
				captureId = request.observationId,
				notes = request.notes,
			),
			observedRotation = request.observedRotation,
		)

		return ClientCaptureResult(
			observation = observation,
			availableChunkCount = availableRegions.size,
			unavailableChunkCount = unavailableRegions.count {
				it.reason == UnavailableReason.CHUNK_UNAVAILABLE
			},
			readFailedChunkCount = unavailableRegions.count {
				it.reason == UnavailableReason.CLIENT_READ_FAILED
			},
		)
	}

	private fun snapshot(state: BlockState, position: LocalBlockPosition): StructuralSample {
		val properties = state.values.iterator().asSequence().associate { value ->
			value.property().name to value.valueName()
		}
		return StructuralSample(
			position = position,
			blockId = BuiltInRegistries.BLOCK.getKey(state.block).toString(),
			properties = properties,
		)
	}

	private fun localPosition(bounds: WorldBlockBounds, x: Int, y: Int, z: Int): LocalBlockPosition =
		LocalBlockPosition(x - bounds.minX, y - bounds.minY, z - bounds.minZ)

	private const val CHUNK_SIZE = 16
	private const val CHUNK_MASK = CHUNK_SIZE - 1
	private const val MAX_CAPTURE_VOLUME = 524_288L
}
