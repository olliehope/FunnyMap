package com.andyy.funnymap.client.dungeon

import com.andyy.funnymap.FunnyMap
import com.andyy.funnymap.client.detection.DungeonDetectionService
import com.andyy.funnymap.detection.DetectionStatus
import com.andyy.funnymap.dungeon.match.CandidateView
import com.andyy.funnymap.dungeon.match.CandidateViewKey
import com.andyy.funnymap.dungeon.match.MatchCacheStatus
import com.andyy.funnymap.dungeon.match.MatchDataDependency
import com.andyy.funnymap.dungeon.match.RoomMatchCache
import com.andyy.funnymap.dungeon.match.RoomMatchResult
import com.andyy.funnymap.dungeon.match.RoomMatcher
import com.andyy.funnymap.dungeon.match.StructuralAnchorIndex
import com.andyy.funnymap.dungeon.model.DungeonGrid
import com.andyy.funnymap.dungeon.model.DungeonScannerDebug
import com.andyy.funnymap.dungeon.model.DungeonSnapshot
import com.andyy.funnymap.dungeon.model.GridPosition
import com.andyy.funnymap.dungeon.model.RecognitionUnknownReason
import com.andyy.funnymap.dungeon.model.RoomScanDebug
import com.andyy.funnymap.dungeon.model.ScannerCacheState
import com.andyy.funnymap.dungeon.model.ScannerCounters
import com.andyy.funnymap.dungeon.model.ScannerLifecycleState
import com.andyy.funnymap.dungeon.room.CoverageRegion
import com.andyy.funnymap.dungeon.room.GameDataVersion
import com.andyy.funnymap.dungeon.room.HorizontalDatum
import com.andyy.funnymap.dungeon.room.LocalBlockBounds
import com.andyy.funnymap.dungeon.room.LocalBlockPosition
import com.andyy.funnymap.dungeon.room.ObservationCoverage
import com.andyy.funnymap.dungeon.room.ObservationProvenance
import com.andyy.funnymap.dungeon.room.ObservationSource
import com.andyy.funnymap.dungeon.room.PolicyDecision
import com.andyy.funnymap.dungeon.room.RoomLocalDatum
import com.andyy.funnymap.dungeon.room.RoomObservation
import com.andyy.funnymap.dungeon.room.StructuralSample
import com.andyy.funnymap.dungeon.room.UnavailableReason
import com.andyy.funnymap.dungeon.room.UnavailableRegion
import com.andyy.funnymap.dungeon.room.VerticalDatum
import com.andyy.funnymap.dungeon.room.WorldCoordinate
import com.andyy.funnymap.dungeon.scan.BoundedWorkQueue
import com.andyy.funnymap.dungeon.scan.DungeonSnapshotAssembler
import com.andyy.funnymap.dungeon.scan.LocatedRoomMatch
import com.andyy.funnymap.dungeon.scan.RoomScannerConfig
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.SharedConstants
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.chunk.LevelChunk
import net.minecraft.world.level.chunk.status.ChunkStatus
import kotlin.math.max

/** Loaded-only, client-thread runtime room scanner. */
object RoomScannerService {
	private val config = RoomScannerConfig()
	private val assembler = DungeonSnapshotAssembler(config.lattice)
	private val anchorQueue = BoundedWorkQueue<ChunkScanTask, Long>(config.maximumQueuedWork, ChunkScanTask::chunkKey)
	private val observationQueue = BoundedWorkQueue<ObservationTask, ProposalKey>(
		config.maximumQueuedWork,
		ObservationTask::key,
	)
	private val matchQueue = BoundedWorkQueue<CompletedObservation, String>(
		config.maximumQueuedWork,
		{ it.observation.observationId },
	)
	private val matchCache = RoomMatchCache(config.maximumQueuedWork)
	private val chunkRevisions = HashMap<Long, Long>()
	private val surveyedChunks = HashMap<Long, Long>()
	private val anchorVotes = object : LinkedHashMap<ProposalKey, MutableSet<LocalBlockPosition>>() {
		override fun removeEldestEntry(eldest: MutableMap.MutableEntry<ProposalKey, MutableSet<LocalBlockPosition>>?): Boolean =
			size > config.maximumQueuedWork * ANCHOR_VOTE_GROUP_MULTIPLIER
	}
	private val discoveredProposals = LinkedHashMap<ProposalKey, CandidateView>()
	private val activeProposals = HashSet<ProposalKey>()
	private val pendingMatchProposals = HashSet<ProposalKey>()
	private val records = LinkedHashMap<ProposalKey, RuntimeMatchRecord>()
	private val counters = MutableScannerCounters()

