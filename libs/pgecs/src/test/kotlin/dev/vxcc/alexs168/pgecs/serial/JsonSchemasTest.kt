package dev.vxcc.alexs168.pgecs.serial

import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.serializer
import dev.vxcc.alexs168.pgecs.db.Database
import dev.vxcc.alexs168.pgecs.testing.TestPostgres

@Serializable
enum class Color { RED, GREEN }

@Serializable
sealed interface Shape {
    @Serializable
    data class Circle(val radius: Double) : Shape

    @Serializable
    data class Square(val side: Long, @MinLength(1) val label: String = "x") : Shape

    @Serializable
    data object Dot : Shape
}

@Serializable
data class Everything(
    val name: String,
    val count: Int,
    val ratio: Double,
    val on: Boolean,
    val color: Color,
    val tags: List<String>,
    val scores: Map<String, Long>,
    val shape: Shape,
    @Serializable(InstantAsEpochMillis::class) val at: Instant,
    @Serializable(DurationAsMillis::class) val took: Duration,
    @Serializable(UuidAsString::class) val id: UUID,
    val nested: Everything? = null,
    @Max(10) val small: Long = 1,
)

class JsonSchemasTest {
    private val db by lazy { Database.connect(TestPostgres.config) }

    private fun <T> matches(serializer: KSerializer<T>, value: String): Boolean = db.read {
        connection.prepareStatement("select jsonb_matches_schema(?::json, ?::jsonb)").use { st ->
            st.setString(1, JsonSchemas.of(serializer.descriptor).toString())
            st.setString(2, value)
            st.executeQuery().use { it.next(); it.getBoolean(1) }
        }
    }

    private fun <T> accepts(serializer: KSerializer<T>, value: T) =
        assertEquals(true, matches(serializer, PgecsJson.encodeToString(serializer, value)), "schema rejects $value")

    private val sample = Everything(
        "a", 1, 0.5, true, Color.GREEN, listOf("x"), mapOf("k" to 2), Shape.Square(3),
        Instant.ofEpochMilli(5), Duration.ofSeconds(1), UUID.randomUUID(),
        nested = Everything("b", 2, 1.0, false, Color.RED, emptyList(), emptyMap(), Shape.Dot, Instant.EPOCH, Duration.ZERO, UUID.randomUUID()),
    )

    @Test
    fun `what PgecsJson writes validates`() {
        accepts(serializer<Everything>(), sample)
        accepts(serializer<Everything>(), sample.copy(shape = Shape.Circle(1.5)))
        accepts(serializer<Long>(), 42L)
        accepts(InstantAsEpochMillis, Instant.now())
        accepts(serializer<String?>(), null)
    }

    @Test
    fun `wrong shapes and broken constraints are rejected`() {
        val s = serializer<Everything>()
        val good = PgecsJson.encodeToString(s, sample)
        assertEquals(false, matches(s, good.replace("\"GREEN\"", "\"BLUE\"")))
        assertEquals(false, matches(s, good.replace("\"count\":1", "\"count\":\"1\"")))
        assertEquals(false, matches(s, good.replace("\"small\":1", "\"small\":11")))
        assertEquals(false, matches(s, good.replace("\"@type\":\"dev.vxcc.alexs168.pgecs.serial.Shape.Square\"", "\"@type\":\"nope\"")))
        assertEquals(false, matches(s, "{}"))
        // additive extensions written by newer processes still validate
        assertEquals(true, matches(s, good.replaceFirst("{", "{\"extra\":1,")))
    }
}
