package com.andyy.funnymap.dungeon.room

import com.andyy.funnymap.dungeon.match.RoomCandidateIndex
import com.andyy.funnymap.dungeon.model.RoomId
import com.andyy.funnymap.dungeon.model.RoomRotation
import java.io.Reader
import java.util.Collections
import java.util.LinkedHashMap
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

data class DatabaseDiagnostic(
	val path: String,
	val message: String,
)

class RoomDatabaseValidationException(
	diagnostics: Collection<DatabaseDiagnostic>,
	cause: Throwable? = null,
) : IllegalArgumentException(
	formatDiagnostics(diagnostics),
	cause,
) {
	val diagnostics: List<DatabaseDiagnostic> = Collections.unmodifiableList(diagnostics.toList())

	init {
		require(this.diagnostics.isNotEmpty()) { "At least one database diagnostic is required" }
	}
}

private fun formatDiagnostics(diagnostics: Collection<DatabaseDiagnostic>): String = buildString {
	append("Room database validation failed")
	diagnostics.forEach { append("\n - ${it.path}: ${it.message}") }
}

class RoomDatabase private constructor(
	val schemaVersion: Int,
	val fingerprintPolicyVersion: String,
	definitions: Collection<RoomDefinition>,
) {
	private lateinit var builtCandidateIndex: RoomCandidateIndex

	val definitions: List<RoomDefinition> =
		Collections.unmodifiableList(definitions.sortedBy { it.id.value })
	val definitionsById: Map<RoomId, RoomDefinition> = Collections.unmodifiableMap(
		LinkedHashMap(this.definitions.associateBy(RoomDefinition::id)),
	)
	val cacheIdentity: String = calculateCacheIdentity(
		schemaVersion,
		fingerprintPolicyVersion,
		this.definitions,
	)
	val candidateIndex: RoomCandidateIndex
		get() {
			check(::builtCandidateIndex.isInitialized) { "Room database candidate index has not been initialized" }
			return builtCandidateIndex
		}

	operator fun get(id: RoomId): RoomDefinition? = definitionsById[id]

	operator fun get(id: String): RoomDefinition? = definitionsById[RoomId(id)]

	companion object {
		const val CURRENT_SCHEMA_VERSION = 1
		const val DEFAULT_RESOURCE_PATH = "assets/funnymap/rooms.json"

		fun create(
			schemaVersion: Int = CURRENT_SCHEMA_VERSION,
			fingerprintPolicyVersion: String = FingerprintPolicy.DEFAULT.version,
			definitions: Collection<RoomDefinition>,
			policies: Map<String, FingerprintPolicy> = defaultPolicies(),
		): RoomDatabase {
			val database = RoomDatabase(schemaVersion, fingerprintPolicyVersion, definitions)
			RoomDatabaseValidator.validate(database, policies)
			database.builtCandidateIndex = RoomCandidateIndex.build(database, policies)
			return database
		}

		fun load(
			json: String,
			policies: Map<String, FingerprintPolicy> = defaultPolicies(),
		): RoomDatabase = RoomDatabaseJson.decode(json, policies)

		fun load(
			reader: Reader,
			policies: Map<String, FingerprintPolicy> = defaultPolicies(),
		): RoomDatabase = load(reader.readText(), policies)

		fun loadResource(
			path: String = DEFAULT_RESOURCE_PATH,
			classLoader: ClassLoader = RoomDatabase::class.java.classLoader,
			policies: Map<String, FingerprintPolicy> = defaultPolicies(),
		): RoomDatabase {
			val normalizedPath = path.removePrefix("/")
			val stream = classLoader.getResourceAsStream(normalizedPath) ?: throw RoomDatabaseValidationException(
				listOf(DatabaseDiagnostic("$", "Room database resource '$normalizedPath' was not found")),
			)
			return stream.bufferedReader(Charsets.UTF_8).use { load(it, policies) }
		}

		fun defaultPolicies(): Map<String, FingerprintPolicy> =
			mapOf(FingerprintPolicy.DEFAULT.version to FingerprintPolicy.DEFAULT)

		internal fun parsed(
			schemaVersion: Int,
			fingerprintPolicyVersion: String,
			definitions: Collection<RoomDefinition>,
			policies: Map<String, FingerprintPolicy>,
		): RoomDatabase = create(schemaVersion, fingerprintPolicyVersion, definitions, policies)
	}
}

private fun calculateCacheIdentity(
	schemaVersion: Int,
	policyVersion: String,
	definitions: Collection<RoomDefinition>,
): String {
	val canonical = buildString {
		append(schemaVersion).append('\n')
		appendPart(policyVersion)
		definitions.sortedBy { it.id.value }.forEach { definition ->
			appendPart(definition.id.value)
			appendPart(definition.displayName)
			appendPart(definition.type.name)
			append(definition.secretCount).append(',').append(definition.cryptCount).append('\n')
			definition.footprint.localCells.sorted().forEach { cell ->
				append(cell.column).append(',').append(cell.row).append(';')
			}
			append('\n')
			appendProvenance(definition.provenance)
			definition.fingerprints.sortedBy(RoomFingerprint::id).forEach { fingerprint ->
				appendPart(fingerprint.id)
				appendPart(fingerprint.policyVersion)
				appendPart(fingerprint.digest)
				append(fingerprint.origin.x).append(',')
					.append(fingerprint.origin.y).append(',')
					.append(fingerprint.origin.z).append('\n')
				append(fingerprint.bounds.min).append('|').append(fingerprint.bounds.max).append('\n')
				appendProvenance(fingerprint.provenance)
			}
		}
	}
	val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(StandardCharsets.UTF_8))
	return "sha256:" + digest.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
}