	private lateinit var matcher: RoomMatcher
	private lateinit var anchorIndex: StructuralAnchorIndex
	private var databaseIdentity = ""
	private var initialized = false
	private var active = false
	private var activeSessionGeneration = -1L
	private var activeLevelIdentity = 0
	private var tick = 0L
	private var nextObservationRevision = 1L
	private var nextSnapshotRevision = 1L
	private var lastPublishedTick = Long.MIN_VALUE
	private var latestDebug: LatestRuntimeDebug? = null
	private var emptyDatabasePublished = false

	fun initialize() {
		check(!initialized) { "RoomScannerService is already initialized" }
		initialized = true
		rebuildDatabaseServices()
		ClientChunkEvents.CHUNK_LOAD.register(::onChunkLoad)
		ClientChunkEvents.CHUNK_UNLOAD.register(::onChunkUnload)
		ClientTickEvents.END_CLIENT_TICK.register(::onEndTick)
	}

	private fun onEndTick(client: Minecraft) {
		check(client.isSameThread) { "RoomScannerService must run on the Minecraft client thread" }
		val level = client.level
		val exchangeView = DungeonSnapshotStore.view()
		val levelIdentity = level?.let(System::identityHashCode) ?: 0
		if (exchangeView.session.generation != activeSessionGeneration || levelIdentity != activeLevelIdentity) {
			resetForSession(exchangeView.session.generation, levelIdentity, exchangeView.snapshot.revision)
		}
		if (level == null || DungeonDetectionService.context.catacombs != DetectionStatus.DETECTED) {
			if (active) {
				active = false
				clearSessionWork()
				emptyDatabasePublished = false
				publishEmpty(RecognitionUnknownReason.INSUFFICIENT_EVIDENCE, ScannerLifecycleState.INACTIVE)
			}
			return
		}

		if (RoomDataService.database.cacheIdentity != databaseIdentity) {
			rebuildDatabaseServices()
			clearSessionWork()
		}
		if (RoomDataService.database.definitions.isEmpty()) {
			active = true
			if (!emptyDatabasePublished) {
				publishEmpty(RecognitionUnknownReason.NO_DATABASE_MATCH, ScannerLifecycleState.EMPTY_DATABASE)
				emptyDatabasePublished = true
			}
			return
		}

		if (!active) {
			active = true
			emptyDatabasePublished = false
			enqueueNearbyLoadedChunks(client, level)
			publishSnapshot()
		}
		tick++
		if (tick % config.fallbackDiscoveryIntervalTicks == 0L) enqueueNearbyLoadedChunks(client, level)
		enqueueDueRevalidations(level)
		processWorldWork(level)
		processMatches()
		if (tick - lastPublishedTick >= config.fallbackDiscoveryIntervalTicks) publishSnapshot()
	}

	private fun onChunkLoad(level: ClientLevel, chunk: LevelChunk) {
		val client = Minecraft.getInstance()
		check(client.isSameThread) { "Chunk-load scanner callbacks must run on the client thread" }
		if (!active || RoomDataService.database.definitions.isEmpty() ||
			System.identityHashCode(level) != activeLevelIdentity
		) return
		val x = chunk.pos.x()
		val z = chunk.pos.z()
		val key = ChunkPos.pack(x, z)
		bumpChunkRevision(key)
		surveyedChunks.remove(key)
		invalidateChunkDependents(level, x, z)
		enqueueChunk(x, z)
	}

	private fun onChunkUnload(level: ClientLevel, chunk: LevelChunk) {
		val client = Minecraft.getInstance()
		check(client.isSameThread) { "Chunk-unload scanner callbacks must run on the client thread" }
		if (!active || RoomDataService.database.definitions.isEmpty() ||
			System.identityHashCode(level) != activeLevelIdentity
		) return
		val x = chunk.pos.x()
		val z = chunk.pos.z()
		val key = ChunkPos.pack(x, z)
		bumpChunkRevision(key)
		surveyedChunks.remove(key)
		anchorQueue.removeIf { it.chunkKey == key }
		invalidateChunkDependents(level, x, z, retry = false)
	}

