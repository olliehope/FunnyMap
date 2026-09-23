package com.andyy.funnymap.client.capture

import com.andyy.funnymap.FunnyMap
import com.andyy.funnymap.build.BuildInfo
import com.andyy.funnymap.client.dungeon.RoomDataService
import com.andyy.funnymap.client.dungeon.DungeonSnapshotStore
import com.andyy.funnymap.dungeon.capture.CaptureComparator
import com.andyy.funnymap.dungeon.capture.CaptureFinalizer
import com.andyy.funnymap.dungeon.capture.CaptureRoomMetadata
import com.andyy.funnymap.dungeon.capture.FileRawCaptureRepository
import com.andyy.funnymap.dungeon.capture.FinalizationReport
import com.andyy.funnymap.dungeon.capture.RawCaptureFactory
import com.andyy.funnymap.dungeon.capture.RawCaptureJsonCodec
import com.andyy.funnymap.dungeon.capture.RawCaptureRepository
import com.andyy.funnymap.dungeon.model.GridPosition
import com.andyy.funnymap.dungeon.model.RoomFootprint
import com.andyy.funnymap.dungeon.model.RoomId
import com.andyy.funnymap.dungeon.model.RoomRotation
import com.andyy.funnymap.dungeon.model.RoomType
import com.andyy.funnymap.dungeon.room.FingerprintPolicy
import com.andyy.funnymap.dungeon.room.RoomGeometry
import com.mojang.brigadier.Command
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.context.CommandContext
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument
import net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/** Explicit developer workflow for producing independently captured room data. */
object RoomCaptureTool {
	private val captureSequence = AtomicLong(System.currentTimeMillis())
	private val finalized = LinkedHashMap<RoomId, FinalizationReport>()
	private lateinit var repository: RawCaptureRepository
	private lateinit var rootDirectory: Path
	private var initialized = false

	fun initialize() {
		check(!initialized) { "RoomCaptureTool is already initialized" }
		initialized = true
		rootDirectory = Minecraft.getInstance().gameDirectory.toPath().resolve(OUTPUT_DIRECTORY)
		repository = FileRawCaptureRepository(
			directory = rootDirectory.resolve(RAW_DIRECTORY),
			codec = RawCaptureJsonCodec,
		)
		ClientCommandRegistrationCallback.EVENT.register { dispatcher, _ ->
			dispatcher.register(literal(ROOT_COMMAND).then(statusCommand())
				.then(captureCommand())
				.then(capturesCommand())
				.then(compareCommand())
				.then(finalizeCommand())
				.then(exportCommand()))
		}
		FunnyMap.LOGGER.info("Room capture developer commands registered under /{}", ROOT_COMMAND)
	}

	private fun statusCommand() = literal("status").executes { context ->
		execute(context, null, ::status)
	}

	private fun captureCommand() = literal("capture").then(
		argument("roomId", StringArgumentType.word()).then(
			argument("displayName", StringArgumentType.string()).then(
				argument("type", StringArgumentType.word()).then(
					argument("footprint", StringArgumentType.string()).then(
						argument("secrets", IntegerArgumentType.integer(0)).then(
							argument("crypts", IntegerArgumentType.integer(0)).then(
								argument("rotation", IntegerArgumentType.integer(0, 270)).then(
									argument("minX", IntegerArgumentType.integer()).then(
										argument("minY", IntegerArgumentType.integer()).then(
											argument("minZ", IntegerArgumentType.integer()).then(
												argument("maxX", IntegerArgumentType.integer()).then(
													argument("maxY", IntegerArgumentType.integer()).then(
														argument("maxZ", IntegerArgumentType.integer())
															.executes { context -> execute(context, null, ::capture) }
															.then(
																argument("notes", StringArgumentType.greedyString()).executes { context ->
																	execute(
																		context,
																		StringArgumentType.getString(context, "notes"),
																		::capture,
																	)
																},
															),
													),
												),
											),
										),
									),
								),
							),
						),
					),
				),
			),
		),
	)

	private fun capturesCommand() = literal("captures")
		.executes { context -> execute(context, null, ::listAllCaptures) }
		.then(
			argument("roomId", StringArgumentType.word()).executes { context ->
				execute(context, null, ::listRoomCaptures)
			},
		)

