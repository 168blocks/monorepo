package net.blocks168.pgecs.serial

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.PolymorphicKind
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.json.Json
import net.blocks168.pgecs.ir.ValueType
import net.blocks168.pgecs.ir.ValueType.Kind

/**
 * The one `Json` configuration every pgecs process uses, so all JVMs write the same shapes
 * (design doc, kotlinx.serialization integration).
 */
@OptIn(ExperimentalSerializationApi::class)
val PgecsJson: Json = Json {
    encodeDefaults = true
    explicitNulls = true
    ignoreUnknownKeys = true
    classDiscriminator = "@type"
}

/** Maps serial descriptors to IR value types. */
@OptIn(ExperimentalSerializationApi::class)
object SerialTypes {
    /** Serial name of kotlin.uuid.Uuid's built-in serializer, which writes a string. */
    private const val KOTLIN_UUID = "kotlin.uuid.Uuid"

    /**
     * The IR type of values with [descriptor], nullable if the descriptor is. Value classes are
     * their underlying type; objects, lists, maps and polymorphic values are [Kind.JSON].
     */
    fun valueType(descriptor: SerialDescriptor): ValueType {
        val d = if (descriptor.isInline) descriptor.getElementDescriptor(0) else descriptor
        val name = d.serialName.removeSuffix("?")
        val kind = when (name) {
            InstantAsEpochMillis.SERIAL_NAME -> Kind.INSTANT
            DurationAsMillis.SERIAL_NAME -> Kind.DURATION
            UuidAsString.SERIAL_NAME, KOTLIN_UUID -> Kind.UUID
            else -> kindOf(d.kind)
        }
        return ValueType(kind, descriptor.isNullable || d.isNullable)
    }

    private fun kindOf(k: SerialKind): Kind = when (k) {
        PrimitiveKind.BOOLEAN -> Kind.BOOL
        PrimitiveKind.BYTE, PrimitiveKind.SHORT, PrimitiveKind.INT, PrimitiveKind.LONG -> Kind.INT
        PrimitiveKind.FLOAT, PrimitiveKind.DOUBLE -> Kind.FLOAT
        PrimitiveKind.CHAR, PrimitiveKind.STRING, SerialKind.ENUM -> Kind.TEXT
        StructureKind.CLASS, StructureKind.OBJECT, StructureKind.LIST, StructureKind.MAP,
        is PolymorphicKind, SerialKind.CONTEXTUAL -> Kind.JSON
    }

    /** Whether values with [descriptor] are JSON objects, whose fields a query can address. */
    fun isObject(descriptor: SerialDescriptor): Boolean {
        val d = if (descriptor.isInline) descriptor.getElementDescriptor(0) else descriptor
        return d.kind == StructureKind.CLASS || d.kind == StructureKind.OBJECT
    }
}