	private fun invalidateChunkDependents(level: ClientLevel, chunkX: Int, chunkZ: Int, retry: Boolean = true) {
		val dependency = dependencyId(chunkX, chunkZ)
		val affected = discoveredProposals.filter { (proposal, view) ->
			proposalTouchesChunk(proposal, view, chunkX, chunkZ)
		}.keys.toSet()
		observationQueue.removeIf { it.key in affected }
		matchQueue.removeIf { completed -> completed.dependencies.any { it.id == dependency } }
		activeProposals.removeAll(affected)
		pendingMatchProposals.removeAll(affected)
		matchCache.invalidateDependency(dependency)
		val changed = records.keys.removeAll(affected)
		if (retry) affected.forEach { proposal ->
			discoveredProposals[proposal]?.let { enqueueObservation(level, proposal, it) }
		}
		if (changed) publishSnapshot()
	}

	private fun processWorldWork(level: ClientLevel) {
		val started = System.nanoTime()
		var remaining = config.blockReadsPerTick
		val observationAllowance = if (anchorQueue.size > 0) max(1, remaining / 2) else remaining
		var observationRemaining = observationAllowance
		while (observationRemaining > 0) {
			val task = observationQueue.poll() ?: break
			val consumed = processObservationTask(level, task, observationRemaining).coerceAtLeast(1)
			observationRemaining -= consumed
			remaining -= consumed
		}
		while (remaining > 0) {
			val task = anchorQueue.poll() ?: break
			val consumed = processChunkTask(level, task, remaining).coerceAtLeast(1)
			remaining -= consumed
		}
		counters.scanTimeNanos += System.nanoTime() - started
	}

	private fun processChunkTask(level: ClientLevel, task: ChunkScanTask, budget: Int): Int {
		val expectedRevision = chunkRevisions.getOrDefault(task.chunkKey, 0L)
		if (surveyedChunks[task.chunkKey] == expectedRevision) return 0
		val chunk = level.chunkSource.getChunk(task.chunkX, task.chunkZ, ChunkStatus.FULL, false)
		if (chunk == null) {
			counters.chunksUnavailable++
			return 1
		}
		var reads = 0
		val sections = chunk.sections
		try {
			while (task.sectionIndex < sections.size && reads < budget) {
				val section = sections[task.sectionIndex]
				if (section.hasOnlyAir()) {
					task.sectionIndex++
					task.blockIndex = 0
					continue
				}
				if (!section.maybeHas { state ->
					anchorIndex.containsBlock(BuiltInRegistries.BLOCK.getKey(state.block).toString())
				}) {
					task.sectionIndex++
					task.blockIndex = 0
					continue
				}
				val sectionY = level.getSectionYFromSectionIndex(task.sectionIndex)
				while (task.blockIndex < BLOCKS_PER_SECTION && reads < budget) {
					val index = task.blockIndex++
					val localX = index and CHUNK_MASK
					val localZ = (index shr 4) and CHUNK_MASK
					val localY = (index shr 8) and CHUNK_MASK
					val state = section.getBlockState(localX, localY, localZ)
					reads++
					val blockId = BuiltInRegistries.BLOCK.getKey(state.block).toString()
					if (!anchorIndex.containsBlock(blockId)) continue
					val world = WorldCoordinate(
						task.chunkX * CHUNK_SIZE + localX,
						sectionY * CHUNK_SIZE + localY,
						task.chunkZ * CHUNK_SIZE + localZ,
					)
					processAnchorBlock(level, state, blockId, world)
				}
				if (task.blockIndex == BLOCKS_PER_SECTION) {
					task.sectionIndex++
					task.blockIndex = 0
				}
			}
		} catch (error: Exception) {
			FunnyMap.LOGGER.debug("Could not scan loaded client chunk {},{}", task.chunkX, task.chunkZ, error)
			return max(1, reads)
		}
		if (task.sectionIndex >= sections.size) surveyedChunks[task.chunkKey] = expectedRevision
		else anchorQueue.enqueue(task)
		return reads
	}