	private fun compareCommand() = literal("compare").then(
		argument("roomId", StringArgumentType.word()).executes { context ->
			execute(context, null, ::compare)
		},
	)

	private fun finalizeCommand() = literal("finalize").then(
		argument("roomId", StringArgumentType.word()).then(
			argument("fingerprintId", StringArgumentType.word())
				.executes { context -> execute(context, null, ::finalize) }
				.then(
					argument("minCaptures", IntegerArgumentType.integer(1)).then(
						argument("minCoveragePercent", IntegerArgumentType.integer(0, 100)).then(
							argument("minStableSamples", IntegerArgumentType.integer(1)).then(
								argument("minPositionObservations", IntegerArgumentType.integer(1)).executes { context ->
									execute(context, null) { commandContext, _ ->
										finalize(
											commandContext,
											config = com.andyy.funnymap.dungeon.capture.FinalizationConfig(
												minimumIndependentCaptures = IntegerArgumentType.getInteger(commandContext, "minCaptures"),
												minimumObservationCoverage = IntegerArgumentType.getInteger(commandContext, "minCoveragePercent") / 100.0,
												minimumStableSamples = IntegerArgumentType.getInteger(commandContext, "minStableSamples"),
												minimumPositionObservations = IntegerArgumentType.getInteger(commandContext, "minPositionObservations"),
											),
										)
									}
								},
							),
						),
					),
				),
		),
	)

	private fun exportCommand() = literal("export").then(
		argument("roomId", StringArgumentType.word()).executes { context ->
			execute(context, null, ::export)
		},
	)

	private fun capture(
		context: CommandContext<FabricClientCommandSource>,
		notes: String?,
	) {
		val canonicalFootprint = parseFootprint(StringArgumentType.getString(context, "footprint"))
		val metadata = CaptureRoomMetadata(
			roomId = roomId(context),
			displayName = StringArgumentType.getString(context, "displayName"),
			type = parseRoomType(StringArgumentType.getString(context, "type")),
			footprint = canonicalFootprint,
			secretCount = IntegerArgumentType.getInteger(context, "secrets"),
			cryptCount = IntegerArgumentType.getInteger(context, "crypts"),
		)
		val bounds = WorldBlockBounds(
			minX = IntegerArgumentType.getInteger(context, "minX"),
			minY = IntegerArgumentType.getInteger(context, "minY"),
			minZ = IntegerArgumentType.getInteger(context, "minZ"),
			maxX = IntegerArgumentType.getInteger(context, "maxX"),
			maxY = IntegerArgumentType.getInteger(context, "maxY"),
			maxZ = IntegerArgumentType.getInteger(context, "maxZ"),
		)
		val observedRotation = parseRotation(IntegerArgumentType.getInteger(context, "rotation"))
		val view = DungeonSnapshotStore.view()
		val revision = captureSequence.incrementAndGet()
		val captureId = "s${view.session.generation}:c$revision"
		val normalizedNotes = notes?.trim()?.takeIf(String::isNotEmpty)
		val result = ClientRoomObservationCapture.capture(
			client = context.source.client,
			request = ClientCaptureRequest(
				observationId = captureId,
				worldSessionGeneration = view.session.generation,
				revision = revision,
				canonicalFootprint = canonicalFootprint,
				observedRotation = observedRotation,
				worldBounds = bounds,
				notes = normalizedNotes,
			),
		)
		val rawCapture = RawCaptureFactory.create(
			captureId = captureId,
			metadata = metadata,
			observation = result.observation,
			policy = FingerprintPolicy.DEFAULT,
			developerNotes = normalizedNotes,
		)
		repository.save(rawCapture)
		finalized.remove(metadata.roomId)

		context.source.sendFeedback(
			Component.literal(
				"Captured $captureId: ${rawCapture.samples.size} structural samples, " +
					"${rawCapture.policyExclusions.size} policy exclusions, " +
					"${formatPercent(result.observation.coverage.coverageRatio)} coverage " +
					"(${result.unavailableChunkCount} unavailable chunks, " +
					"${result.readFailedChunkCount} failed chunk reads).",
			),
		)
	}

