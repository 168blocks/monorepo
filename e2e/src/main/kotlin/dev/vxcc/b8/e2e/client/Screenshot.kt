package dev.vxcc.b8.e2e.client

import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import javax.imageio.ImageIO
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * A screenshot from a [GameClient], with the checks tests need: not blank, and similar to a stored
 * reference image within a tolerance.
 *
 * References live in `e2e/src/test/screenshots/<name>.png` (`BLOCKS_E2E_SCREENSHOT_REFS`). They are only
 * written when asked for: `tools/network.sh test -PupdateScreenshots` (sets
 * `BLOCKS_E2E_UPDATE_SCREENSHOTS=true`); a missing reference fails the test otherwise.
 */
class Screenshot(val file: Path, val image: BufferedImage) {
    val width get() = image.width
    val height get() = image.height

    /** Share of pixels with the most common colour (1.0 = one flat colour). */
    fun dominantColorShare(): Double {
        val counts = HashMap<Int, Int>()
        for (y in 0 until height) for (x in 0 until width) counts.merge(image.getRGB(x, y) and 0xffffff, 1, Int::plus)
        return counts.values.max().toDouble() / (width * height)
    }

    /** Standard deviation of the luminance, 0..1. */
    fun luminanceStdDev(): Double {
        var sum = 0.0
        var sumSq = 0.0
        for (y in 0 until height) for (x in 0 until width) {
            val l = luminance(image.getRGB(x, y))
            sum += l
            sumSq += l * l
        }
        val n = (width * height).toDouble()
        val mean = sum / n
        return sqrt((sumSq / n - mean * mean).coerceAtLeast(0.0))
    }

    /** Blank: one colour almost everywhere, or hardly any contrast (black, a clear colour, a fog wall). */
    fun isBlank(): Boolean = dominantColorShare() > 0.95 || luminanceStdDev() < 0.02

    fun assertNotBlank() = apply {
        check(!isBlank()) {
            "screenshot $file is blank (dominant colour ${"%.3f".format(dominantColorShare())}, luminance stddev ${"%.4f".format(luminanceStdDev())})"
        }
    }

    /**
     * Difference to [other], 0 (same) .. 1: mean absolute per-channel difference after scaling both down
     * to a [grid] x grid/aspect raster, so small shifts (animated water, a moving mob) weigh little.
     */
    fun difference(other: Screenshot, grid: Int = 64): Double {
        val h = (grid * height / width).coerceAtLeast(1)
        val a = downscale(image, grid, h)
        val b = downscale(other.image, grid, h)
        var total = 0.0
        for (i in a.indices) total += abs(a[i] - b[i])
        return total / a.size
    }

    /**
     * Compares with the reference `<refs>/<name>.png`: fails when the [difference] is above [maxDiff].
     * With `BLOCKS_E2E_UPDATE_SCREENSHOTS=true` the reference is (re)written instead.
     */
    fun assertMatches(name: String, maxDiff: Double, env: Map<String, String> = System.getenv()) = apply {
        val refs = Path.of(env["BLOCKS_E2E_SCREENSHOT_REFS"] ?: "src/test/screenshots")
        val ref = refs.resolve("$name.png")
        if (env["BLOCKS_E2E_UPDATE_SCREENSHOTS"] == "true") {
            refs.createDirectories()
            Files.copy(file, ref, StandardCopyOption.REPLACE_EXISTING)
            return@apply
        }
        check(ref.exists()) { "no reference screenshot $ref; create it with tools/network.sh test -PupdateScreenshots (actual: $file)" }
        val diff = difference(read(ref))
        check(diff <= maxDiff) { "screenshot $file differs from $ref by ${"%.4f".format(diff)} (max $maxDiff)" }
    }

    companion object {
        fun read(file: Path) = Screenshot(file, ImageIO.read(file.toFile()) ?: error("not an image: $file"))

        private fun luminance(rgb: Int): Double =
            (0.2126 * (rgb shr 16 and 0xff) + 0.7152 * (rgb shr 8 and 0xff) + 0.0722 * (rgb and 0xff)) / 255.0

        /** Box-filtered RGB values 0..1, row-major, 3 per cell. */
        private fun downscale(img: BufferedImage, w: Int, h: Int): DoubleArray {
            val out = DoubleArray(w * h * 3)
            val counts = IntArray(w * h)
            for (y in 0 until img.height) for (x in 0 until img.width) {
                val cell = (y * h / img.height) * w + (x * w / img.width)
                val rgb = img.getRGB(x, y)
                out[cell * 3] += (rgb shr 16 and 0xff) / 255.0
                out[cell * 3 + 1] += (rgb shr 8 and 0xff) / 255.0
                out[cell * 3 + 2] += (rgb and 0xff) / 255.0
                counts[cell]++
            }
            for (i in counts.indices) for (c in 0..2) out[i * 3 + c] /= counts[i].coerceAtLeast(1)
            return out
        }
    }
}