private fun StringBuilder.appendPart(value: String) {
	append(value.length).append(':').append(value).append('\n')
}

private fun StringBuilder.appendProvenance(provenance: RoomDataProvenance?) {
	if (provenance == null) {
		append("provenance:null\n")
		return
	}
	append("provenance\n")
	provenance.gameVersions.sorted().forEach(::appendPart)
	provenance.dataVersions.sorted().forEach { append(it).append(',') }
	append('\n')
	provenance.captureIds.sorted().forEach(::appendPart)
	appendPart(provenance.notes.orEmpty())
}

private object RoomDatabaseValidator {
	fun validate(database: RoomDatabase, policies: Map<String, FingerprintPolicy>) {
		val diagnostics = mutableListOf<DatabaseDiagnostic>()
		if (database.schemaVersion != RoomDatabase.CURRENT_SCHEMA_VERSION) {
			diagnostics += DatabaseDiagnostic(
				"$.schemaVersion",
				"Unsupported schema version ${database.schemaVersion}; expected ${RoomDatabase.CURRENT_SCHEMA_VERSION}",
			)
		}
		val policy = policies[database.fingerprintPolicyVersion]
		if (policy == null) {
			diagnostics += DatabaseDiagnostic(
				"$.fingerprintPolicy",
				"Unknown fingerprint policy '${database.fingerprintPolicyVersion}'",
			)
		}

		database.definitions.groupBy { it.id }.filterValues { it.size > 1 }.forEach { (id, _) ->
			diagnostics += DatabaseDiagnostic("$.rooms", "Duplicate room id '$id'")
		}

		database.definitions.forEachIndexed { roomIndex, definition ->
			val roomPath = "$.rooms[$roomIndex]"
			definition.fingerprints.groupBy(RoomFingerprint::id).filterValues { it.size > 1 }.forEach { (id, _) ->
				diagnostics += DatabaseDiagnostic("$roomPath.fingerprints", "Duplicate fingerprint id '$id'")
			}
			definition.fingerprints.forEachIndexed { fingerprintIndex, fingerprint ->
				val fingerprintPath = "$roomPath.fingerprints[$fingerprintIndex]"
				if (fingerprint.policyVersion != database.fingerprintPolicyVersion) {
					diagnostics += DatabaseDiagnostic(
						"$fingerprintPath.policyVersion",
						"Fingerprint policy '${fingerprint.policyVersion}' does not match database policy " +
							"'${database.fingerprintPolicyVersion}'",
					)
				}
				if (policy != null) validateFingerprint(fingerprint, policy, fingerprintPath, diagnostics)
			}
			if (policy != null) detectEquivalentVariants(definition, policy, roomPath, diagnostics)
		}

		if (diagnostics.isNotEmpty()) throw RoomDatabaseValidationException(diagnostics)
	}

	private fun validateFingerprint(
		fingerprint: RoomFingerprint,
		policy: FingerprintPolicy,
		path: String,
		diagnostics: MutableList<DatabaseDiagnostic>,
	) {
		if (fingerprint.samples.size < policy.minimumSamples) {
			diagnostics += DatabaseDiagnostic(
				"$path.samples",
				"Fingerprint has ${fingerprint.samples.size} structural samples; policy requires ${policy.minimumSamples}",
			)
		}
		fingerprint.samples.forEachIndexed { index, sample ->
			when (val decision = policy.apply(sample)) {
				is PolicyDecision.Excluded -> diagnostics += DatabaseDiagnostic(
					"$path.samples[$index]",
					"Block '${sample.blockId}' is excluded by policy: ${decision.reason}",
				)
				is PolicyDecision.Accepted -> if (decision.sample != sample) {
					diagnostics += DatabaseDiagnostic(
						"$path.samples[$index].properties",
						"Properties ${decision.droppedProperties} do not participate in policy '${policy.version}'",
					)
				}
			}
		}
	}

	private fun detectEquivalentVariants(
		definition: RoomDefinition,
		policy: FingerprintPolicy,
		roomPath: String,
		diagnostics: MutableList<DatabaseDiagnostic>,
	) {
		definition.fingerprints.forEachIndexed { leftIndex, left ->
			definition.fingerprints.drop(leftIndex + 1).forEachIndexed { relativeRight, right ->
				val rightIndex = leftIndex + relativeRight + 1
				val equivalentRotation = RoomRotation.entries.firstOrNull { rotation ->
					val rotated = StructuralNormalizer.rotate(left.samples, left.bounds, rotation, policy)
					rotated.issues.isEmpty() && rotated.bounds == right.bounds && rotated.samples == right.samples
				}
				if (equivalentRotation != null) {
					diagnostics += DatabaseDiagnostic(
						"$roomPath.fingerprints[$rightIndex]",
						"Fingerprint '${right.id}' is rotation-equivalent to '${left.id}' at " +
							"${equivalentRotation.degrees} degrees; rotations must not be serialized as variants",
					)
				}
			}
		}
	}
}