	private fun processAnchorBlock(
		level: ClientLevel,
		state: BlockState,
		blockId: String,
		world: WorldCoordinate,
	) {
		val raw = StructuralSample(LocalBlockPosition(0, 0, 0), blockId, snapshotProperties(state))
		val accepted = (RoomDataService.database.candidateIndex.policy.apply(raw) as? PolicyDecision.Accepted)?.sample
			?: return
		for (reference in anchorIndex.referencesFor(
			accepted.blockId,
			accepted.properties,
			config.maximumAnchorLookupsPerBlock,
		)) {
			val origin = WorldCoordinate(
				world.x - reference.localPosition.x,
				world.y - reference.localPosition.y,
				world.z - reference.localPosition.z,
			)
			val key = ProposalKey(reference.candidate.key, origin)
			if (key in discoveredProposals) continue
			val votes = anchorVotes.getOrPut(key, ::LinkedHashSet)
			votes += reference.localPosition
			if (votes.size >= config.minimumAnchorVotes) {
				if (!makeProposalCapacity()) continue
				discoveredProposals[key] = reference.candidate
				if (!enqueueObservation(level, key, reference.candidate)) {
					discoveredProposals.remove(key)
					anchorVotes.remove(key)
				}
			}
		}
	}

	private fun makeProposalCapacity(): Boolean {
		if (discoveredProposals.size < config.maximumQueuedWork) return true
		val disposable = discoveredProposals.keys.firstOrNull { key ->
			key !in activeProposals && key !in pendingMatchProposals &&
				records[key]?.located?.result is RoomMatchResult.Unknown
		}
			?: return false
		discoveredProposals.remove(disposable)
		anchorVotes.remove(disposable)
		records.remove(disposable)
		return true
	}

	private fun enqueueObservation(level: ClientLevel, key: ProposalKey, view: CandidateView): Boolean {
		if (key in activeProposals || key in pendingMatchProposals ||
			view.bounds.volume > config.maximumObservationVolume
		) return false
		val worldMinY = key.origin.y + view.bounds.min.y
		val worldMaxY = key.origin.y + view.bounds.max.y
		if (worldMinY < level.minY || worldMaxY >= level.maxY) return false
		val task = ObservationTask.create(key, view)
		if (!observationQueue.enqueue(task)) return false
		activeProposals += key
		counters.roomsQueued++
		return true
	}

	private fun processObservationTask(level: ClientLevel, task: ObservationTask, budget: Int): Int {
		var reads = 0
		val position = BlockPos.MutableBlockPos()
		while (task.regionIndex < task.regions.size && reads < budget) {
			val region = task.regions[task.regionIndex]
			val chunk = level.chunkSource.getChunk(region.chunkX, region.chunkZ, ChunkStatus.FULL, false)
			if (chunk == null) {
				task.discardCurrentRegion(UnavailableReason.CHUNK_UNAVAILABLE)
				counters.chunksUnavailable++
				reads++
				continue
			}
			try {
				while (task.regionBlockIndex < region.volume && reads < budget) {
					val local = region.positionAt(task.regionBlockIndex++)
					position.set(task.key.origin.x + local.x, task.key.origin.y + local.y, task.key.origin.z + local.z)
					val sample = snapshotPolicySample(chunk.getBlockState(position), local)
					if (sample != null) task.currentRegionSamples += sample
					reads++
				}
			} catch (error: Exception) {
				FunnyMap.LOGGER.debug(
					"Could not read candidate room region from loaded chunk {},{}",
					region.chunkX,
					region.chunkZ,
					error,
				)
				task.discardCurrentRegion(UnavailableReason.CLIENT_READ_FAILED)
				continue
			}
			if (task.regionBlockIndex == region.volume) task.acceptCurrentRegion()
		}
		if (task.regionIndex < task.regions.size) {
			observationQueue.enqueue(task)
			return reads
		}

		activeProposals -= task.key
		counters.roomsScanned++
		val observation = task.toObservation(
			sessionGeneration = activeSessionGeneration,
			revision = nextObservationRevision++,
		)
		counters.observationsCreated++
		val completed = CompletedObservation(
			key = task.key,
			view = task.view,
			observation = observation,
			dependencies = task.regions.map { region ->
				MatchDataDependency(
					dependencyId(region.chunkX, region.chunkZ),
					chunkRevisions.getOrDefault(ChunkPos.pack(region.chunkX, region.chunkZ), 0L),
				)
			}.distinct(),
		)
		if (!matchQueue.enqueue(completed)) {
			counters.failedMatches++
			discoveredProposals.remove(task.key)
			anchorVotes.remove(task.key)
			FunnyMap.LOGGER.warn("Room match queue is full; dropping observation {}", observation.observationId)
		} else pendingMatchProposals += task.key
		return reads
	}