	private fun listAllCaptures(context: CommandContext<FabricClientCommandSource>, ignored: String?) {
		val captures = repository.all()
		if (captures.isEmpty()) {
			context.source.sendFeedback(Component.literal("No raw room captures recorded."))
			return
		}
		val groups = captures.groupingBy { it.metadata.roomId }.eachCount().toSortedMap(compareBy(RoomId::value))
		context.source.sendFeedback(Component.literal("${captures.size} raw capture(s) across ${groups.size} room(s):"))
		groups.forEach { (roomId, count) ->
			context.source.sendFeedback(Component.literal("${roomId.value}: $count"))
		}
	}

	private fun listRoomCaptures(context: CommandContext<FabricClientCommandSource>, ignored: String?) {
		val id = roomId(context)
		val captures = repository.findByRoom(id)
		if (captures.isEmpty()) {
			context.source.sendFeedback(Component.literal("No raw captures for ${id.value}."))
			return
		}
		context.source.sendFeedback(Component.literal("${id.value}: ${captures.size} raw capture(s)"))
		captures.take(MAX_LISTED_CAPTURES).forEach { capture ->
			context.source.sendFeedback(
				Component.literal(
					"${capture.captureId} rotation=${capture.observation.observedRotation?.degrees ?: "unknown"} " +
						"coverage=${formatPercent(capture.observation.coverage.coverageRatio)}",
				),
			)
		}
		if (captures.size > MAX_LISTED_CAPTURES) {
			context.source.sendFeedback(Component.literal("...and ${captures.size - MAX_LISTED_CAPTURES} more"))
		}
	}

	private fun compare(context: CommandContext<FabricClientCommandSource>, ignored: String?) {
		val id = roomId(context)
		val captures = requireCaptures(id)
		val comparison = CaptureComparator.compare(captures)
		val reportPath = RoomCaptureReports.writeComparison(rootDirectory.resolve(REPORTS_DIRECTORY), comparison)
		context.source.sendFeedback(
			Component.literal(
				"Compared ${comparison.captureIds.size} capture(s): ${comparison.stableSamples.size} stable samples, " +
					"${comparison.changedBlockIdentifiers.size} block changes, " +
					"${comparison.changedProperties.size} property changes, " +
					"${comparison.unavailablePositions.size} unavailable positions. Report: $reportPath",
			),
		)
	}

	private fun finalize(
		context: CommandContext<FabricClientCommandSource>,
		ignored: String? = null,
		config: com.andyy.funnymap.dungeon.capture.FinalizationConfig =
			com.andyy.funnymap.dungeon.capture.FinalizationConfig(),
	) {
		val id = roomId(context)
		val report = CaptureFinalizer.finalize(
			captures = requireCaptures(id),
			policy = FingerprintPolicy.DEFAULT,
			fingerprintId = StringArgumentType.getString(context, "fingerprintId"),
			config = config,
		)
		finalized[id] = report
		val reportDirectory = rootDirectory.resolve(REPORTS_DIRECTORY)
		RoomCaptureReports.writeComparison(reportDirectory, report.comparison)
		val finalizationPath = RoomCaptureReports.writeFinalization(reportDirectory, report)
		context.source.sendFeedback(
			Component.literal(
				"Finalization ${report.status}: ${report.acceptedSamples.size} accepted samples, " +
				"${report.exclusions.size} exclusions, ${report.warnings.size} warning(s). Report: $finalizationPath",
			),
		)
		report.warnings.forEach { warning -> context.source.sendFeedback(Component.literal("Warning: $warning")) }
		if (report.candidateJson != null) {
			context.source.sendFeedback(Component.literal("Candidate is review-ready; run /$ROOT_COMMAND export ${id.value}."))
		}
	}

