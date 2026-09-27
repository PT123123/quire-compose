package dev.quire.compose.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * One row of ActivityWatch's theme table — `aw-qtui/src/theme.h`'s `kThemes[]`,
 * field for field with the desktop shell's `ThemePalette` in `ui/Colors.slint`.
 * The two shells are ports of the same table, so a theme picked on the phone is
 * the theme the desktop opens in (they share the `theme` row in `quire.db`).
 */
data class ThemePalette(
    val id: String,
    val name: String,
    val emoji: String,
    val light: Boolean,
    val bg: Color,
    val bgElev: Color,
    val bgElev2: Color,
    val border: Color,
    val fg: Color,
    val fgMuted: Color,
    val accent: Color,
    val danger: Color,
    val ok: Color,
    /**
     * The second stop of the page ramp. Equal to [bg] for the nine flat themes,
     * which is what lets one `Brush.verticalGradient` draw both kinds — the
     * same trick the desktop's `Colors.page` uses.
     */
    val grad2: Color,
)

/** AW's twelve themes, in `kThemes[]` order. `midnight` is AW's default and ours. */
val ThemeCatalog: List<ThemePalette> = listOf(
    ThemePalette(
        id = "midnight", name = "暗夜蓝", emoji = "🌙", light = false,
        bg = Color(0xFF1A1D21), bgElev = Color(0xFF22262C), bgElev2 = Color(0xFF2A2F37),
        border = Color(0xFF343A44), fg = Color(0xFFE6E6E6), fgMuted = Color(0xFF9AA4B0),
        accent = Color(0xFF4C8BF5), danger = Color(0xFFE5534B), ok = Color(0xFF3FB950),
        grad2 = Color(0xFF1A1D21),
    ),
    ThemePalette(
        id = "graphite", name = "石墨灰", emoji = "🪨", light = false,
        bg = Color(0xFF161B22), bgElev = Color(0xFF1F242C), bgElev2 = Color(0xFF282E36),
        border = Color(0xFF363D46), fg = Color(0xFFE6EDF3), fgMuted = Color(0xFF8B949E),
        accent = Color(0xFF2F81F7), danger = Color(0xFFF85149), ok = Color(0xFF3FB950),
        grad2 = Color(0xFF161B22),
    ),
    ThemePalette(
        id = "violet", name = "紫罗兰", emoji = "💜", light = false,
        bg = Color(0xFF17151F), bgElev = Color(0xFF211E2D), bgElev2 = Color(0xFF2B2738),
        border = Color(0xFF3A3550), fg = Color(0xFFE8E6F0), fgMuted = Color(0xFF9D97B5),
        accent = Color(0xFFA78BFA), danger = Color(0xFFF87171), ok = Color(0xFF34D399),
        grad2 = Color(0xFF17151F),
    ),
    ThemePalette(
        id = "emerald", name = "森林绿", emoji = "🌲", light = false,
        bg = Color(0xFF0F1A16), bgElev = Color(0xFF16241E), bgElev2 = Color(0xFF1D2F27),
        border = Color(0xFF2A4238), fg = Color(0xFFE2EFE7), fgMuted = Color(0xFF93B3A4),
        accent = Color(0xFF34D399), danger = Color(0xFFF87171), ok = Color(0xFF22C55E),
        grad2 = Color(0xFF0F1A16),
    ),
    ThemePalette(
        id = "amber", name = "琥珀暖", emoji = "🔥", light = false,
        bg = Color(0xFF1C1712), bgElev = Color(0xFF241D16), bgElev2 = Color(0xFF2E251B),
        border = Color(0xFF45392A), fg = Color(0xFFF0E6D6), fgMuted = Color(0xFFB8A68A),
        accent = Color(0xFFF59E0B), danger = Color(0xFFF87171), ok = Color(0xFF34D399),
        grad2 = Color(0xFF1C1712),
    ),
    ThemePalette(
        id = "ocean", name = "海洋青", emoji = "🌊", light = false,
        bg = Color(0xFF0B1A22), bgElev = Color(0xFF10222C), bgElev2 = Color(0xFF16303C),
        border = Color(0xFF20404F), fg = Color(0xFFD8EEF7), fgMuted = Color(0xFF86B3C4),
        accent = Color(0xFF22D3EE), danger = Color(0xFFFB7185), ok = Color(0xFF2DD4BF),
        grad2 = Color(0xFF0B1A22),
    ),
    ThemePalette(
        id = "rose", name = "珊瑚红", emoji = "🌹", light = false,
        bg = Color(0xFF1A1216), bgElev = Color(0xFF221820), bgElev2 = Color(0xFF2C2029),
        border = Color(0xFF43313A), fg = Color(0xFFF0E4E9), fgMuted = Color(0xFFBB9FA9),
        accent = Color(0xFFFB7185), danger = Color(0xFFF43F5E), ok = Color(0xFF34D399),
        grad2 = Color(0xFF1A1216),
    ),
    ThemePalette(
        id = "light", name = "明亮", emoji = "☀️", light = true,
        bg = Color(0xFFF5F6F8), bgElev = Color(0xFFFFFFFF), bgElev2 = Color(0xFFECEFF3),
        border = Color(0xFFD9DEE5), fg = Color(0xFF24292F), fgMuted = Color(0xFF6B7280),
        accent = Color(0xFF2F6FED), danger = Color(0xFFD13438), ok = Color(0xFF1A7F37),
        grad2 = Color(0xFFF5F6F8),
    ),
    // ---- the four gradient themes: `bg` is the top stop, `grad2` the bottom ----
    ThemePalette(
        id = "jade", name = "翡翠绿", emoji = "💎", light = false,
        bg = Color(0xFF0F4938), bgElev = Color(0xFF143F35), bgElev2 = Color(0xFF1B4F43),
        border = Color(0xFF2A4F46), fg = Color(0xFFE6EEF0), fgMuted = Color(0xFF8FA8A6),
        accent = Color(0xFF34D399), danger = Color(0xFFF87171), ok = Color(0xFF22C55E),
        grad2 = Color(0xFF0A2442),
    ),
    ThemePalette(
        id = "deepblue", name = "深空蓝", emoji = "🌌", light = false,
        bg = Color(0xFF0D2A49), bgElev = Color(0xFF112F4E), bgElev2 = Color(0xFF173B5F),
        border = Color(0xFF1E3A57), fg = Color(0xFFE3ECF5), fgMuted = Color(0xFF8AA3BD),
        accent = Color(0xFF38BDF8), danger = Color(0xFFF87171), ok = Color(0xFF34D399),
        grad2 = Color(0xFF060F1E),
    ),
    ThemePalette(
        id = "twilight", name = "暮光紫", emoji = "🌆", light = false,
        bg = Color(0xFF2B1D45), bgElev = Color(0xFF312250), bgElev2 = Color(0xFF3A2A63),
        border = Color(0xFF413566), fg = Color(0xFFECE8F5), fgMuted = Color(0xFFA395C4),
        accent = Color(0xFFC084FC), danger = Color(0xFFF87171), ok = Color(0xFF34D399),
        grad2 = Color(0xFF120A24),
    ),
    ThemePalette(
        id = "crimson", name = "荣艳红", emoji = "🌺", light = false,
        bg = Color(0xFF471524), bgElev = Color(0xFF4C1A2A), bgElev2 = Color(0xFF5C2235),
        border = Color(0xFF5C2E3E), fg = Color(0xFFF5E8EC), fgMuted = Color(0xFFC09AA6),
        accent = Color(0xFFF87171), danger = Color(0xFFE11D48), ok = Color(0xFF34D399),
        grad2 = Color(0xFF1C0B14),
    ),
)