	private fun processMatches() {
		repeat(config.matchesPerTick) {
			val completed = matchQueue.poll() ?: return
			pendingMatchProposals -= completed.key
			if (completed.observation.worldSessionGeneration != activeSessionGeneration) return@repeat
			val started = System.nanoTime()
			val cached = matchCache.resolve(
				observation = completed.observation,
				databaseIdentity = databaseIdentity,
				dependencies = completed.dependencies,
			) { matcher.match(completed.observation) }
			counters.matchTimeNanos += System.nanoTime() - started
			if (cached.result.worldSessionGeneration != activeSessionGeneration ||
				cached.result.observationRevision != completed.observation.revision
			) return@repeat

			counters.shortlistCandidates += cached.result.diagnostics.shortlistSize
			when (cached.status) {
				MatchCacheStatus.MISS -> counters.cacheMisses++
				MatchCacheStatus.EXACT_HIT, MatchCacheStatus.FINGERPRINT_HIT -> counters.cacheHits++
			}
			when (cached.result) {
				is RoomMatchResult.Known -> counters.successfulMatches++
				is RoomMatchResult.Unknown -> counters.failedMatches++
			}
			val record = RuntimeMatchRecord(
				located = LocatedRoomMatch(
					worldOrigin = completed.key.origin,
					observedFootprint = completed.view.footprint,
					result = cached.result,
					anchorVoteCount = anchorVotes[completed.key]?.size ?: config.minimumAnchorVotes,
				),
				view = completed.view,
				dependencies = completed.dependencies,
				cacheStatus = cached.status,
				completedTick = tick,
			)
			records[completed.key] = record
			latestDebug = LatestRuntimeDebug(completed.key.origin, record)
			publishSnapshot()
		}
	}

	private fun enqueueDueRevalidations(level: ClientLevel) {
		for ((key, record) in records.toMap()) {
			val interval = if (record.located.result is RoomMatchResult.Known) {
				config.successfulRevalidationTicks
			} else {
				config.failedRetryTicks
			}
			if (tick - record.completedTick >= interval) enqueueObservation(level, key, record.view)
		}
	}

	private fun enqueueNearbyLoadedChunks(client: Minecraft, level: ClientLevel) {
		val center = client.player?.chunkPosition() ?: return
		for (x in center.x() - config.chunkDiscoveryRadius..center.x() + config.chunkDiscoveryRadius) {
			for (z in center.z() - config.chunkDiscoveryRadius..center.z() + config.chunkDiscoveryRadius) {
				if (level.chunkSource.getChunk(x, z, ChunkStatus.FULL, false) != null) enqueueChunk(x, z)
			}
		}
	}

	private fun enqueueChunk(x: Int, z: Int) {
		val key = ChunkPos.pack(x, z)
		val revision = chunkRevisions.getOrDefault(key, 0L)
		if (surveyedChunks[key] == revision) return
		anchorQueue.enqueue(ChunkScanTask(x, z))
	}

	private fun publishSnapshot() {
		val session = DungeonSnapshotStore.session()
		if (session.generation != activeSessionGeneration) return
		val revision = max(nextSnapshotRevision, DungeonSnapshotStore.snapshot().revision + 1)
		val snapshot = assembler.assemble(
			revision = revision,
			locatedMatches = records.values.map(RuntimeMatchRecord::located),
			debug = buildDebug(),
		)
		if (DungeonSnapshotStore.publish(session, snapshot)) {
			nextSnapshotRevision = revision + 1
			lastPublishedTick = tick
		}
	}

	private fun publishEmpty(reason: RecognitionUnknownReason, lifecycle: ScannerLifecycleState) {
		val session = DungeonSnapshotStore.session()
		if (session.generation != activeSessionGeneration) return
		val revision = max(nextSnapshotRevision, DungeonSnapshotStore.snapshot().revision + 1)
		val snapshot = DungeonSnapshot(
			revision = revision,
			grid = DungeonGrid(bounds = config.lattice.logicalBounds),
			failureReason = reason,
			scannerDebug = buildDebug(lifecycle),
		)
		if (DungeonSnapshotStore.publish(session, snapshot)) {
			nextSnapshotRevision = revision + 1
			lastPublishedTick = tick
		}
	}

