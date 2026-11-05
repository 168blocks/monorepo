package dev.vxcc.alexs168.pgecs.serial

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialInfo
import kotlinx.serialization.descriptors.PolymorphicKind
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.descriptors.elementDescriptors
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// Constraints on fields of component values, enforced by Postgres through the component's JSON Schema
// (pg_jsonschema). They are @SerialInfo annotations, so the SerialDescriptor exposes them.

/** The string matches this regular expression (JSON Schema `pattern`, ECMA 262 syntax). */
@OptIn(ExperimentalSerializationApi::class)
@SerialInfo
@Target(AnnotationTarget.PROPERTY)
annotation class Pattern(val regex: String)

/** The string has at least [value] characters. */
@OptIn(ExperimentalSerializationApi::class)
@SerialInfo
@Target(AnnotationTarget.PROPERTY)
annotation class MinLength(val value: Int)

/** The string has at most [value] characters. */
@OptIn(ExperimentalSerializationApi::class)
@SerialInfo
@Target(AnnotationTarget.PROPERTY)
annotation class MaxLength(val value: Int)

/** The number is at least [value]. */
@OptIn(ExperimentalSerializationApi::class)
@SerialInfo
@Target(AnnotationTarget.PROPERTY)
annotation class Min(val value: Long)

/** The number is at most [value]. */
@OptIn(ExperimentalSerializationApi::class)
@SerialInfo
@Target(AnnotationTarget.PROPERTY)
annotation class Max(val value: Long)

/** The string has this JSON Schema `format`, such as `email` or `uuid`. */
@OptIn(ExperimentalSerializationApi::class)
@SerialInfo
@Target(AnnotationTarget.PROPERTY)
annotation class Format(val value: String)

/**
 * JSON Schemas (draft 2020-12) of stored values, derived from their [SerialDescriptor] (pgecs design,
 * *kotlinx.serialization integration*). They are what Postgres enforces on every write, so they must
 * accept exactly what [PgecsJson] writes, plus additive extensions:
 *
 * - primitives map to `string`, `integer`, `number`, `boolean`; the standard serializers to their JSON
 *   form ([InstantAsEpochMillis] and [DurationAsMillis] to `integer`, [UuidAsString] to a `uuid` string);
 * - classes to `object` with `required` from non-optional elements and `additionalProperties: true`,
 *   so values written by a newer JVM with more fields still validate;
 * - lists to `array`, maps with string keys to `object` with `additionalProperties`, enums to `enum`
 *   of their serial names, nullable types to a `null` alternative;
 * - sealed hierarchies to `oneOf` of the subclasses, each requiring its `@type` discriminator.
 *
 * Contextual and open polymorphic values accept any JSON.
 */
@OptIn(ExperimentalSerializationApi::class)
object JsonSchemas {
    const val DIALECT = "https://json-schema.org/draft/2020-12/schema"

    /** The schema of values described by [descriptor], as a document with `$schema`. */
    fun of(descriptor: SerialDescriptor): JsonObject {
        val body = schema(descriptor, ArrayDeque())
        return JsonObject(mapOf("\$schema" to JsonPrimitive(DIALECT)) + body)
    }

    private fun schema(d: SerialDescriptor, stack: ArrayDeque<String>, annotations: List<Annotation> = emptyList()): JsonObject {
        val base = nonNull(d, stack, annotations)
        return if (d.isNullable) buildJsonObject { put("anyOf", buildJsonArray { add(base); add(buildJsonObject { put("type", "null") }) }) } else base
    }

