package dev.quire.compose.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The palette, ported from the desktop shell's `ui/Colors.slint` — the same
 * near-white/warm-neutral light theme and the same "never pure black" dark one,
 * so a page opened on the phone looks like the page opened on the desktop.
 *
 * Ported rather than re-invented for a reason beyond consistency: those values
 * are *measured*, not chosen. The muted tier is the lightest text in the app and
 * sits at 4.43:1 on white; the block swatches were re-tuned so an orange block
 * on its own tint clears 3:1. Retyping them by eye would throw that away.
 */
data class QuireColors(
    val background: Color,
    val sidebar: Color,
    val surface: Color,
    val surfaceHover: Color,
    val surfaceSelected: Color,
    /**
     * The organizer's card plate: a translucent overlay rather than an opaque
     * colour, so it takes the page's own tone underneath it — the "glass" the
     * reference app draws every note and task row on (`inbox_card`'s 8% white,
     * `aw_surface_glass`'s 6% black). A flat grey would be a second background
     * colour to keep in step with the theme.
     */
    val card: Color,
    val cardBorder: Color,
    val codeBackground: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textMuted: Color,
    val border: Color,
    val borderStrong: Color,
    val divider: Color,
    val accent: Color,
    val accentSoft: Color,
    val accentText: Color,
    val danger: Color,
    val calloutBackground: Color,
    val scrim: Color,
    val isDark: Boolean,
)

private val LightColors = QuireColors(
    background = Color(0xFFFFFFFF),
    sidebar = Color(0xFFF6F6F4),
    surface = Color(0xFFFFFFFF),
    surfaceHover = Color(0xFFEFEFEC),
    surfaceSelected = Color(0xFFE8E8E4),
    card = Color(0x0F000000),
    cardBorder = Color(0x14000000),
    codeBackground = Color(0xFFF6F6F4),
    textPrimary = Color(0xFF1F2328),
    textSecondary = Color(0xFF5F6569),
    textMuted = Color(0xFF75787D),
    border = Color(0xFFEBEBEA),
    borderStrong = Color(0xFFD9D9D6),
    divider = Color(0xFFE2E2DF),
    accent = Color(0xFF5B54D6),
    accentSoft = Color(0xFFEEEEFF),
    accentText = Color(0xFF4A44B0),
    danger = Color(0xFFD9412E),
    calloutBackground = Color(0xFFEEEEFF),
    scrim = Color(0x59000000),
    isDark = false,
)

private val DarkColors = QuireColors(
    background = Color(0xFF1A1A1E),
    sidebar = Color(0xFF16161A),
    surface = Color(0xFF1F1F24),
    surfaceHover = Color(0xFF26262C),
    surfaceSelected = Color(0xFF2D2D34),
    card = Color(0x14FFFFFF),
    cardBorder = Color(0x1FFFFFFF),
    codeBackground = Color(0xFF101014),
    textPrimary = Color(0xFFE7E7EA),
    textSecondary = Color(0xFFB4B4BB),
    textMuted = Color(0xFF7D7D86),
    border = Color(0xFF2B2B31),
    borderStrong = Color(0xFF3B3B43),
    divider = Color(0xFF34343B),
    accent = Color(0xFF8F87F0),
    accentSoft = Color(0xFF2A2945),
    accentText = Color(0xFFA8A2F5),
    danger = Color(0xFFF0776C),
    calloutBackground = Color(0xFF2A2945),
    scrim = Color(0x8C000000),
    isDark = true,
)

val LocalQuireColors = staticCompositionLocalOf { LightColors }

/** Spacing ladder — the only allowed steps, same numbers as `Theme.slint`. */
object Spacing {
    val xxs = 2.dp
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 24.dp
    val xxl = 32.dp
}

object Radius {
    val xs = 3.dp
    val sm = 5.dp
    val md = 8.dp
    val lg = 12.dp
    val xl = 16.dp
}

/**
 * The type scale.
 *
 * The document tier is the desktop's numbers; the chrome tier is lifted from
 * 11/13 to 12/14 sp, because the desktop's is sized for a mouse at 96 dpi and a
 * finger on a phone reads it as small. Line heights are factors of the natural
 * box in Slint and explicit here, which is the same instruction.
 */
