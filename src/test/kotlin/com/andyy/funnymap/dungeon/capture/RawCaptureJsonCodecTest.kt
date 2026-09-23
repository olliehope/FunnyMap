package com.andyy.funnymap.dungeon.capture

import com.andyy.funnymap.dungeon.model.RoomId
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RawCaptureJsonCodecTest {
	@Test
	fun `raw capture JSON round trips deterministically`() {
		val capture = CaptureTestFixtures.capture(
			id = "capture-1",
			samples = CaptureTestFixtures.samples(
				propertiesAt = mapOf(0 to mapOf("waterlogged" to "false", "facing" to "north")),
			),
		)

		val json = RawCaptureJsonCodec.encode(capture)
		val decoded = RawCaptureJsonCodec.decode(json, "round-trip fixture")

		assertEquals(capture, decoded)
		assertEquals(json, RawCaptureJsonCodec.encode(decoded))
		assertTrue(RawCaptureIntegrity.verify(decoded))
		assertTrue("dataVersionSeries" in json)
	}

	@Test
	fun `codec rejects unknown fields and tampered contents with useful paths`() {
		val json = RawCaptureJsonCodec.encode(CaptureTestFixtures.capture("capture-2"))
		val unknown = json.replaceFirst("{", "{\n  \"surprise\": true,")
		val unknownError = assertFailsWith<RawCaptureValidationException> {
			RawCaptureJsonCodec.decode(unknown, "unknown-field.json")
		}
		assertEquals("$.surprise", unknownError.path)
		assertTrue("unknown-field.json" in unknownError.message.orEmpty())

		val tampered = json.replaceFirst("minecraft:stone_bricks", "minecraft:cobblestone")
		val digestError = assertFailsWith<RawCaptureValidationException> {
			RawCaptureJsonCodec.decode(tampered, "tampered.json")
		}
		assertEquals("$.integrityDigest", digestError.path)
	}

	@Test
	fun `file repository is append only and reloads verified captures`() {
		val directory = Files.createTempDirectory("funnymap-captures")
		val repository = FileRawCaptureRepository(directory, RawCaptureJsonCodec)
		val capture = CaptureTestFixtures.capture("capture-3")

		repository.save(capture)

		assertEquals(capture, repository.find("capture-3"))
		assertEquals(listOf(capture), repository.findByRoom(RoomId("catacombs/synthetic-room")))
		assertFailsWith<DuplicateCaptureException> { repository.save(capture) }
	}
}
