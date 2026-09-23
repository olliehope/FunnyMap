package com.andyy.funnymap.client.capture

import com.andyy.funnymap.FunnyMap
import com.andyy.funnymap.build.BuildInfo
import com.andyy.funnymap.client.detection.DungeonDetectionService
import com.andyy.funnymap.client.dungeon.DungeonSnapshotStore
import com.andyy.funnymap.client.dungeon.RoomDataService
import com.andyy.funnymap.client.dungeon.RoomScannerService
import com.andyy.funnymap.dungeon.capture.CaptureComparator
import com.andyy.funnymap.dungeon.capture.CaptureFinalizer
import com.andyy.funnymap.dungeon.capture.CapturePreview
import com.andyy.funnymap.dungeon.capture.CapturePreviewFormatter
import com.andyy.funnymap.dungeon.capture.CaptureRoomMetadata
import com.andyy.funnymap.dungeon.capture.FileRawCaptureRepository
import com.andyy.funnymap.dungeon.capture.FinalizationReport
import com.andyy.funnymap.dungeon.capture.RawCaptureFactory
import com.andyy.funnymap.dungeon.capture.RawCaptureJsonCodec
import com.andyy.funnymap.dungeon.capture.RawCaptureRepository
import com.andyy.funnymap.dungeon.capture.RawRoomCapture
import com.andyy.funnymap.dungeon.debug.LiveDebugReportContext
import com.andyy.funnymap.dungeon.debug.LiveDebugReportFormatter
import com.andyy.funnymap.dungeon.model.GridPosition
import com.andyy.funnymap.dungeon.model.RoomFootprint
import com.andyy.funnymap.dungeon.model.RoomId
import com.andyy.funnymap.dungeon.model.RoomRotation
import com.andyy.funnymap.dungeon.model.RoomType
import com.andyy.funnymap.dungeon.model.ScannerLifecycleState
import com.andyy.funnymap.dungeon.room.FingerprintPolicy
import com.andyy.funnymap.dungeon.room.RoomGeometry
import com.andyy.funnymap.dungeon.room.WorldCoordinate
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
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/** Explicit developer workflow for producing independently captured room data. */
object RoomCaptureTool {
	private val captureSequence = AtomicLong(System.currentTimeMillis())
	private val finalized = LinkedHashMap<RoomId, FinalizationReport>()
	private lateinit var repository: RawCaptureRepository
	private lateinit var rootDirectory: Path
	private var captureMarks = CaptureMarks()
	private var pendingCapture: PendingCapture? = null
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
				.then(scannerCommand())
				.then(roomCommand())
				.then(debugCopyCommand())
				.then(captureCommand())
				.then(captureMarkCommand())
				.then(captureHereCommand())
				.then(captureConfirmCommand())
				.then(captureCancelCommand())
				.then(capturesCommand())
				.then(compareCommand())
				.then(finalizeCommand())
				.then(exportCommand())
				.then(reloadCommand())
				.then(corpusCommand()))
		}
		FunnyMap.LOGGER.info("Room capture developer commands registered under /{}", ROOT_COMMAND)
	}

	private fun statusCommand() = literal("status").executes { context ->
		execute(context, null, ::status)
	}

	private fun scannerCommand() = literal("scanner").executes { context ->
		execute(context, null, ::scannerStatus)
	}

	private fun roomCommand() = literal("room").executes { context ->
		execute(context, null, ::currentRoom)
	}

	private fun debugCopyCommand() = literal("debugcopy").executes { context ->
		execute(context, null, ::writeDebugReport)
	}

	private fun captureMarkCommand() = literal("capturemark")
		.then(literal("min").executes { context -> execute(context, "min", ::markCaptureCorner) })
		.then(literal("max").executes { context -> execute(context, "max", ::markCaptureCorner) })
		.then(literal("show").executes { context -> execute(context, null, ::showCaptureMarks) })
		.then(literal("clear").executes { context -> execute(context, null, ::clearCaptureMarks) })

	private fun captureHereCommand() = literal("capturehere").then(
		argument("roomId", RoomIdCommandArgument).then(
			argument("displayName", StringArgumentType.string()).then(
				argument("type", StringArgumentType.word()).then(
					argument("footprint", StringArgumentType.string()).then(
						argument("secrets", IntegerArgumentType.integer(0)).then(
							argument("crypts", IntegerArgumentType.integer(0)).then(
								argument("rotation", IntegerArgumentType.integer(0, 270))
									.executes { context -> execute(context, null, ::previewCaptureHere) }
									.then(
										argument("notes", StringArgumentType.greedyString()).executes { context ->
											execute(
												context,
												StringArgumentType.getString(context, "notes"),
												::previewCaptureHere,
											)
										},
									),
							),
						),
					),
				),
			),
		),
	)

	private fun captureConfirmCommand() = literal("captureconfirm").executes { context ->
		execute(context, null, ::confirmCapture)
	}

	private fun captureCancelCommand() = literal("capturecancel").executes { context ->
		execute(context, null, ::cancelCapture)
	}

	private fun reloadCommand() = literal("reload").executes { context ->
		execute(context, null, ::reloadDatabase)
	}

	private fun corpusCommand() = literal("corpus").executes { context ->
		execute(context, null, ::corpusStatus)
	}

	private fun captureCommand() = literal("capture").then(
		argument("roomId", RoomIdCommandArgument).then(
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
			argument("roomId", RoomIdCommandArgument).executes { context ->
				execute(context, null, ::listRoomCaptures)
			},
		)

	private fun compareCommand() = literal("compare").then(
		argument("roomId", RoomIdCommandArgument).executes { context ->
			execute(context, null, ::compare)
		},
	)

	private fun finalizeCommand() = literal("finalize").then(
		argument("roomId", RoomIdCommandArgument).then(
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
		argument("roomId", RoomIdCommandArgument).executes { context ->
			execute(context, null, ::export)
		},
	)

	private fun capture(
		context: CommandContext<FabricClientCommandSource>,
		notes: String?,
	) {
		val metadata = captureMetadata(context)
		val bounds = WorldBlockBounds(
			minX = IntegerArgumentType.getInteger(context, "minX"),
			minY = IntegerArgumentType.getInteger(context, "minY"),
			minZ = IntegerArgumentType.getInteger(context, "minZ"),
			maxX = IntegerArgumentType.getInteger(context, "maxX"),
			maxY = IntegerArgumentType.getInteger(context, "maxY"),
			maxZ = IntegerArgumentType.getInteger(context, "maxZ"),
		)
		val observedRotation = parseRotation(IntegerArgumentType.getInteger(context, "rotation"))
		val prepared = prepareCapture(context, metadata, bounds, observedRotation, notes)
		repository.save(prepared.rawCapture)
		finalized.remove(metadata.roomId)

		context.source.sendFeedback(
			Component.literal(
				"Captured ${prepared.rawCapture.captureId}: ${prepared.rawCapture.samples.size} structural samples, " +
					"${prepared.rawCapture.policyExclusions.size} policy exclusions, " +
					"${formatPercent(prepared.result.observation.coverage.coverageRatio)} coverage " +
					"(${prepared.result.unavailableChunkCount} unavailable chunks, " +
					"${prepared.result.readFailedChunkCount} failed chunk reads).",
			),
		)
	}

	private fun markCaptureCorner(context: CommandContext<FabricClientCommandSource>, corner: String?) {
		val hit = context.source.client.hitResult as? BlockHitResult
			?: error("Look directly at the block that should become the $corner capture corner")
		require(hit.type == HitResult.Type.BLOCK) {
			"Look directly at a loaded block that should become the $corner capture corner"
		}
		val block = hit.blockPos
		val position = WorldCoordinate(block.x, block.y, block.z)
		val generation = DungeonSnapshotStore.session().generation
		val currentMarks = captureMarks.takeIf { it.sessionGeneration == generation }
			?: CaptureMarks(sessionGeneration = generation)
		captureMarks = when (corner) {
			"min" -> currentMarks.copy(minimum = position)
			"max" -> currentMarks.copy(maximum = position)
			else -> error("Capture corner must be min or max")
		}
		pendingCapture = null
		context.source.sendFeedback(Component.literal("Capture $corner marked at ${formatCoordinate(position)} (session $generation)."))
		if (captureMarks.complete) showCaptureMarks(context, null)
	}

	private fun showCaptureMarks(context: CommandContext<FabricClientCommandSource>, ignored: String?) {
		val marks = captureMarks
		if (marks.minimum == null && marks.maximum == null) {
			context.source.sendFeedback(Component.literal("No capture corners are marked."))
			return
		}
		context.source.sendFeedback(Component.literal("Capture marks for session ${marks.sessionGeneration}:"))
		context.source.sendFeedback(Component.literal("min=${marks.minimum?.let(::formatCoordinate) ?: "NOT SET"}"))
		context.source.sendFeedback(Component.literal("max=${marks.maximum?.let(::formatCoordinate) ?: "NOT SET"}"))
		if (!marks.complete) {
			context.source.sendFeedback(Component.literal("Look at the missing corner and run /$ROOT_COMMAND capturemark min|max."))
		}
	}

	private fun clearCaptureMarks(context: CommandContext<FabricClientCommandSource>, ignored: String?) {
		captureMarks = CaptureMarks()
		pendingCapture = null
		context.source.sendFeedback(Component.literal("Capture marks and pending preview cleared."))
	}

	private fun previewCaptureHere(context: CommandContext<FabricClientCommandSource>, notes: String?) {
		val marks = captureMarks
		val currentSession = DungeonSnapshotStore.session().generation
		require(marks.sessionGeneration == currentSession) {
			"Capture marks belong to session ${marks.sessionGeneration}; re-mark both corners in current session $currentSession"
		}
		val minimum = requireNotNull(marks.minimum) {
			"Missing minimum corner; look at it and run /$ROOT_COMMAND capturemark min"
		}
		val maximum = requireNotNull(marks.maximum) {
			"Missing maximum corner; look at it and run /$ROOT_COMMAND capturemark max"
		}
		require(minimum.x <= maximum.x && minimum.y <= maximum.y && minimum.z <= maximum.z) {
			"Marked min ${formatCoordinate(minimum)} must not exceed max ${formatCoordinate(maximum)} on any axis; re-mark the corners explicitly"
		}
		val bounds = WorldBlockBounds(minimum.x, minimum.y, minimum.z, maximum.x, maximum.y, maximum.z)
		val prepared = prepareCapture(
			context = context,
			metadata = captureMetadata(context),
			bounds = bounds,
			rotation = parseRotation(IntegerArgumentType.getInteger(context, "rotation")),
			notes = notes,
		)
		val preview = CapturePreview(
			captureId = prepared.rawCapture.captureId,
			metadata = prepared.rawCapture.metadata,
			observedRotation = requireNotNull(prepared.rawCapture.observation.observedRotation),
			worldMinimum = minimum,
			worldMaximum = maximum,
			availableChunks = prepared.result.availableChunkCount,
			unavailableChunks = prepared.result.unavailableChunkCount,
			failedChunkReads = prepared.result.readFailedChunkCount,
			coverage = prepared.result.observation.coverage.coverageRatio,
			eligibleStructuralSamples = prepared.rawCapture.samples.size,
			policyExclusions = prepared.rawCapture.policyExclusions.size,
		)
		pendingCapture = PendingCapture(currentSession, prepared.rawCapture, preview)
		CapturePreviewFormatter.lines(preview).forEach { line ->
			context.source.sendFeedback(Component.literal(line))
		}
		context.source.sendFeedback(Component.literal("This is a preview; no artifact has been written."))
		context.source.sendFeedback(Component.literal("Run /$ROOT_COMMAND captureconfirm to write it, or /$ROOT_COMMAND capturecancel."))
	}

	private fun confirmCapture(context: CommandContext<FabricClientCommandSource>, ignored: String?) {
		val pending = requireNotNull(pendingCapture) {
			"No pending capture; mark corners and run /$ROOT_COMMAND capturehere first"
		}
		val generation = DungeonSnapshotStore.session().generation
		require(pending.sessionGeneration == generation) {
			"Pending capture belongs to session ${pending.sessionGeneration}; it cannot be written in current session $generation"
		}
		repository.save(pending.rawCapture)
		finalized.remove(pending.rawCapture.metadata.roomId)
		pendingCapture = null
		captureMarks = CaptureMarks()
		context.source.sendFeedback(
			Component.literal(
				"Captured ${pending.rawCapture.captureId}: ${pending.rawCapture.samples.size} stable-policy samples at " +
					"${formatPercent(pending.preview.coverage)} coverage.",
			),
		)
	}

	private fun cancelCapture(context: CommandContext<FabricClientCommandSource>, ignored: String?) {
		val cancelled = pendingCapture != null
		pendingCapture = null
		context.source.sendFeedback(
			Component.literal(if (cancelled) "Pending capture discarded; corner marks were retained." else "No pending capture."),
		)
	}

	private fun prepareCapture(
		context: CommandContext<FabricClientCommandSource>,
		metadata: CaptureRoomMetadata,
		bounds: WorldBlockBounds,
		rotation: RoomRotation,
		notes: String?,
	): PreparedCapture {
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
				canonicalFootprint = metadata.footprint,
				observedRotation = rotation,
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
		return PreparedCapture(rawCapture, result)
	}

	private fun captureMetadata(context: CommandContext<FabricClientCommandSource>): CaptureRoomMetadata =
		CaptureRoomMetadata(
			roomId = roomId(context),
			displayName = StringArgumentType.getString(context, "displayName"),
			type = parseRoomType(StringArgumentType.getString(context, "type")),
			footprint = parseFootprint(StringArgumentType.getString(context, "footprint")),
			secretCount = IntegerArgumentType.getInteger(context, "secrets"),
			cryptCount = IntegerArgumentType.getInteger(context, "crypts"),
		)

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
		context.source.sendFeedback(
			Component.literal(
				"For rebuild-free development testing, review the export, merge it into $DEVELOPMENT_OVERLAY_FILE, then run /$ROOT_COMMAND reload.",
			),
		)
	}

	private fun status(context: CommandContext<FabricClientCommandSource>, ignored: String?) {
		val build = BuildInfo.current
		val database = RoomDataService.status()
		val scanner = RoomScannerService.status()
		context.source.sendFeedback(Component.literal("FunnyMap ${build.version}"))
		context.source.sendFeedback(Component.literal("Build: ${build.mode.name.lowercase()} commit=${build.commit}"))
		context.source.sendFeedback(Component.literal("Minecraft: ${build.minecraftVersion}"))
		context.source.sendFeedback(Component.literal("Database: ${database.databaseIdentity}"))
		context.source.sendFeedback(
			Component.literal(
				"Rooms: ${database.totalRoomCount} (${database.bundledRoomCount} bundled + ${database.overlayRoomCount} overlay); fingerprints=${database.fingerprintCount}",
			),
		)
		context.source.sendFeedback(Component.literal("Policy: ${database.policyVersion}"))
		context.source.sendFeedback(
			Component.literal("Scanner: ${scanner.lifecycle.name} active=${scanner.lifecycle != ScannerLifecycleState.INACTIVE} queued=${scanner.queuedWork}"),
		)
		if (database.lastReloadError != null) {
			context.source.sendFeedback(Component.literal("Last overlay error: ${database.lastReloadError}"))
		}
	}

	private fun scannerStatus(context: CommandContext<FabricClientCommandSource>, ignored: String?) {
		val debug = RoomScannerService.status()
		val counters = debug.counters
		context.source.sendFeedback(
			Component.literal(
				"Scanner ${debug.lifecycle.name}: queued=${debug.queuedWork}, loaded=${debug.loadedChunkCount}, surveyed=${debug.surveyedChunkCount}, proposals=${debug.discoveredProposalCount}",
			),
		)
		context.source.sendFeedback(
			Component.literal(
				"Chunk events=${counters.chunkLoadEvents}, loaded observed=${counters.loadedChunksObserved}, anchors=${counters.anchorSamples}, observations=${counters.observationsCreated}",
			),
		)
		context.source.sendFeedback(
			Component.literal(
				"Matches=${counters.successfulMatches}/${counters.failedMatches}, cache=${counters.cacheHits} hit/${counters.cacheMisses} miss, invalidations=${counters.invalidations}",
			),
		)
		context.source.sendFeedback(Component.literal(debug.statusMessage))
	}

	private fun currentRoom(context: CommandContext<FabricClientCommandSource>, ignored: String?) {
		val debug = RoomScannerService.status()
		val room = RoomScannerService.inspect(currentWorldPosition(context))
		if (room == null) {
			context.source.sendFeedback(Component.literal("No scanner candidate exists near the player."))
			context.source.sendFeedback(Component.literal(debug.statusMessage))
			context.source.sendFeedback(
				Component.literal(
					"loaded=${debug.loadedChunkCount}, surveyed=${debug.surveyedChunkCount}, anchorVoteGroups=${debug.anchorVoteGroupCount}, proposals=${debug.discoveredProposalCount}",
				),
			)
			pendingCapture?.let { pending ->
				context.source.sendFeedback(
					Component.literal(
						"Pending capture ${pending.rawCapture.captureId}: ${pending.rawCapture.samples.size} eligible samples, ${formatPercent(pending.preview.coverage)} coverage.",
					),
				)
			}
			return
		}
		context.source.sendFeedback(
			Component.literal(
				"Cell=${room.logicalCell?.let { "${it.column},${it.row}" } ?: "UNKNOWN"} origin=${room.worldOrigin?.let(::formatCoordinate) ?: "UNKNOWN"} footprint=${room.footprint?.shape?.name ?: "UNKNOWN"}",
			),
		)
		context.source.sendFeedback(
			Component.literal(
				"State=${room.observationState.name} bounds=${room.localBounds?.let { "${it.min}..${it.max}" } ?: "UNKNOWN"} coverage=${formatPercent(room.availableCoverage)} eligible=${room.eligibleSampleCount} candidates=${room.candidateCount}",
			),
		)
		context.source.sendFeedback(
			Component.literal(
				"Best=${room.bestCandidate ?: "none"} score=${formatDecimal(room.score)} runner=${room.runnerUpCandidate ?: "none"} margin=${room.margin?.let(::formatDecimal) ?: "none"}",
			),
		)
		context.source.sendFeedback(
			Component.literal(
				"Evidence matched=${room.matchedSampleCount} conflicts=${room.conflictingSampleCount} comparable=${room.comparableSampleCount} observed=${formatPercent(room.observedToDefinitionCoverage)} availableRecall=${formatPercent(room.definitionToObservedCoverage)} totalRecall=${formatPercent(room.totalDefinitionCoverage)}",
			),
		)
		context.source.sendFeedback(
			Component.literal(
				"Match=${room.matchedRoomId ?: "none"} fingerprint=${room.matchedFingerprintId ?: "none"} rotation=${room.rotation?.degrees ?: "UNKNOWN"} cache=${room.cacheState.name} failure=${room.failureReason?.name ?: "none"}",
			),
		)
		if (room.diagnosticMessage.isNotBlank()) context.source.sendFeedback(Component.literal(room.diagnosticMessage))
	}

	private fun writeDebugReport(context: CommandContext<FabricClientCommandSource>, ignored: String?) {
		val build = BuildInfo.current
		val scanner = RoomScannerService.status()
		val room = RoomScannerService.inspect(currentWorldPosition(context))
		val report = LiveDebugReportFormatter.format(
			context = LiveDebugReportContext(
				version = build.version,
				commit = build.commit,
				buildMode = build.mode.name.lowercase(Locale.ROOT),
				minecraftVersion = build.minecraftVersion,
				floor = DungeonDetectionService.context.floor.token ?: "UNKNOWN",
				sessionGeneration = DungeonSnapshotStore.session().generation,
				scanner = scanner,
			),
			room = room,
		)
		val directory = rootDirectory.resolve(REPORTS_DIRECTORY)
		Files.createDirectories(directory)
		val target = directory.resolve("live-debug-${Instant.now().toEpochMilli()}.txt")
		writeAtomically(target, report)
		context.source.sendFeedback(Component.literal("Wrote sanitized current-room diagnostic to $target"))
	}

	private fun reloadDatabase(context: CommandContext<FabricClientCommandSource>, ignored: String?) {
		val status = RoomDataService.reloadDevelopmentOverlay()
		context.source.sendFeedback(
			Component.literal(
				"Database reloaded: ${status.totalRoomCount} rooms, ${status.fingerprintCount} fingerprints, identity=${status.databaseIdentity}",
			),
		)
		context.source.sendFeedback(
			Component.literal(
				if (status.overlayLoaded) "Development overlay loaded from ${status.overlayPath}" else "No development overlay found; using bundled rooms only (${status.overlayPath}).",
			),
		)
	}

	private fun corpusStatus(context: CommandContext<FabricClientCommandSource>, ignored: String?) {
		val captures = repository.all()
		val groups = captures.groupBy { it.metadata.roomId }.toSortedMap(compareBy(RoomId::value))
		val database = RoomDataService.status()
		context.source.sendFeedback(
			Component.literal(
				"Corpus Alpha: ${groups.size}/$ALPHA_TARGET_ROOMS room ids captured; ${captures.size} raw captures; ${finalized.size} finalized this client run.",
			),
		)
		context.source.sendFeedback(
			Component.literal("Active database: ${database.totalRoomCount} rooms (${database.overlayRoomCount} development overlay)."),
		)
		if (groups.isEmpty()) {
			context.source.sendFeedback(Component.literal("No room entries have been fabricated; collect the first independent room in game."))
			return
		}
		groups.entries.take(MAX_LISTED_CORPUS_ROOMS).forEach { (id, roomCaptures) ->
			val minimumCoverage = roomCaptures.minOf { it.observation.coverage.coverageRatio }
			val averageCoverage = roomCaptures.map { it.observation.coverage.coverageRatio }.average()
			context.source.sendFeedback(
				Component.literal(
					"${id.value}: captures=${roomCaptures.size}, coverage avg/min=${formatPercent(averageCoverage)}/${formatPercent(minimumCoverage)}, finalized=${id in finalized}",
				),
			)
		}
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
		RoomId(context.getArgument("roomId", String::class.java))

	private fun currentWorldPosition(context: CommandContext<FabricClientCommandSource>): WorldCoordinate? =
		context.source.client.player?.blockPosition()?.let { position ->
			WorldCoordinate(position.x, position.y, position.z)
		}

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

	private fun formatDecimal(value: Double): String = "%.4f".format(Locale.ROOT, value)

	private fun formatCoordinate(value: WorldCoordinate): String = "${value.x},${value.y},${value.z}"

	private data class CaptureMarks(
		val sessionGeneration: Long = -1,
		val minimum: WorldCoordinate? = null,
		val maximum: WorldCoordinate? = null,
	) {
		val complete: Boolean
			get() = minimum != null && maximum != null
	}

	private data class PreparedCapture(
		val rawCapture: RawRoomCapture,
		val result: ClientCaptureResult,
	)

	private data class PendingCapture(
		val sessionGeneration: Long,
		val rawCapture: RawRoomCapture,
		val preview: CapturePreview,
	)

	private const val ROOT_COMMAND = "fmapdev"
	private const val OUTPUT_DIRECTORY = "funnymap-room-captures"
	private const val RAW_DIRECTORY = "raw"
	private const val REPORTS_DIRECTORY = "reports"
	private const val EXPORTS_DIRECTORY = "exports"
	private const val DEVELOPMENT_OVERLAY_FILE = "$OUTPUT_DIRECTORY/dev-rooms.json"
	private const val MAX_LISTED_CAPTURES = 20
	private const val MAX_LISTED_CORPUS_ROOMS = 10
	private const val ALPHA_TARGET_ROOMS = 5
}
