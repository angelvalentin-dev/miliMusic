package com.metrolist.desktop.lyrics

import java.awt.Color
import java.awt.Font
import java.awt.GradientPaint
import java.awt.Image
import java.awt.RenderingHints
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import java.io.File
import java.net.URI
import javax.imageio.ImageIO

enum class ShareCardStyle(val label: String) {
    GRADIENT("Degradado"),
    DARK("Oscuro"),
    LIGHT("Claro"),
}

object DesktopLyricsCardRenderer {
    fun renderLyricsCard(
        lines: List<String>,
        title: String,
        artist: String,
        thumbnailUrl: String?,
        accentColorArgb: Int,
        style: ShareCardStyle = ShareCardStyle.GRADIENT,
    ): BufferedImage {
        val width = 1080
        val height = 1350
        val img = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB)
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)

            val accent = Color(accentColorArgb, false)
            val fgColor: Color
            val secondaryFg: Color
            when (style) {
                ShareCardStyle.GRADIENT -> {
                    val darkAccent = Color((accent.red * 0.45).toInt(), (accent.green * 0.45).toInt(), (accent.blue * 0.45).toInt())
                    val deep = Color((accent.red * 0.14).toInt(), (accent.green * 0.14).toInt(), (accent.blue * 0.14).toInt())
                    g.paint = GradientPaint(0f, 0f, darkAccent, width.toFloat(), height.toFloat(), deep)
                    fgColor = Color(0xFF, 0xFF, 0xFF)
                    secondaryFg = Color(0xFF, 0xFF, 0xFF, 190)
                }
                ShareCardStyle.DARK -> {
                    g.paint = Color(0x14, 0x14, 0x18)
                    fgColor = Color(0xF5, 0xF5, 0xF7)
                    secondaryFg = Color(0xB8, 0xB8, 0xC2)
                }
                ShareCardStyle.LIGHT -> {
                    g.paint = Color(0xF8, 0xF7, 0xF4)
                    fgColor = Color(0x18, 0x18, 0x1B)
                    secondaryFg = Color(0x52, 0x52, 0x5B)
                }
            }
            g.fill(RoundRectangle2D.Double(0.0, 0.0, width.toDouble(), height.toDouble(), 56.0, 56.0))

            // Top artwork + track metadata
            val artSize = 148
            val padX = 88
            var textStartX = padX
            val artImage = thumbnailUrl?.takeIf { it.isNotBlank() }?.let { url ->
                runCatching { ImageIO.read(URI.create(url).toURL()) }.getOrNull()
            }
            if (artImage != null) {
                val clip = RoundRectangle2D.Double(padX.toDouble(), 88.0, artSize.toDouble(), artSize.toDouble(), 28.0, 28.0)
                val oldClip = g.clip
                g.clip = clip
                g.drawImage(artImage, padX, 88, artSize, artSize, null)
                g.clip = oldClip
                textStartX = padX + artSize + 36
            }

            g.color = fgColor
            g.font = Font("SansSerif", Font.BOLD, 40)
            g.drawString(truncate(title, 28), textStartX, 154)
            g.color = secondaryFg
            g.font = Font("SansSerif", Font.PLAIN, 30)
            g.drawString(truncate(artist, 34), textStartX, 202)

            // Selected lyric lines
            g.color = fgColor
            g.font = Font("SansSerif", Font.BOLD, 54)
            val fm = g.fontMetrics
            val maxTextWidth = width - padX * 2
            var y = 370
            for (line in lines.take(5)) {
                val wrapped = wrapText(line.trim(), fm, maxTextWidth)
                for (w in wrapped) {
                    if (y > height - 190) break
                    g.drawString(w, padX, y)
                    y += 76
                }
                y += 28
            }

            // Bottom brand footer
            g.color = secondaryFg
            g.font = Font("SansSerif", Font.BOLD, 28)
            g.drawString("miliMusic", padX, height - 76)
        } finally {
            g.dispose()
        }
        return img
    }

    fun renderWrappedCard(
        yearLabel: String,
        totalMinutes: Long,
        topSong: String,
        topArtist: String,
        topSongsList: List<String>,
        accentColorArgb: Int,
    ): BufferedImage {
        val lines = buildList {
            add("Resumen $yearLabel — $totalMinutes min escuchados")
            if (topArtist.isNotBlank()) add("Artista #1: $topArtist")
            if (topSong.isNotBlank()) add("Canción #1: $topSong")
            topSongsList.take(2).forEachIndexed { idx, s -> add("#${idx + 2} $s") }
        }
        return renderLyricsCard(
            lines = lines,
            title = "miliMusic Wrapped $yearLabel",
            artist = "Tu resumen musical",
            thumbnailUrl = null,
            accentColorArgb = accentColorArgb,
            style = ShareCardStyle.GRADIENT,
        )
    }

    fun savePng(image: BufferedImage, targetFile: File): Result<File> = runCatching {
        targetFile.parentFile?.mkdirs()
        ImageIO.write(image, "png", targetFile)
        targetFile
    }

    fun copyToClipboard(image: BufferedImage): Result<Unit> = runCatching {
        val transferable = object : Transferable {
            override fun getTransferDataFlavors(): Array<DataFlavor> = arrayOf(DataFlavor.imageFlavor)
            override fun isDataFlavorSupported(flavor: DataFlavor?): Boolean = flavor == DataFlavor.imageFlavor
            override fun getTransferData(flavor: DataFlavor?): Any {
                if (flavor != DataFlavor.imageFlavor) throw UnsupportedFlavorException(flavor)
                return image as Image
            }
        }
        Toolkit.getDefaultToolkit().systemClipboard.setContents(transferable, null)
    }

    private fun truncate(text: String, maxChars: Int): String =
        if (text.length <= maxChars) text else text.take(maxChars - 1) + "…"

    private fun wrapText(text: String, fm: java.awt.FontMetrics, maxWidth: Int): List<String> {
        if (text.isBlank()) return emptyList()
        val words = text.split(Regex("""\s+"""))
        val out = mutableListOf<String>()
        var current = StringBuilder()
        for (word in words) {
            val candidate = if (current.isEmpty()) word else "$current $word"
            if (fm.stringWidth(candidate) <= maxWidth) {
                current = StringBuilder(candidate)
            } else {
                if (current.isNotEmpty()) out += current.toString()
                current = StringBuilder(word)
            }
        }
        if (current.isNotEmpty()) out += current.toString()
        return out
    }
}
