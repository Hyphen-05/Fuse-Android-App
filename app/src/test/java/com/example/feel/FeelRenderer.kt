package com.example.feel

import java.awt.BasicStroke
import java.awt.Color as AwtColor
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/** One strip's run, ready to draw. */
data class FeelTrack(
    val label: String,
    /** What the strip shows over time, from [VirtualStrip.timeline]. */
    val frames: List<StripFrame>,
    /** Free-text second line — preset config, stats, whatever makes the row self-describing. */
    val caption: String = ""
)

/**
 * Draws what the strips did as a picture.
 *
 * The point of the whole harness: a timeline of colour is something you can *look at* and judge —
 * how often it changes, how far the hue travels, whether brightness pumps or drifts, where writes
 * were dropped — none of which survives being described in numbers or in prose. Stacking several
 * presets in one image makes them directly comparable, which is the question that actually gets
 * asked ("is Ebb & Flow calmer than Balanced?").
 */
object FeelRenderer {

    private const val LABEL_WIDTH = 190
    private const val ROW_HEIGHT = 92
    private const val BAND_HEIGHT = 44
    private const val CURVE_HEIGHT = 40
    private const val TOP_PADDING = 46
    private const val BOTTOM_PADDING = 30

    fun render(
        tracks: List<FeelTrack>,
        title: String,
        outputFile: File,
        pixelsPerSecond: Int = 60
    ): File {
        require(tracks.isNotEmpty()) { "nothing to render" }
        val durationMs = tracks.maxOf { it.frames.lastOrNull()?.atMs ?: 0L }
        val plotWidth = ((durationMs / 1000.0) * pixelsPerSecond).toInt().coerceAtLeast(200)
        val width = LABEL_WIDTH + plotWidth + 20
        val height = TOP_PADDING + tracks.size * ROW_HEIGHT + BOTTOM_PADDING

        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val gfx = image.createGraphics()
        gfx.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        gfx.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)

        gfx.color = AwtColor(18, 18, 20)
        gfx.fillRect(0, 0, width, height)

        gfx.color = AwtColor(235, 235, 240)
        gfx.font = Font(Font.SANS_SERIF, Font.BOLD, 16)
        gfx.drawString(title, 12, 26)

        gfx.font = Font(Font.SANS_SERIF, Font.PLAIN, 10)
        gfx.color = AwtColor(120, 120, 130)
        for (second in 0..(durationMs / 1000)) {
            val x = LABEL_WIDTH + (second * pixelsPerSecond).toInt()
            if (x > width - 20) break
            gfx.drawLine(x, TOP_PADDING - 8, x, height - BOTTOM_PADDING)
            gfx.drawString("${second}s", x + 2, TOP_PADDING - 12)
        }

        tracks.forEachIndexed { index, track ->
            val rowTop = TOP_PADDING + index * ROW_HEIGHT

            gfx.color = AwtColor(235, 235, 240)
            gfx.font = Font(Font.SANS_SERIF, Font.BOLD, 12)
            gfx.drawString(track.label, 12, rowTop + 18)
            gfx.color = AwtColor(150, 150, 160)
            gfx.font = Font(Font.SANS_SERIF, Font.PLAIN, 10)
            track.caption.split(" | ").forEachIndexed { line, text ->
                gfx.drawString(text, 12, rowTop + 34 + line * 12)
            }

            // Lane 1 — the colour the strip is actually showing, one column per sample.
            track.frames.forEach { frame ->
                val x = LABEL_WIDTH + ((frame.atMs / 1000.0) * pixelsPerSecond).toInt()
                if (x in 0 until width) {
                    gfx.color = AwtColor(frame.r.coerceIn(0, 255), frame.g.coerceIn(0, 255), frame.b.coerceIn(0, 255))
                    gfx.drawLine(x, rowTop, x, rowTop + BAND_HEIGHT)
                }
            }

            // Lane 2 — perceived brightness, so pumping vs drifting is legible as a shape.
            gfx.color = AwtColor(90, 90, 100)
            gfx.drawLine(LABEL_WIDTH, rowTop + BAND_HEIGHT + CURVE_HEIGHT, LABEL_WIDTH + plotWidth, rowTop + BAND_HEIGHT + CURVE_HEIGHT)
            gfx.color = AwtColor(255, 214, 102)
            gfx.stroke = BasicStroke(1.2f)
            var previousX = -1
            var previousY = -1
            track.frames.forEach { frame ->
                val luma = (0.2126 * frame.r + 0.7152 * frame.g + 0.0722 * frame.b) / 255.0
                val x = LABEL_WIDTH + ((frame.atMs / 1000.0) * pixelsPerSecond).toInt()
                val y = rowTop + BAND_HEIGHT + CURVE_HEIGHT - (luma * CURVE_HEIGHT).toInt()
                if (previousX >= 0 && x in 0 until width) gfx.drawLine(previousX, previousY, x, y)
                previousX = x
                previousY = y
            }

            // Lane 3 — dropped writes, the stutter you'd see but never find in a DSP trace.
            gfx.color = AwtColor(255, 82, 82)
            track.frames.filter { it.droppedWriteHere }.forEach { frame ->
                val x = LABEL_WIDTH + ((frame.atMs / 1000.0) * pixelsPerSecond).toInt()
                if (x in 0 until width) {
                    gfx.drawLine(x, rowTop + BAND_HEIGHT + CURVE_HEIGHT + 3, x, rowTop + BAND_HEIGHT + CURVE_HEIGHT + 9)
                }
            }
        }

        gfx.dispose()
        outputFile.parentFile?.mkdirs()
        ImageIO.write(image, "png", outputFile)
        return outputFile
    }
}
