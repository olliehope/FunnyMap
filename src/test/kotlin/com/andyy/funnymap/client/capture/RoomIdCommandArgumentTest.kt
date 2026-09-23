package com.andyy.funnymap.client.capture

import com.mojang.brigadier.StringReader
import kotlin.test.Test
import kotlin.test.assertEquals

class RoomIdCommandArgumentTest {
	@Test
	fun `canonical slash-separated room id is read as one argument`() {
		val reader = StringReader("catacombs/alpha-one remaining")

		val parsed = RoomIdCommandArgument.parse(reader)

		assertEquals("catacombs/alpha-one", parsed)
		assertEquals(' ', reader.peek())
	}
}