	private fun buildDebug(forcedLifecycle: ScannerLifecycleState? = null): DungeonScannerDebug {
		val lifecycle = forcedLifecycle ?: when {
			!active -> ScannerLifecycleState.INACTIVE
			RoomDataService.database.definitions.isEmpty() -> ScannerLifecycleState.EMPTY_DATABASE
			anchorQueue.size + observationQueue.size + matchQueue.size > 0 -> ScannerLifecycleState.SCANNING
			records.values.any { it.located.result is RoomMatchResult.Known } -> ScannerLifecycleState.READY
			else -> ScannerLifecycleState.DISCOVERING
		}
		return DungeonScannerDebug(
			lifecycle = lifecycle,
			databaseRoomCount = RoomDataService.database.definitions.size,
			queuedWork = anchorQueue.size + observationQueue.size + matchQueue.size,
			counters = counters.snapshot(),
			latestRoom = latestDebug?.let { it.toModel(logicalCellFor(it.origin)) },
		)
	}

	private fun logicalCellFor(origin: WorldCoordinate): GridPosition? {
		val known = records.values.filter { it.located.result is RoomMatchResult.Known }
		if (known.isEmpty()) return null
		val baseX = known.minOf { it.located.worldOrigin.x }
		val baseZ = known.minOf { it.located.worldOrigin.z }
		val deltaX = origin.x - baseX
		val deltaZ = origin.z - baseZ
		if (deltaX % config.lattice.cellSpacingBlocks != 0 || deltaZ % config.lattice.cellSpacingBlocks != 0) return null
		return GridPosition(deltaX / config.lattice.cellSpacingBlocks, deltaZ / config.lattice.cellSpacingBlocks)
			.takeIf { it in config.lattice.logicalBounds }
	}

	private fun rebuildDatabaseServices() {
		val database = RoomDataService.database
		databaseIdentity = database.cacheIdentity
		matcher = RoomMatcher(database)
		anchorIndex = StructuralAnchorIndex.build(
			database.candidateIndex,
			config.maximumAnchorSignatureReferences,
		)
		matchCache.retainDatabase(databaseIdentity)
	}

	private fun resetForSession(generation: Long, levelIdentity: Int, currentRevision: Long) {
		clearSessionWork()
		active = false
		activeSessionGeneration = generation
		activeLevelIdentity = levelIdentity
		tick = 0
		nextObservationRevision = 1
		nextSnapshotRevision = currentRevision + 1
		lastPublishedTick = Long.MIN_VALUE
		emptyDatabasePublished = false
		matchCache.retainSession(generation)
		counters.reset()
	}

	private fun clearSessionWork() {
		anchorQueue.clear()
		observationQueue.clear()
		matchQueue.clear()
		chunkRevisions.clear()
		surveyedChunks.clear()
		anchorVotes.clear()
		discoveredProposals.clear()
		activeProposals.clear()
		pendingMatchProposals.clear()
		records.clear()
		latestDebug = null
	}

	private fun snapshotPolicySample(state: BlockState, position: LocalBlockPosition): StructuralSample? {
		val raw = StructuralSample(
			position,
			BuiltInRegistries.BLOCK.getKey(state.block).toString(),
			snapshotProperties(state),
		)
		return (RoomDataService.database.candidateIndex.policy.apply(raw) as? PolicyDecision.Accepted)?.sample
	}

	private fun snapshotProperties(state: BlockState): Map<String, String> =
		state.values.iterator().asSequence().associate { value -> value.property().name to value.valueName() }

	private fun bumpChunkRevision(key: Long) {
		chunkRevisions[key] = chunkRevisions.getOrDefault(key, 0L) + 1
	}

	private data class ProposalKey(
		val candidate: CandidateViewKey,
		val origin: WorldCoordinate,
	)

	private data class ChunkScanTask(
		val chunkX: Int,
		val chunkZ: Int,
		var sectionIndex: Int = 0,
		var blockIndex: Int = 0,
	) {
		val chunkKey: Long = ChunkPos.pack(chunkX, chunkZ)
	}

	private data class RuntimeRegion(
		val coverage: CoverageRegion,
		val chunkX: Int,
		val chunkZ: Int,
	) {
		val volume: Int = coverage.bounds.volume.toInt()

		fun positionAt(index: Int): LocalBlockPosition {
			val width = coverage.bounds.width
			val depth = coverage.bounds.depth
			val xOffset = index % width
			val remainder = index / width
			val zOffset = remainder % depth
			val yOffset = remainder / depth
			return LocalBlockPosition(
				coverage.bounds.min.x + xOffset,
				coverage.bounds.min.y + yOffset,
				coverage.bounds.min.z + zOffset,
			)
		}
	}

