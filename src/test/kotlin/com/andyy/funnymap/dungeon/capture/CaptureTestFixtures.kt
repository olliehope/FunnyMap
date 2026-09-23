package com.andyy.funnymap.dungeon.capture

import com.andyy.funnymap.dungeon.model.GridPosition
import com.andyy.funnymap.dungeon.model.RoomFootprint
import com.andyy.funnymap.dungeon.model.RoomId
import com.andyy.funnymap.dungeon.model.RoomRotation
import com.andyy.funnymap.dungeon.model.RoomType
import com.andyy.funnymap.dungeon.room.CoverageRegion
import com.andyy.funnymap.dungeon.room.DirectionalPropertyKind
import com.andyy.funnymap.dungeon.room.FingerprintPolicy
import com.andyy.funnymap.dungeon.room.GameDataVersion
import com.andyy.funnymap.dungeon.room.LocalBlockBounds
import com.andyy.funnymap.dungeon.room.LocalBlockPosition
import com.andyy.funnymap.dungeon.room.ObservationCoverage
import com.andyy.funnymap.dungeon.room.ObservationProvenance
import com.andyy.funnymap.dungeon.room.ObservationSource
import com.andyy.funnymap.dungeon.room.RoomLocalDatum
import com.andyy.funnymap.dungeon.room.RoomObservation
import com.andyy.funnymap.dungeon.room.StructuralSample
import com.andyy.funnymap.dungeon.room.UnavailableReason
import com.andyy.funnymap.dungeon.room.UnavailableRegion
import com.andyy.funnymap.dungeon.room.WorldCoordinate
import java.time.Instant

internal object CaptureTestFixtures {
	val policy = FingerprintPolicy(
		version = "capture-test-v1",
		allowedNamespaces = setOf("minecraft"),
		excludedBlockIds = setOf("minecraft:air", "minecraft:chest"),
		participatingProperties = setOf("facing", "axis"),
		directionalProperties = mapOf(
			"facing" to DirectionalPropertyKind.CARDINAL_FACING,
			"axis" to DirectionalPropertyKind.HORIZONTAL_AXIS,
		),
		minimumSamples = 2,
	)

	val metadata = CaptureRoomMetadata(
		roomId = RoomId("catacombs/synthetic-room"),
		displayName = "Synthetic Room",
		type = RoomType.NORMAL,
		footprint = RoomFootprint.of(GridPosition(0, 0)),
		secretCount = 2,
		cryptCount = 1,
	)

	val bounds = LocalBlockBounds(LocalBlockPosition(0, 0, 0), LocalBlockPosition(7, 0, 0))

	fun samples(
		blockAt: Map<Int, String> = emptyMap(),
		propertiesAt: Map<Int, Map<String, String>> = emptyMap(),
		positions: IntRange = 0..7,
	): List<StructuralSample> = positions.map { x ->
		StructuralSample(
			position = LocalBlockPosition(x, 0, 0),
			blockId = blockAt[x] ?: "minecraft:stone_bricks",
			properties = propertiesAt[x].orEmpty(),
		)
	}

	fun capture(
		id: String,
		samples: List<StructuralSample> = samples(),
		availableMaxX: Int = 7,
		rotation: RoomRotation = RoomRotation.DEGREES_0,
		observationBounds: LocalBlockBounds = bounds,
		roomMetadata: CaptureRoomMetadata = metadata,
	): RawRoomCapture {
		val available = if (availableMaxX >= 0) {
			listOf(CoverageRegion(LocalBlockBounds(LocalBlockPosition(0, 0, 0), LocalBlockPosition(availableMaxX, 0, 0)), 0, 0))
		} else {
			emptyList()
		}
		val unavailable = if (availableMaxX < observationBounds.max.x) {
			listOf(
				UnavailableRegion(
					CoverageRegion(
						LocalBlockBounds(
							LocalBlockPosition(availableMaxX + 1, 0, 0),
							observationBounds.max,
						),
						1,
						0,
					),
					UnavailableReason.CHUNK_UNAVAILABLE,
				),
			)
		} else {
			emptyList()
		}
		val observation = RoomObservation(
			observationId = id,
			worldSessionGeneration = 4,
			revision = id.substringAfterLast('-').toLongOrNull() ?: 1,
			gameDataVersion = GameDataVersion("26.1.2", 9999, "main"),
			fingerprintPolicyVersion = policy.version,
			footprint = roomMetadata.footprint,
			bounds = observationBounds,
			datum = RoomLocalDatum(WorldCoordinate(128, 64, -32)),
			samples = samples,
			coverage = ObservationCoverage(available, unavailable),
			provenance = ObservationProvenance(
				source = ObservationSource.DEVELOPER_CAPTURE,
				captureId = id,
				notes = "synthetic fixture",
				attributes = mapOf("suite" to "capture"),
			),
			observedRotation = rotation,
		)
		return RawCaptureFactory.createAt(
			captureId = id,
			metadata = roomMetadata,
			observation = observation,
			policy = policy,
			developerNotes = "fixture $id",
			capturedAt = Instant.parse("2026-09-22T12:00:00Z").plusSeconds(id.length.toLong()),
		)
	}
}
