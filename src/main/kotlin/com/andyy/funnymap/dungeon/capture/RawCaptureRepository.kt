package com.andyy.funnymap.dungeon.capture

import com.andyy.funnymap.dungeon.model.RoomId
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Base64
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

interface RawCaptureCodec {
	fun encode(capture: RawRoomCapture): String

	fun decode(json: String, sourceDescription: String = "raw capture JSON"): RawRoomCapture
}

/** Append-only storage for immutable development captures. */
interface RawCaptureRepository {
	fun save(capture: RawRoomCapture)

	fun find(captureId: String): RawRoomCapture?

	fun findByRoom(roomId: RoomId): List<RawRoomCapture>

	fun all(): List<RawRoomCapture>
}

class DuplicateCaptureException(captureId: String) :
	IllegalArgumentException("A raw capture with id '$captureId' already exists")

class InMemoryRawCaptureRepository : RawCaptureRepository {
	private val lock = ReentrantReadWriteLock()
	private val captures = LinkedHashMap<String, RawRoomCapture>()

	override fun save(capture: RawRoomCapture) {
		lock.write {
			if (capture.captureId in captures) {
				throw DuplicateCaptureException(capture.captureId)
			}
			captures[capture.captureId] = capture
		}
	}

	override fun find(captureId: String): RawRoomCapture? = lock.read { captures[captureId] }

	override fun findByRoom(roomId: RoomId): List<RawRoomCapture> = lock.read {
		immutableList(captures.values.filter { it.metadata.roomId == roomId }.sortedBy { it.captureId })
	}

	override fun all(): List<RawRoomCapture> = lock.read {
		immutableList(captures.values.sortedBy { it.captureId })
	}
}

/**
 * Directory-backed append-only store. Its directory is selected by the client adapter and must not
 * point at bundled production resources.
 */
class FileRawCaptureRepository(
	private val directory: Path,
	private val codec: RawCaptureCodec,
) : RawCaptureRepository {
	private val lock = ReentrantReadWriteLock()

	override fun save(capture: RawRoomCapture) {
		lock.write {
			Files.createDirectories(directory)
			val target = pathFor(capture.captureId)
			if (Files.exists(target)) {
				throw DuplicateCaptureException(capture.captureId)
			}

			val temporary = Files.createTempFile(directory, ".capture-", ".tmp")
			try {
				Files.writeString(temporary, codec.encode(capture), StandardCharsets.UTF_8)
				try {
					Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE)
				} catch (_: java.nio.file.AtomicMoveNotSupportedException) {
					Files.move(temporary, target)
				}
			} finally {
				Files.deleteIfExists(temporary)
			}
		}
	}

	override fun find(captureId: String): RawRoomCapture? = lock.read {
		val path = pathFor(captureId)
		if (!Files.isRegularFile(path)) {
			null
		} else {
			load(path).also {
				require(it.captureId == captureId) {
					"Raw capture file '${path.fileName}' contains id '${it.captureId}', expected '$captureId'"
				}
			}
		}
	}

	override fun findByRoom(roomId: RoomId): List<RawRoomCapture> =
		immutableList(all().filter { it.metadata.roomId == roomId })

	override fun all(): List<RawRoomCapture> = lock.read {
		if (!Files.isDirectory(directory)) {
			return@read emptyList()
		}
		val loaded = Files.list(directory).use { paths ->
			paths
				.filter { it.fileName.toString().endsWith(FILE_SUFFIX) }
				.sorted()
				.map(::load)
				.toList()
		}
		immutableList(loaded.sortedBy { it.captureId })
	}

	private fun load(path: Path): RawRoomCapture = codec.decode(
		json = Files.readString(path, StandardCharsets.UTF_8),
		sourceDescription = path.toString(),
	)

	private fun pathFor(captureId: String): Path {
		val encoded = Base64.getUrlEncoder().withoutPadding()
			.encodeToString(captureId.toByteArray(StandardCharsets.UTF_8))
		return directory.resolve(encoded + FILE_SUFFIX)
	}

	private companion object {
		const val FILE_SUFFIX = ".fmap-capture.json"
	}
}
