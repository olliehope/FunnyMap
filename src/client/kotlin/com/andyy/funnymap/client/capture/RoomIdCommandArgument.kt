package com.andyy.funnymap.client.capture

import com.mojang.brigadier.LiteralMessage
import com.mojang.brigadier.StringReader
import com.mojang.brigadier.arguments.ArgumentType
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType

/** Reads one whitespace-delimited room id while allowing the canonical slash separator. */
object RoomIdCommandArgument : ArgumentType<String> {
	override fun parse(reader: StringReader): String {
		val start = reader.cursor
		while (reader.canRead() && !reader.peek().isWhitespace()) reader.skip()
		if (reader.cursor == start) throw MISSING.createWithContext(reader)
		return reader.string.substring(start, reader.cursor)
	}

	override fun getExamples(): Collection<String> = EXAMPLES

	private val MISSING = SimpleCommandExceptionType(LiteralMessage("Room id must be a non-space token"))
	private val EXAMPLES = listOf("catacombs/alpha-one")
}
