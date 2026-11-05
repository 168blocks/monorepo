package net.blocks168.pgecs.serial

import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

// The standard pgecs serializers for types without a natural JSON form. The query compiler and the
// JSON Schema generator recognise them by serialName, so comparisons and indexes on these fields are
// typed correctly (an instant is a range-scannable number, not a string).

/** `Instant` as epoch milliseconds (a JSON number): range-scannable and indexable. */
object InstantAsEpochMillis : KSerializer<Instant> {
    const val SERIAL_NAME = "pgecs:InstantAsEpochMillis"
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor(SERIAL_NAME, PrimitiveKind.LONG)
    override fun serialize(encoder: Encoder, value: Instant) = encoder.encodeLong(value.toEpochMilli())
    override fun deserialize(decoder: Decoder): Instant = Instant.ofEpochMilli(decoder.decodeLong())
}

/** `UUID` as its canonical string. */
object UuidAsString : KSerializer<UUID> {
    const val SERIAL_NAME = "pgecs:UuidAsString"
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor(SERIAL_NAME, PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: UUID) = encoder.encodeString(value.toString())
    override fun deserialize(decoder: Decoder): UUID = UUID.fromString(decoder.decodeString())
}

/** `Duration` as milliseconds (a JSON number). */
object DurationAsMillis : KSerializer<Duration> {
    const val SERIAL_NAME = "pgecs:DurationAsMillis"
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor(SERIAL_NAME, PrimitiveKind.LONG)
    override fun serialize(encoder: Encoder, value: Duration) = encoder.encodeLong(value.toMillis())
    override fun deserialize(decoder: Decoder): Duration = Duration.ofMillis(decoder.decodeLong())
}