	private fun export(context: CommandContext<FabricClientCommandSource>, ignored: String?) {
		val id = roomId(context)
		val report = requireNotNull(finalized[id]) {
			"No finalized candidate for ${id.value}; run /$ROOT_COMMAND finalize ${id.value} <fingerprintId> first"
		}
		val json = requireNotNull(report.candidateJson) {
			"Finalization for ${id.value} was rejected; inspect the comparison report and capture again"
		}
		val directory = rootDirectory.resolve(EXPORTS_DIRECTORY)
		Files.createDirectories(directory)
		val target = directory.resolve(RoomCaptureReports.fileStem(id.value) + ".rooms.json")
		writeAtomically(target, json)
		context.source.sendFeedback(Component.literal("Exported RoomDatabase-compatible candidate to $target"))
	}

	private fun status(context: CommandContext<FabricClientCommandSource>, ignored: String?) {
		val build = BuildInfo.current
		val database = RoomDataService.database
		context.source.sendFeedback(Component.literal("FunnyMap ${build.version}"))
		context.source.sendFeedback(Component.literal("Build: ${build.mode.name.lowercase()} commit=${build.commit}"))
		context.source.sendFeedback(Component.literal("Minecraft: ${build.minecraftVersion}"))
		context.source.sendFeedback(Component.literal("Database: ${database.cacheIdentity}"))
		context.source.sendFeedback(Component.literal("Rooms: ${database.definitions.size}"))
	}

	private fun requireCaptures(roomId: RoomId) = repository.findByRoom(roomId).also {
		require(it.isNotEmpty()) { "No raw captures for ${roomId.value}" }
	}

	private fun parseFootprint(value: String): RoomFootprint {
		val cells = value.split(';').map { token ->
			val coordinates = token.split(',')
			require(coordinates.size == 2) {
				"Footprint must use column,row cells separated by semicolons, for example 0,0;1,0;0,1"
			}
			GridPosition(
				column = coordinates[0].toIntOrNull()
					?: throw IllegalArgumentException("Invalid footprint column '${coordinates[0]}'"),
				row = coordinates[1].toIntOrNull()
					?: throw IllegalArgumentException("Invalid footprint row '${coordinates[1]}'"),
			)
		}
		return RoomGeometry.normalizeFootprint(RoomFootprint.of(cells))
	}

	private fun parseRoomType(value: String): RoomType {
		val type = RoomType.entries.firstOrNull { it.name == value.uppercase(Locale.ROOT) }
			?: throw IllegalArgumentException("Unknown room type '$value': ${RoomType.entries.joinToString { it.name }}")
		require(type != RoomType.UNKNOWN) { "Capture room type must be explicit, not UNKNOWN" }
		return type
	}

	private fun parseRotation(degrees: Int): RoomRotation = RoomRotation.entries.firstOrNull { it.degrees == degrees }
		?: throw IllegalArgumentException("Rotation must be one of 0, 90, 180, or 270")

	private fun roomId(context: CommandContext<FabricClientCommandSource>): RoomId =
		RoomId(StringArgumentType.getString(context, "roomId"))

	private fun execute(
		context: CommandContext<FabricClientCommandSource>,
		notes: String?,
		command: (CommandContext<FabricClientCommandSource>, String?) -> Unit,
	): Int = try {
		command(context, notes)
		Command.SINGLE_SUCCESS
	} catch (exception: Exception) {
		FunnyMap.LOGGER.warn("Room capture command failed", exception)
		context.source.sendError(Component.literal(exception.message ?: "Room capture command failed"))
		0
	}

	private fun writeAtomically(target: Path, value: String) {
		val temporary = Files.createTempFile(target.parent, ".room-", ".tmp")
		try {
			Files.writeString(temporary, value, StandardCharsets.UTF_8)
			try {
				Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
			} catch (_: java.nio.file.AtomicMoveNotSupportedException) {
				Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
			}
		} finally {
			Files.deleteIfExists(temporary)
		}
	}

	private fun formatPercent(value: Double): String = "%.1f%%".format(Locale.ROOT, value * 100.0)

	private const val ROOT_COMMAND = "fmapdev"
	private const val OUTPUT_DIRECTORY = "funnymap-room-captures"
	private const val RAW_DIRECTORY = "raw"
	private const val REPORTS_DIRECTORY = "reports"
	private const val EXPORTS_DIRECTORY = "exports"
	private const val MAX_LISTED_CAPTURES = 20
}
