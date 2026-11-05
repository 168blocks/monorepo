package net.blocks168.e2e.bot

import net.blocks168.e2e.PacketReader
import net.blocks168.e2e.readModifiedUtf8

/**
 * Just enough network NBT (nameless root tag, as the protocol sends it since 1.20.2) to read text
 * components: disconnect reasons and chat. Tags become Kotlin values: numbers, String, List, Map.
 */
object Nbt {
    fun readAnyTag(r: PacketReader): Any? = readPayload(r, r.readUnsignedByte())

    private fun readPayload(r: PacketReader, type: Int): Any? = when (type) {
        0 -> null
        1 -> r.readByte().toByte()
        2 -> r.readShort().toShort()
        3 -> r.readInt()
        4 -> r.readLong()
        5 -> r.readFloat()
        6 -> r.readDouble()
        7 -> r.readBytes(r.readInt())
        8 -> r.readModifiedUtf8()
        9 -> {
            val elementType = r.readUnsignedByte()
            List(r.readInt()) { readPayload(r, elementType) }
        }
        10 -> buildMap {
            while (true) {
                val t = r.readUnsignedByte()
                if (t == 0) break
                put(r.readModifiedUtf8(), readPayload(r, t))
            }
        }
        11 -> IntArray(r.readInt()) { r.readInt() }
        12 -> LongArray(r.readInt()) { r.readLong() }
        else -> error("unknown NBT tag type $type")
    }

    /**
     * Plain text of a text component (string, compound with text/translate/extra, or list).
     * Translatable components without a client-side language show as `key[arg, ...]`.
     */
    fun componentText(tag: Any?): String = when (tag) {
        null -> ""
        is String -> tag
        is List<*> -> tag.joinToString("") { componentText(it) }
        is Map<*, *> -> buildString {
            val text = tag["text"] ?: tag[""]
            when {
                text != null -> append(text.toString())
                tag["translate"] != null -> {
                    append(tag["translate"])
                    (tag["with"] as? List<*>)?.let { args -> append(args.joinToString(", ", "[", "]") { componentText(it) }) }
                }
            }
            (tag["extra"] as? List<*>)?.forEach { append(componentText(it)) }
        }
        else -> tag.toString()
    }

    fun readComponentText(r: PacketReader): String = componentText(readAnyTag(r))
}
