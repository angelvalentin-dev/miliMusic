package com.metrolist.desktop.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamiccolor.ColorSpec
import com.materialkolor.rememberDynamicColorScheme
import com.materialkolor.score.Score
import com.metrolist.desktop.data.DesktopDarkMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URI
import javax.imageio.ImageIO

val DefaultThemeColor = Color(0xFFED5564)

@Composable
fun MiliMusicDesktopTheme(
    darkMode: DesktopDarkMode,
    pureBlack: Boolean,
    seedColor: Color,
    content: @Composable () -> Unit,
) {
    val isDark = when (darkMode) {
        DesktopDarkMode.SYSTEM -> isSystemInDarkTheme()
        DesktopDarkMode.LIGHT -> false
        DesktopDarkMode.DARK -> true
    }

    val baseScheme = rememberDynamicColorScheme(
        seedColor = seedColor,
        isDark = isDark,
        specVersion = ColorSpec.SpecVersion.SPEC_2025,
        style = PaletteStyle.TonalSpot,
    )

    val colorScheme = remember(baseScheme, pureBlack, isDark) {
        if (isDark && pureBlack) baseScheme.pureBlack() else baseScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content,
    )
}

fun ColorScheme.pureBlack(): ColorScheme =
    copy(
        surface = Color.Black,
        background = Color.Black,
    )

@Composable
fun rememberArtworkSeedColor(
    thumbnailUrl: String?,
    fallback: Color,
    enabled: Boolean,
): Color {
    var extracted by remember(fallback) { mutableStateOf(fallback) }

    LaunchedEffect(thumbnailUrl, enabled, fallback) {
        if (!enabled || thumbnailUrl.isNullOrBlank()) {
            extracted = fallback
            return@LaunchedEffect
        }
        val color = withContext(Dispatchers.IO) {
            runCatching {
                val image = URI(thumbnailUrl).toURL().openStream().use { ImageIO.read(it) }
                    ?: return@runCatching fallback
                val w = image.width.coerceAtMost(48)
                val h = image.height.coerceAtMost(48)
                val stepX = (image.width / w).coerceAtLeast(1)
                val stepY = (image.height / h).coerceAtLeast(1)
                val histogram = HashMap<Int, Int>()
                var y = 0
                while (y < image.height) {
                    var x = 0
                    while (x < image.width) {
                        val rgb = image.getRGB(x, y)
                        val quantized = (rgb and 0xF8F8F8) or (0xFF shl 24)
                        histogram[quantized] = (histogram[quantized] ?: 0) + 1
                        x += stepX
                    }
                    y += stepY
                }
                val ranked = Score.score(histogram, 1, fallback.toArgb(), true)
                Color(ranked.first())
            }.getOrDefault(fallback)
        }
        extracted = color
    }

    return extracted
}
