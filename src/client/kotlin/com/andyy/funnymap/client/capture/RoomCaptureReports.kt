package com.andyy.funnymap.client.capture

import com.andyy.funnymap.dungeon.capture.CaptureComparison
import com.andyy.funnymap.dungeon.capture.CaptureReportJson
import com.andyy.funnymap.dungeon.capture.FinalizationReport
import com.andyy.funnymap.dungeon.room.LocalBlockPosition
import com.andyy.funnymap.dungeon.room.StructuralSample
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64

internal object RoomCaptureReports {
	fun writeComparison(directory: Path, comparison: CaptureComparison): Path {
		Files.createDirectories(directory)
		val path = directory.resolve(fileStem(comparison.roomId.value) + ".comparison.txt")
		Files.writeString(path, formatComparison(comparison), StandardCharsets.UTF_8)
		Files.writeString(
			directory.resolve(fileStem(comparison.roomId.value) + ".comparison.json"),
			CaptureReportJson.encodeComparison(comparison),
			StandardCharsets.UTF_8,
		)
		return path
	}

	fun writeFinalization(directory: Path, report: FinalizationReport): Path {
		Files.createDirectories(directory)
		val path = directory.resolve(fileStem(report.comparison.roomId.value) + ".finalization.txt")
		Files.writeString(path, formatFinalization(report), StandardCharsets.UTF_8)
		Files.writeString(
			directory.resolve(fileStem(report.comparison.roomId.value) + ".finalization.json"),
			CaptureReportJson.encodeFinalization(report),
			StandardCharsets.UTF_8,
		)
		return path
	}

	private fun formatComparison(comparison: CaptureComparison): String = buildString {
		appendLine("room=${comparison.roomId.value}")
		appendLine("captures=${comparison.captureIds.joinToString(",")}")
		appendLine("coverage=${comparison.coverage}")
		comparison.coverageByCapture.forEach { (captureId, coverage) ->
			appendLine("coverage.$captureId=$coverage")
		}
		appendLine("stableSamples=${comparison.stableSamples.size}")
		appendLine("changedBlockIdentifiers=${comparison.changedBlockIdentifiers.size}")
		appendLine("changedProperties=${comparison.changedProperties.size}")
		appendLine("presenceChanges=${comparison.presenceChanges.size}")
		appendLine("unavailablePositions=${comparison.unavailablePositions.size}")
		appendLine("insufficientObservations=${comparison.insufficientObservations.size}")
		appendLine()
		appendLine("[stable-samples]")
		comparison.stableSamples.forEach { appendLine(sample(it)) }
		appendLine()

		appendLine("[changed-block-identifiers]")
		comparison.changedBlockIdentifiers.forEach { change ->
			append(position(change.position))
			append(' ')
			appendLine(change.observations.entries.joinToString("; ") { (block, captures) ->
				"$block=[${captures.joinToString(",")}]"
			})
		}

		appendLine("[changed-properties]")
		comparison.changedProperties.forEach { change ->
			append(position(change.position))
			append(" ${change.blockId} ${change.property} ")
			appendLine(change.observations.entries.joinToString("; ") { (value, captures) ->
				"${value ?: "<absent>"}=[${captures.joinToString(",")}]"
			})
		}

		appendLine("[presence-changes]")
		comparison.presenceChanges.forEach { change ->
			appendLine(
				"${position(change.position)} present=[${change.presentCaptureIds.joinToString(",")}] " +
					"absent=[${change.absentCaptureIds.joinToString(",")}]",
			)
		}

		appendLine("[unavailable-positions]")
		comparison.unavailablePositions.forEach { availability ->
			appendLine(
				"${position(availability.position)} available=[${availability.availableCaptureIds.joinToString(",")}] " +
					"unavailable=[${availability.unavailableCaptureIds.joinToString(",")}]",
			)
		}

		appendLine("[insufficient-observations]")
		comparison.insufficientObservations.forEach { insufficient ->
			appendLine(
				"${position(insufficient.position)} observed=${insufficient.observationCount} " +
					"required=${insufficient.requiredCount}",
			)
		}

		appendLine("[policy-exclusions]")
		comparison.policyExclusions.forEach { captured ->
			appendLine(
				"${captured.captureId} ${sample(captured.exclusion.sample)} " +
					"reason=${captured.exclusion.reason} dropped=${captured.exclusion.droppedProperties}",
			)
		}
	}

	private fun formatFinalization(report: FinalizationReport): String = buildString {
		appendLine("room=${report.comparison.roomId.value}")
		appendLine("status=${report.status}")
		appendLine("fingerprintPolicy=${report.fingerprintPolicyVersion}")
		appendLine("digest=${report.finalDigest ?: "<none>"}")
		appendLine("captures=${report.contributingCaptureIds.sorted().joinToString(",")}")
		appendLine("acceptedSamples=${report.acceptedSamples.size}")
		appendLine("exclusions=${report.exclusions.size}")
		appendLine()
		appendLine("[warnings]")
		report.warnings.forEach(::appendLine)
		appendLine()
		appendLine("[accepted-samples]")
		report.acceptedSamples.forEach { appendLine(sample(it)) }
		appendLine()
		appendLine("[exclusions]")
		report.exclusions.forEach { exclusion ->
			appendLine("${exclusion.position?.let(::position) ?: "<capture>"} ${exclusion.reason}: ${exclusion.detail}")
		}
		appendLine()
		appendLine("[candidate-room-database-json]")
		append(report.candidateJson ?: "<candidate rejected>\n")
	}

	private fun position(position: LocalBlockPosition): String =
		"${position.x},${position.y},${position.z}"

	private fun sample(sample: StructuralSample): String =
		"${position(sample.position)} ${sample.blockId}" +
			if (sample.properties.isEmpty()) "" else " ${sample.properties}"

	fun fileStem(roomId: String): String = Base64.getUrlEncoder().withoutPadding()
		.encodeToString(roomId.toByteArray(StandardCharsets.UTF_8))
}