object QuireType {
    val pageTitle = TextStyle(fontSize = 30.sp, lineHeight = 38.sp, fontWeight = FontWeight.SemiBold)
    val h1 = TextStyle(fontSize = 28.sp, lineHeight = 36.sp, fontWeight = FontWeight.SemiBold)
    val h2 = TextStyle(fontSize = 22.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold)
    val h3 = TextStyle(fontSize = 18.sp, lineHeight = 27.sp, fontWeight = FontWeight.SemiBold)
    val body = TextStyle(fontSize = 16.sp, lineHeight = 25.sp)
    val code = TextStyle(fontSize = 14.sp, lineHeight = 21.sp, fontFamily = FontFamily.Monospace)
    val ui = TextStyle(fontSize = 14.sp, lineHeight = 20.sp)
    val caption = TextStyle(fontSize = 12.sp, lineHeight = 16.sp)
}

/** Notion-style block swatches, slot 0 = inherit, 1..9 = gray..red (`Colors.slint`). */
fun blockTextColor(slot: Int, dark: Boolean): Color = when (slot) {
    0 -> if (dark) Color(0xFFE7E7EA) else Color(0xFF1F2328)
    1 -> if (dark) Color(0xFF9B9A97) else Color(0xFF787774)
    2 -> if (dark) Color(0xFFC3996B) else Color(0xFF9F6B53)
    3 -> if (dark) Color(0xFFE28D50) else Color(0xFFBD6408)
    4 -> if (dark) Color(0xFFDCAE3F) else Color(0xFFA87718)
    5 -> if (dark) Color(0xFF6BA584) else Color(0xFF448361)
    6 -> if (dark) Color(0xFF5B9BC4) else Color(0xFF337EA9)
    7 -> if (dark) Color(0xFFAD7FB8) else Color(0xFF9065B0)
    8 -> if (dark) Color(0xFFD3709F) else Color(0xFFC14C8A)
    else -> if (dark) Color(0xFFE06D66) else Color(0xFFD44C47)
}

fun blockBackgroundColor(slot: Int, dark: Boolean): Color = when (slot) {
    0 -> Color.Transparent
    1 -> if (dark) Color(0xFF2F2F2F) else Color(0xFFF1F1EF)
    2 -> if (dark) Color(0xFF2E2420) else Color(0xFFF4EEEE)
    3 -> if (dark) Color(0xFF38291C) else Color(0xFFFAEBDD)
    4 -> if (dark) Color(0xFF372C14) else Color(0xFFFBF3DB)
    5 -> if (dark) Color(0xFF1E2C21) else Color(0xFFEDF3EC)
    6 -> if (dark) Color(0xFF1D272E) else Color(0xFFE7F3F8)
    7 -> if (dark) Color(0xFF2A2233) else Color(0xFFF6F3F9)
    8 -> if (dark) Color(0xFF322028) else Color(0xFFFAF1F5)
    else -> if (dark) Color(0xFF37201F) else Color(0xFFFDEBEC)
}

/**
 * Wrap the app in the palette.
 *
 * @param theme the stored setting: `system` follows the device (the platform's
 *   own convention, and the default), `light` and `dark` pin it.
 */
@Composable
fun QuireTheme(theme: String, content: @Composable () -> Unit) {
    val dark = when (theme) {
        "dark" -> true
        "light" -> false
        else -> isSystemInDarkTheme()
    }
    val colors = if (dark) DarkColors else LightColors

    // Material3 still draws the primitives we borrow (Checkbox, Switch, dialogs,
    // the sheets), so the palette has to reach it too or a checkbox would be
    // Material purple in a Quire page.
    val scheme = if (dark) {
        darkColorScheme(
            primary = colors.accent,
            onPrimary = Color.White,
            background = colors.background,
            onBackground = colors.textPrimary,
            surface = colors.surface,
            onSurface = colors.textPrimary,
            surfaceVariant = colors.surfaceHover,
            onSurfaceVariant = colors.textSecondary,
            outline = colors.borderStrong,
            error = colors.danger,
        )
    } else {
        lightColorScheme(
            primary = colors.accent,
            onPrimary = Color.White,
            background = colors.background,
            onBackground = colors.textPrimary,
            surface = colors.surface,
            onSurface = colors.textPrimary,
            surfaceVariant = colors.surfaceHover,
            onSurfaceVariant = colors.textSecondary,
            outline = colors.borderStrong,
            error = colors.danger,
        )
    }

    CompositionLocalProvider(LocalQuireColors provides colors) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