private val ById: Map<String, ThemePalette> = ThemeCatalog.associateBy { it.id }

/** The catalog row for [id]; anything unknown falls back to `midnight`. */
fun themeById(id: String): ThemePalette = ById[id] ?: ById.getValue("midnight")

/**
 * The row a stored id resolves to. `system` follows the device; `dark` is the
 * pre-catalog spelling of midnight — an older shell wrote it into the shared
 * `quire.db`, so it still resolves rather than snapping to the default.
 */
fun resolveTheme(id: String, systemDark: Boolean): ThemePalette = when (id) {
    "system", "" -> if (systemDark) themeById("midnight") else themeById("light")
    "dark" -> themeById("midnight")
    else -> themeById(id)
}

/**
 * The semantic palette the whole UI reads, derived from one catalog row. This is
 * the Kotlin half of `ui/Colors.slint`'s derivation and follows it token for
 * token: `accent-soft`/`accent-text` are tints of the theme's own accent over
 * its own page rather than AW's separate `tagBg`/`tagFg` pair, because quire
 * spends them on callout bodies and mention chips where the text on top is the
 * primary ink — deriving from [ThemePalette.bg] keeps every theme legible there
 * by construction.
 */
fun colorsFor(p: ThemePalette): QuireColors = QuireColors(
    background = p.bg,
    sidebar = p.bgElev,
    surface = p.bgElev,
    surfaceHover = p.bgElev2,
    surfaceSelected = lerp(p.bgElev2, p.accent, 0.22f),
    card = if (p.light) Color(0x0F000000) else Color(0x14FFFFFF),
    cardBorder = if (p.light) Color(0x14000000) else Color(0x1FFFFFFF),
    codeBackground = lerp(p.bg, Color.Black, if (p.light) 0.03f else 0.35f),
    textPrimary = p.fg,
    textSecondary = lerp(p.fg, p.fgMuted, 0.55f),
    textMuted = p.fgMuted,
    border = p.border,
    borderStrong = lerp(p.border, p.fg, 0.14f),
    divider = p.border,
    accent = p.accent,
    accentSoft = lerp(p.accent, p.bg, 0.84f),
    accentText = if (p.light) lerp(p.accent, Color.Black, 0.25f)
    else lerp(p.accent, Color.White, 0.30f),
    danger = p.danger,
    calloutBackground = lerp(p.accent, p.bg, 0.84f),
    scrim = if (p.light) Color(0x59000000) else Color(0x8C000000),
    isDark = !p.light,
)