	private class ObservationTask private constructor(
		val key: ProposalKey,
		val view: CandidateView,
		val regions: List<RuntimeRegion>,
	) {
		var regionIndex = 0
		var regionBlockIndex = 0
		val currentRegionSamples = ArrayList<StructuralSample>()
		private val samples = ArrayList<StructuralSample>()
		private val available = ArrayList<CoverageRegion>()
		private val unavailable = ArrayList<UnavailableRegion>()

		fun acceptCurrentRegion() {
			val region = regions[regionIndex]
			samples += currentRegionSamples
			available += region.coverage
			advanceRegion()
		}

		fun discardCurrentRegion(reason: UnavailableReason) {
			val region = regions[regionIndex]
			unavailable += UnavailableRegion(region.coverage, reason)
			advanceRegion()
		}

		fun toObservation(sessionGeneration: Long, revision: Long): RoomObservation {
			val version = SharedConstants.getCurrentVersion()
			val dataVersion = version.dataVersion()
			return RoomObservation(
				observationId = observationId(key, revision),
				worldSessionGeneration = sessionGeneration,
				revision = revision,
				gameDataVersion = GameDataVersion(version.id(), dataVersion.version(), dataVersion.series()),
				fingerprintPolicyVersion = view.fingerprint.policyVersion,
				footprint = view.footprint,
				bounds = view.bounds,
				datum = RoomLocalDatum(
					worldOrigin = key.origin,
					verticalDatum = VerticalDatum.WORLD_ORIGIN_Y,
					horizontalDatum = HorizontalDatum.NORTH_WEST_FOOTPRINT_BOUNDARY,
				),
				samples = samples,
				coverage = ObservationCoverage(
					availableRegions = available,
					unavailableRegions = unavailable,
					expectedPositions = view.samples.map(StructuralSample::position),
				),
				provenance = ObservationProvenance(
					source = ObservationSource.RUNTIME_SCANNER,
					attributes = mapOf(
						"candidateRoom" to key.candidate.roomId.value,
						"candidateFingerprint" to key.candidate.fingerprintId,
						"candidateRotation" to key.candidate.rotation.degrees.toString(),
					),
				),
			)
		}

		private fun advanceRegion() {
			regionIndex++
			regionBlockIndex = 0
			currentRegionSamples.clear()
		}

		companion object {
			fun create(key: ProposalKey, view: CandidateView): ObservationTask = ObservationTask(
				key,
				view,
				partitionRegions(key.origin, view.bounds),
			)
		}
	}

	private data class CompletedObservation(
		val key: ProposalKey,
		val view: CandidateView,
		val observation: RoomObservation,
		val dependencies: List<MatchDataDependency>,
	)

	private data class RuntimeMatchRecord(
		val located: LocatedRoomMatch,
		val view: CandidateView,
		val dependencies: List<MatchDataDependency>,
		val cacheStatus: MatchCacheStatus,
		val completedTick: Long,
	)

	private data class LatestRuntimeDebug(
		val origin: WorldCoordinate,
		val record: RuntimeMatchRecord,
	) {
		fun toModel(logicalCell: GridPosition?): RoomScanDebug {
			val result = record.located.result
			val evidence = result.recognition.evidence
			return RoomScanDebug(
				observationId = result.observationId,
				logicalCell = logicalCell,
				availableCoverage = evidence.availableCoverage,
				candidateCount = result.diagnostics.shortlistSize,
				bestCandidate = result.diagnostics.bestCandidate?.let(::candidateName),
				runnerUpCandidate = result.diagnostics.runnerUpCandidate?.let(::candidateName),
				score = evidence.candidateScore,
				margin = evidence.scoreMargin,
				rotation = evidence.candidateRotation,
				failureReason = (result as? RoomMatchResult.Unknown)?.reason,
				cacheState = when (record.cacheStatus) {
					MatchCacheStatus.EXACT_HIT -> ScannerCacheState.EXACT_HIT
					MatchCacheStatus.FINGERPRINT_HIT -> ScannerCacheState.FINGERPRINT_HIT
					MatchCacheStatus.MISS -> ScannerCacheState.MISS
				},
			)
		}
	}

