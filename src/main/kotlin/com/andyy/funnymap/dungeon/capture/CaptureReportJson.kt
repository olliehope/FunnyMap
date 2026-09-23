package com.andyy.funnymap.dungeon.capture

import com.andyy.funnymap.dungeon.room.LocalBlockPosition
import com.andyy.funnymap.dungeon.room.StructuralSample
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/** Deterministic machine-readable reports retained beside human review output. */
object CaptureReportJson {
	private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()

	fun encodeComparison(comparison: CaptureComparison): String = gson.toJson(
		comparisonElement(comparison).apply {
			addProperty("reportSchemaVersion", REPORT_SCHEMA_VERSION)
			addProperty("kind", "capture-comparison")
		},
	) + "\n"

	fun encodeFinalization(report: FinalizationReport): String = gson.toJson(
		JsonObject().apply {
			addProperty("reportSchemaVersion", REPORT_SCHEMA_VERSION)
			addProperty("kind", "capture-finalization")
			addProperty("roomId", report.comparison.roomId.value)
			addProperty("status", report.status.name)
			addProperty("fingerprintPolicyVersion", report.fingerprintPolicyVersion)
			report.finalDigest?.let { addProperty("finalDigest", it) }
			add("contributingCaptureIds", strings(report.contributingCaptureIds.sorted()))
			add("acceptedSamples", samples(report.acceptedSamples))
			add("exclusions", JsonArray().also { array ->
				report.exclusions.forEach { exclusion ->
					array.add(JsonObject().apply {
						exclusion.position?.let { add("position", position(it)) }
						addProperty("reason", exclusion.reason.name)
						addProperty("detail", exclusion.detail)
					})
				}
			})
			add("warnings", strings(report.warnings))
			add("comparison", comparisonElement(report.comparison))
			add(
				"candidateRoomDatabase",
				report.candidateJson?.let(JsonParser::parseString) ?: JsonNull.INSTANCE,
			)
		},
	) + "\n"

	private fun comparisonElement(comparison: CaptureComparison): JsonObject = JsonObject().apply {
		addProperty("roomId", comparison.roomId.value)
		add("captureIds", strings(comparison.captureIds.sorted()))
		addProperty("minimumCoverage", comparison.coverage)
		add("coverageByCapture", JsonObject().also { coverage ->
			comparison.coverageByCapture.forEach(coverage::addProperty)
		})
		add("stableSamples", samples(comparison.stableSamples))
		add("changedBlockIdentifiers", JsonArray().also { array ->
			comparison.changedBlockIdentifiers.forEach { change ->
				array.add(JsonObject().apply {
					add("position", position(change.position))
					add("observations", JsonObject().also { observations ->
						change.observations.toSortedMap().forEach { (blockId, captureIds) ->
							observations.add(blockId, strings(captureIds.sorted()))
						}
					})
				})
			}
		})
		add("changedProperties", JsonArray().also { array ->
			comparison.changedProperties.forEach { change ->
				array.add(JsonObject().apply {
					add("position", position(change.position))
					addProperty("block", change.blockId)
					addProperty("property", change.property)
					add("observations", JsonArray().also { observations ->
						change.observations.entries.sortedWith(compareBy(nullsFirst()) { it.key }).forEach { (value, ids) ->
							observations.add(JsonObject().apply {
								if (value == null) add("value", JsonNull.INSTANCE) else addProperty("value", value)
								add("captureIds", strings(ids.sorted()))
							})
						}
					})
				})
			}
		})
		add("presenceChanges", JsonArray().also { array ->
			comparison.presenceChanges.forEach { change ->
				array.add(JsonObject().apply {
					add("position", position(change.position))
					add("presentCaptureIds", strings(change.presentCaptureIds.sorted()))
					add("absentCaptureIds", strings(change.absentCaptureIds.sorted()))
				})
			}
		})
		add("unavailablePositions", JsonArray().also { array ->
			comparison.unavailablePositions.forEach { unavailable ->
				array.add(JsonObject().apply {
					add("position", position(unavailable.position))
					add("availableCaptureIds", strings(unavailable.availableCaptureIds.sorted()))
					add("unavailableCaptureIds", strings(unavailable.unavailableCaptureIds.sorted()))
				})
			}
		})
		add("insufficientObservations", JsonArray().also { array ->
			comparison.insufficientObservations.forEach { insufficient ->
				array.add(JsonObject().apply {
					add("position", position(insufficient.position))
					addProperty("observationCount", insufficient.observationCount)
					addProperty("requiredCount", insufficient.requiredCount)
				})
			}
		})
		add("policyExclusions", JsonArray().also { array ->
			comparison.policyExclusions.forEach { captured ->
				array.add(JsonObject().apply {
					addProperty("captureId", captured.captureId)
					add("sample", sample(captured.exclusion.sample))
					addProperty("reason", captured.exclusion.reason)
					add("droppedProperties", properties(captured.exclusion.droppedProperties))
				})
			}
		})
	}

	private fun samples(values: Collection<StructuralSample>): JsonArray = JsonArray().also { array ->
		values.forEach { array.add(sample(it)) }
	}

	private fun sample(value: StructuralSample): JsonObject = JsonObject().apply {
		add("pos", position(value.position))
		addProperty("block", value.blockId)
		add("properties", properties(value.properties))
	}

	private fun properties(values: Map<String, String>): JsonObject = JsonObject().apply {
		values.toSortedMap().forEach(::addProperty)
	}

	private fun position(value: LocalBlockPosition): JsonArray = JsonArray().apply {
		add(value.x)
		add(value.y)
		add(value.z)
	}

	private fun strings(values: Collection<String>): JsonArray = JsonArray().also { array -> values.forEach(array::add) }

	private const val REPORT_SCHEMA_VERSION = 1
}
