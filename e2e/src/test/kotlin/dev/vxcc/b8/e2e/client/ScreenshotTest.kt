package dev.vxcc.b8.e2e.client

import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.io.TempDir
import java.awt.Color
import java.awt.image.BufferedImage
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@Tag("unit")
class ScreenshotTest {
    @TempDir
    lateinit var dir: Path

    private fun shot(name: String, paint: (BufferedImage) -> Unit): Screenshot {
        val img = BufferedImage(160, 90, BufferedImage.TYPE_INT_RGB).also(paint)
        val file = dir.resolve("$name.png")
        ImageIO.write(img, "png", file.toFile())
        return Screenshot.read(file)
    }

    private fun flat(c: Color) = { img: BufferedImage -> img.createGraphics().apply { color = c; fillRect(0, 0, img.width, img.height) }.dispose() }

    private fun scene(seed: Int) = { img: BufferedImage ->
        val r = Random(seed)
        val g = img.createGraphics()
        g.color = Color(120, 170, 255); g.fillRect(0, 0, img.width, img.height / 2) // sky
        g.color = Color(80, 140, 60); g.fillRect(0, img.height / 2, img.width, img.height / 2) // grass
        repeat(30) { g.color = Color(r.nextInt(256), r.nextInt(256), r.nextInt(256)); g.fillRect(r.nextInt(150), r.nextInt(80), 8, 8) }
        g.dispose()
    }

    @Test
    fun blankDetection() {
        assertTrue(shot("black", flat(Color.BLACK)).isBlank())
        assertTrue(shot("sky", flat(Color(120, 170, 255))).isBlank())
        assertFalse(shot("scene", scene(1)).isBlank())
        assertFailsWith<IllegalStateException> { shot("black2", flat(Color.BLACK)).assertNotBlank() }
    }

    @Test
    fun difference() {
        val a = shot("a", scene(1))
        assertEquals(0.0, a.difference(shot("a2", scene(1))))
        assertTrue(a.difference(shot("b", scene(2))) in 0.001..0.2)
        assertTrue(shot("black", flat(Color.BLACK)).difference(shot("white", flat(Color.WHITE))) > 0.99)
    }

    @Test
    fun referencesAreOnlyWrittenWhenAsked() {
        val refs = dir.resolve("refs")
        val env = mapOf("BLOCKS_E2E_SCREENSHOT_REFS" to refs.toString())
        val a = shot("a", scene(1))
        assertFailsWith<IllegalStateException> { a.assertMatches("scene", 0.01, env) }
        a.assertMatches("scene", 0.01, env + ("BLOCKS_E2E_UPDATE_SCREENSHOTS" to "true"))
        a.assertMatches("scene", 0.01, env)
        assertFailsWith<IllegalStateException> { shot("w", flat(Color.WHITE)).assertMatches("scene", 0.01, env) }
    }
}