	private class MutableScannerCounters {
		var roomsQueued = 0L
		var roomsScanned = 0L
		var chunksUnavailable = 0L
		var observationsCreated = 0L
		var shortlistCandidates = 0L
		var successfulMatches = 0L
		var failedMatches = 0L
		var cacheHits = 0L
		var cacheMisses = 0L
		var scanTimeNanos = 0L
		var matchTimeNanos = 0L

		fun reset() {
			roomsQueued = 0
			roomsScanned = 0
			chunksUnavailable = 0
			observationsCreated = 0
			shortlistCandidates = 0
			successfulMatches = 0
			failedMatches = 0
			cacheHits = 0
			cacheMisses = 0
			scanTimeNanos = 0
			matchTimeNanos = 0
		}

		fun snapshot(): ScannerCounters = ScannerCounters(
			roomsQueued,
			roomsScanned,
			chunksUnavailable,
			observationsCreated,
			shortlistCandidates,
			successfulMatches,
			failedMatches,
			cacheHits,
			cacheMisses,
			scanTimeNanos,
			matchTimeNanos,
		)
	}

	private fun proposalTouchesChunk(
		proposal: ProposalKey,
		view: CandidateView,
		chunkX: Int,
		chunkZ: Int,
	): Boolean {
		val minChunkX = Math.floorDiv(proposal.origin.x + view.bounds.min.x, CHUNK_SIZE)
		val maxChunkX = Math.floorDiv(proposal.origin.x + view.bounds.max.x, CHUNK_SIZE)
		val minChunkZ = Math.floorDiv(proposal.origin.z + view.bounds.min.z, CHUNK_SIZE)
		val maxChunkZ = Math.floorDiv(proposal.origin.z + view.bounds.max.z, CHUNK_SIZE)
		return chunkX in minChunkX..maxChunkX && chunkZ in minChunkZ..maxChunkZ
	}

	private fun partitionRegions(origin: WorldCoordinate, bounds: LocalBlockBounds): List<RuntimeRegion> {
		val minChunkX = Math.floorDiv(origin.x + bounds.min.x, CHUNK_SIZE)
		val maxChunkX = Math.floorDiv(origin.x + bounds.max.x, CHUNK_SIZE)
		val minChunkZ = Math.floorDiv(origin.z + bounds.min.z, CHUNK_SIZE)
		val maxChunkZ = Math.floorDiv(origin.z + bounds.max.z, CHUNK_SIZE)
		return buildList {
			for (chunkX in minChunkX..maxChunkX) for (chunkZ in minChunkZ..maxChunkZ) {
				val localMinX = maxOf(bounds.min.x, chunkX * CHUNK_SIZE - origin.x)
				val localMaxX = minOf(bounds.max.x, chunkX * CHUNK_SIZE + CHUNK_MASK - origin.x)
				val localMinZ = maxOf(bounds.min.z, chunkZ * CHUNK_SIZE - origin.z)
				val localMaxZ = minOf(bounds.max.z, chunkZ * CHUNK_SIZE + CHUNK_MASK - origin.z)
				add(
					RuntimeRegion(
						CoverageRegion(
							LocalBlockBounds(
								LocalBlockPosition(localMinX, bounds.min.y, localMinZ),
								LocalBlockPosition(localMaxX, bounds.max.y, localMaxZ),
							),
							chunkX,
							chunkZ,
						),
						chunkX,
						chunkZ,
					),
				)
			}
		}
	}

	private fun observationId(key: ProposalKey, revision: Long): String =
		"runtime-${key.origin.x}-${key.origin.y}-${key.origin.z}-${key.candidate.roomId.value.replace('/', '_')}-${key.candidate.rotation.degrees}-$revision"

	private fun dependencyId(chunkX: Int, chunkZ: Int): String = "chunk:$chunkX:$chunkZ"

	private fun candidateName(key: CandidateViewKey): String =
		"${key.roomId.value}/${key.fingerprintId}@${key.rotation.degrees}"

	private const val CHUNK_SIZE = 16
	private const val CHUNK_MASK = CHUNK_SIZE - 1
	private const val BLOCKS_PER_SECTION = CHUNK_SIZE * CHUNK_SIZE * CHUNK_SIZE
	private const val ANCHOR_VOTE_GROUP_MULTIPLIER = 4
}
