package com.andyy.funnymap.build

import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BuildInfoTest {
	@Test
	fun `development metadata is parsed and identifiable`() {
		val info = parse(
			"""
			version=0.4.0-dev+abcdef0
			baseVersion=0.4.0
			commit=abcdef0
			mode=development
			minecraftVersion=26.1.2
			""".trimIndent(),
		)

		assertEquals("0.4.0-dev+abcdef0", info.version)
		assertEquals("abcdef0", info.commit)
		assertTrue(info.isDevelopment)
	}

	@Test
	fun `release metadata is not marked as development`() {
		val info = parse(
			"""
			version=0.4.0
			baseVersion=0.4.0
			commit=abcdef0
			mode=release
			minecraftVersion=26.1.2
			""".trimIndent(),
		)

		assertEquals(BuildMode.RELEASE, info.mode)
		assertFalse(info.isDevelopment)
	}

	private fun parse(value: String): FunnyMapBuildInfo =
		BuildInfo.parse(ByteArrayInputStream(value.toByteArray(Charsets.UTF_8)))
}