    private fun nonNull(d0: SerialDescriptor, stack: ArrayDeque<String>, annotations: List<Annotation>): JsonObject {
        val d = if (d0.isInline) d0.getElementDescriptor(0) else d0
        val name = d.serialName.removeSuffix("?")
        val constraints = constraints(annotations)
        when (name) {
            InstantAsEpochMillis.SERIAL_NAME, DurationAsMillis.SERIAL_NAME ->
                return JsonObject(mapOf("type" to JsonPrimitive("integer")) + constraints)
            // kotlinx.serialization.json's own tree types: any JSON
            "kotlinx.serialization.json.JsonElement", "kotlinx.serialization.json.JsonPrimitive",
            "kotlinx.serialization.json.JsonLiteral" -> return JsonObject(emptyMap())
            "kotlinx.serialization.json.JsonObject" -> return buildJsonObject { put("type", "object") }
            "kotlinx.serialization.json.JsonArray" -> return buildJsonObject { put("type", "array") }
            "kotlinx.serialization.json.JsonNull" -> return buildJsonObject { put("type", "null") }
            UuidAsString.SERIAL_NAME, "kotlin.uuid.Uuid" ->
                return JsonObject(mapOf("type" to JsonPrimitive("string"), "format" to JsonPrimitive("uuid")) + constraints)
        }
        return when (val k = d.kind) {
            PrimitiveKind.BOOLEAN -> typed("boolean", constraints)
            PrimitiveKind.BYTE, PrimitiveKind.SHORT, PrimitiveKind.INT, PrimitiveKind.LONG -> typed("integer", constraints)
            PrimitiveKind.FLOAT, PrimitiveKind.DOUBLE -> typed("number", constraints)
            PrimitiveKind.CHAR -> JsonObject(mapOf("type" to JsonPrimitive("string"), "minLength" to JsonPrimitive(1), "maxLength" to JsonPrimitive(1)) + constraints)
            PrimitiveKind.STRING -> typed("string", constraints)
            SerialKind.ENUM -> buildJsonObject {
                put("type", "string")
                put("enum", JsonArray((0 until d.elementsCount).map { JsonPrimitive(d.getElementName(it)) }))
            }
            StructureKind.LIST -> buildJsonObject {
                put("type", "array")
                put("items", schema(d.getElementDescriptor(0), stack))
            }
            StructureKind.MAP -> {
                val key = d.getElementDescriptor(0)
                val keyKind = (if (key.isInline) key.getElementDescriptor(0) else key).kind
                if (keyKind == PrimitiveKind.STRING || keyKind == SerialKind.ENUM) {
                    buildJsonObject {
                        put("type", "object")
                        put("additionalProperties", schema(d.getElementDescriptor(1), stack))
                    }
                } else {
                    // kotlinx.serialization writes maps with non-string keys as flat [k, v, k, v] arrays
                    buildJsonObject { put("type", "array") }
                }
            }
            StructureKind.OBJECT -> buildJsonObject { put("type", "object") }
            StructureKind.CLASS -> classSchema(d, stack, discriminator = null)
            PolymorphicKind.SEALED -> sealedSchema(d, stack)
            PolymorphicKind.OPEN, SerialKind.CONTEXTUAL -> JsonObject(emptyMap())
            else -> error("no JSON Schema for serial kind $k (${d.serialName})")
        }
    }

    private fun typed(type: String, constraints: Map<String, JsonElement>) =
        JsonObject(mapOf("type" to JsonPrimitive(type)) + constraints)

    private fun classSchema(d: SerialDescriptor, stack: ArrayDeque<String>, discriminator: String?): JsonObject {
        if (d.serialName in stack) return JsonObject(emptyMap()) // recursive type: accept any JSON below the cycle
        stack.addLast(d.serialName)
        try {
            val props = LinkedHashMap<String, JsonElement>()
            val required = ArrayList<String>()
            if (discriminator != null) {
                props[PgecsJson.configuration.classDiscriminator] = buildJsonObject { put("const", discriminator) }
                required += PgecsJson.configuration.classDiscriminator
            }
            for (i in 0 until d.elementsCount) {
                val name = d.getElementName(i)
                props[name] = schema(d.getElementDescriptor(i), stack, d.getElementAnnotations(i))
                if (!d.isElementOptional(i)) required += name
            }
            return buildJsonObject {
                put("type", "object")
                put("properties", JsonObject(props))
                if (required.isNotEmpty()) put("required", JsonArray(required.map(::JsonPrimitive)))
                put("additionalProperties", true)
            }
        } finally {
            stack.removeLast()
        }
    }

    private fun sealedSchema(d: SerialDescriptor, stack: ArrayDeque<String>): JsonObject {
        // a sealed descriptor has two elements: the discriminator ("type") and the subclasses ("value")
        val subclasses = d.getElementDescriptor(1).elementDescriptors.toList()
        return buildJsonObject {
            put("oneOf", JsonArray(subclasses.map { sub ->
                when (sub.kind) {
                    StructureKind.OBJECT -> buildJsonObject {
                        put("type", "object")
                        put("properties", buildJsonObject {
                            put(PgecsJson.configuration.classDiscriminator, buildJsonObject { put("const", sub.serialName) })
                        })
                        put("required", buildJsonArray { add(JsonPrimitive(PgecsJson.configuration.classDiscriminator)) })
                    }
                    else -> classSchema(sub, stack, discriminator = sub.serialName)
                }
            }))
        }
    }

    private fun constraints(annotations: List<Annotation>): Map<String, JsonElement> = buildMap {
        for (a in annotations) when (a) {
            is Pattern -> put("pattern", JsonPrimitive(a.regex))
            is MinLength -> put("minLength", JsonPrimitive(a.value))
            is MaxLength -> put("maxLength", JsonPrimitive(a.value))
            is Min -> put("minimum", JsonPrimitive(a.value))
            is Max -> put("maximum", JsonPrimitive(a.value))
            is Format -> put("format", JsonPrimitive(a.value))
        }
    }
}