/**
 * The palette, ported from the desktop shell's `ui/Colors.slint` — both are
 * derived from the same ActivityWatch table, so a page opened on the phone looks
 * like the page opened on the desktop.
 *
 * Ported rather than re-invented for a reason beyond consistency: those values
 * are *measured*, not chosen. The block swatches were re-tuned so an orange
 * block on its own tint clears 3:1, and `cover-scrim`'s alpha is arithmetic
 * (`ADR-0023`, `ADR-0047` on the desktop side). Retyping them by eye would throw
 * that away.
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

val LocalQuireColors = staticCompositionLocalOf { colorsFor(themeById("midnight")) }

/** The catalog row [QuireTheme] resolved, so a caller can paint its ramp. */
val LocalThemePalette = staticCompositionLocalOf { themeById("midnight") }

/**
 * The page ramp, the Kotlin twin of `Colors.slint`'s `Colors.page`: the four
 * gradient themes paint [ThemePalette.bg] into [ThemePalette.grad2], and the
 * nine flat ones hand back a ramp whose two stops are equal — which draws flat.
 * One rule for both kinds, and one surface for the whole window, so the ramp is
 * continuous behind the chrome instead of restarting per pane.
 */
fun pageBrush(p: ThemePalette): Brush = Brush.verticalGradient(listOf(p.bg, p.grad2))

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
 * @param theme the stored setting: an id from [ThemeCatalog], or `system` to
 *   follow the device. Resolution lives in [resolveTheme].
 */
@Composable
fun QuireTheme(theme: String, content: @Composable () -> Unit) {
    val palette = resolveTheme(theme, isSystemInDarkTheme())
    val colors = colorsFor(palette)
    val dark = colors.isDark

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

    CompositionLocalProvider(
        LocalQuireColors provides colors,
        LocalThemePalette provides palette,
    ) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
