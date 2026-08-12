package stramus.ui

/**
 * Everything Settings → Appearance can change about how the app looks, and the one place that puts it
 * on the page.
 *
 * The colours are *not* a stylesheet here. index.html carries one palette — the light one and its dark
 * counterpart — as the page's own CSS, and that is what paints the first frame before a single line of
 * Kotlin runs; a theme chosen afterwards is written straight onto `<html>` as custom properties, which
 * win over any rule in the sheet. That is what keeps a set of five palettes (each with a light and a
 * dark variant, each crossed with nine accents) from being five hundred lines of CSS repeated in two
 * index.html files: the sheet describes the *shape* of the app, this file describes its colour.
 *
 * The pieces that are not colours — corner radius, card density, whether a wallpaper is up — are
 * stamped as `data-*` attributes instead, because what they change is a handful of measurements the
 * stylesheet is better placed to hold (`:root[data-radius="sharp"]`, and so on).
 */

/** One theme, in one lighting: the eight colours every rule in index.html is written against. */
data class Palette(
    val bg: String,
    val panel: String,
    val border: String,
    val text: String,
    val muted: String,
    val accent: String,
    val accentSoft: String,
    val danger: String,
)

/**
 * A theme family. Each carries both lightings — a palette that only knew how to be dark would leave
 * the Auto/Light/Dark switch above it lying, and the switch is the honest thing to keep.
 *
 * [CUSTOM] is the odd one out: it has no colours of its own beyond the default's, and takes its accent
 * from the colour the user mixed (see [Appearance.customAccent]).
 */
enum class ThemePalette(
    val id: String,
    val light: Palette,
    val dark: Palette,
) {
    DEFAULT(
        "default",
        light = Palette(
            bg = "#f6f7f9", panel = "#ffffff", border = "#e6e8eb", text = "#1c2024",
            muted = "#6b7280", accent = "#3b82f6", accentSoft = "#eaf1fe", danger = "#ef4444",
        ),
        dark = Palette(
            bg = "#16181d", panel = "#1e2127", border = "#2b2f36", text = "#e7e9ea",
            muted = "#9aa1ab", accent = "#4b8bf5", accentSoft = "#22304d", danger = "#f26060",
        ),
    ),

    // Nord: Snow Storm over Polar Night, the palette's own two halves.
    NORD(
        "nord",
        light = Palette(
            bg = "#eceff4", panel = "#fbfcfe", border = "#d8dee9", text = "#2e3440",
            muted = "#6d7c93", accent = "#5e81ac", accentSoft = "#dee7f2", danger = "#bf616a",
        ),
        dark = Palette(
            bg = "#2e3440", panel = "#3b4252", border = "#49505f", text = "#e5e9f0",
            muted = "#a3aec1", accent = "#88c0d0", accentSoft = "#3a4b56", danger = "#bf616a",
        ),
    ),

    // Solarized, both halves of it: base3/base2 over base03/base02, with the palette's blue and cyan.
    SOLARIZED(
        "solarized",
        light = Palette(
            bg = "#fdf6e3", panel = "#fffbf0", border = "#e8dfc4", text = "#073642",
            muted = "#83968f", accent = "#268bd2", accentSoft = "#e2e8de", danger = "#dc322f",
        ),
        dark = Palette(
            bg = "#002b36", panel = "#073642", border = "#14454f", text = "#eee8d5",
            muted = "#93a1a1", accent = "#2aa198", accentSoft = "#0b3f47", danger = "#dc322f",
        ),
    ),

    // Tokyo Night, and its daylight counterpart (Tokyo Night Day).
    TOKYO(
        "tokyo",
        light = Palette(
            bg = "#e1e2e7", panel = "#f4f5f9", border = "#cbd0e0", text = "#3760bf",
            muted = "#6a7ba8", accent = "#2e7de9", accentSoft = "#dde4fb", danger = "#f52a65",
        ),
        dark = Palette(
            bg = "#1a1b26", panel = "#24283b", border = "#32364d", text = "#c0caf5",
            muted = "#8189b3", accent = "#7aa2f7", accentSoft = "#2a3157", danger = "#f7768e",
        ),
    ),

    /** The default's neutrals, coloured by whatever the user mixed themselves. */
    CUSTOM("custom", light = DEFAULT.light, dark = DEFAULT.dark),
    ;

    fun variant(dark: Boolean): Palette = if (dark) this.dark else light

    companion object {
        fun from(id: String?): ThemePalette = entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}

/**
 * The brand colour, offered apart from the theme: the palettes above settle what the app is made of,
 * this settles what is *marked* in it — the selected collection, the primary button, the focus ring.
 *
 * [AUTO] is the palette's own accent, and the default: a theme picked for its colours should not have
 * one of them replaced before it has been seen.
 */
enum class AccentColor(
    val id: String,
    /** Null for [AUTO] — its colour is whatever the chosen palette brought with it. */
    val light: String?,
    val lightSoft: String?,
    val dark: String?,
    val darkSoft: String?,
) {
    AUTO("auto", null, null, null, null),
    BLUE("blue", "#3b82f6", "#eaf1fe", "#4b8bf5", "#22304d"),
    PURPLE("purple", "#8b5cf6", "#f1ecfe", "#a78bfa", "#2e2650"),
    INDIGO("indigo", "#4f46e5", "#e8e7fd", "#818cf8", "#26295c"),
    TEAL("teal", "#0d9488", "#dff4f1", "#2dd4bf", "#123530"),
    GREEN("green", "#16a34a", "#e7f7ed", "#4ade80", "#16321f"),
    AMBER("amber", "#d97706", "#fdf0dc", "#fbbf24", "#3a2f12"),
    ORANGE("orange", "#ea580c", "#fdece0", "#fb923c", "#3a2b12"),
    ROSE("rose", "#e11d48", "#fce7ec", "#fb7185", "#3a1f28"),
    ;

    fun label(s: Strings): String = when (this) {
        AUTO -> s.accentAuto
        BLUE -> s.accentBlue
        PURPLE -> s.accentPurple
        INDIGO -> s.accentIndigo
        TEAL -> s.accentTeal
        GREEN -> s.accentGreen
        AMBER -> s.accentAmber
        ORANGE -> s.accentOrange
        ROSE -> s.accentRose
    }

    companion object {
        fun from(id: String?): AccentColor = entries.firstOrNull { it.id == id } ?: AUTO
    }
}

/** What can stand behind the app: nothing, one of [Gradient]'s presets, or a picture of the user's. */
enum class BackgroundKind(val id: String) {
    NONE("none"),
    GRADIENT("gradient"),
    IMAGE("image"),
    ;

    fun label(s: Strings): String = when (this) {
        NONE -> s.backgroundNone
        GRADIENT -> s.backgroundGradient
        IMAGE -> s.backgroundImage
    }

    companion object {
        fun from(id: String?): BackgroundKind = entries.firstOrNull { it.id == id } ?: NONE
    }
}

/**
 * The wallpapers that need no file: a handful of gradients dark enough to hold light text and light
 * enough to hold dark text, since the panels floating on them are translucent in either lighting.
 */
enum class Gradient(val id: String, val css: String) {
    AURORA("aurora", "linear-gradient(135deg, #5b6ee1 0%, #a855f7 55%, #ec4899 100%)"),
    DUSK("dusk", "linear-gradient(160deg, #1e293b 0%, #4c1d95 60%, #831843 100%)"),
    MINT("mint", "linear-gradient(135deg, #0f766e 0%, #22d3ee 100%)"),
    SUNSET("sunset", "linear-gradient(135deg, #f97316 0%, #ec4899 55%, #7c3aed 100%)"),
    FOREST("forest", "linear-gradient(160deg, #065f46 0%, #064e3b 55%, #052e16 100%)"),
    SLATE("slate", "linear-gradient(160deg, #475569 0%, #1e293b 60%, #0f172a 100%)"),
    ;

    companion object {
        fun from(id: String?): Gradient = entries.firstOrNull { it.id == id } ?: AURORA
    }
}

/** How much room a card takes: the same grid, drawn generously or tightly. */
enum class CardDensity(val id: String) {
    COMFORTABLE("comfortable"),
    COMPACT("compact"),
    ;

    fun label(s: Strings): String = when (this) {
        COMFORTABLE -> s.cardStyleComfortable
        COMPACT -> s.cardStyleCompact
    }

    companion object {
        fun from(id: String?): CardDensity = entries.firstOrNull { it.id == id } ?: COMFORTABLE
    }
}

/** How round everything in the app is — cards, buttons, rows, windows, all off one scale. */
enum class CornerRadius(val id: String) {
    SOFT("soft"),
    MEDIUM("medium"),
    SHARP("sharp"),
    ;

    fun label(s: Strings): String = when (this) {
        SOFT -> s.cornerSoft
        MEDIUM -> s.cornerMedium
        SHARP -> s.cornerSharp
    }

    companion object {
        fun from(id: String?): CornerRadius = entries.firstOrNull { it.id == id } ?: SOFT
    }
}

/**
 * Every appearance preference at once — held together rather than passed around as nine strings,
 * because none of them can be put on the page alone: the accent depends on the palette, the palette on
 * the lighting, and how solid a panel is depends on whether there is a wallpaper behind it.
 */
data class Appearance(
    /** "auto" | "light" | "dark" — the *lighting*, which every palette below has a variant for. */
    val mode: String = "auto",
    val palette: ThemePalette = ThemePalette.DEFAULT,
    val accent: AccentColor = AccentColor.AUTO,
    /** The colour mixed by hand, used by [ThemePalette.CUSTOM] and by nothing else. */
    val customAccent: String = DEFAULT_CUSTOM_ACCENT,
    val density: CardDensity = CardDensity.COMFORTABLE,
    val radius: CornerRadius = CornerRadius.SOFT,
    val background: BackgroundKind = BackgroundKind.NONE,
    val gradient: Gradient = Gradient.AURORA,
    /** The picture behind the app as a `data:` URI, or null where none has been chosen. */
    val image: String? = null,
) {
    /** Whether the app is dark right now: what the user asked for, or — on "auto" — what the OS says. */
    fun isDark(): Boolean = when (mode) {
        "dark" -> true
        "light" -> false
        else -> systemPrefersDark()
    }

    /** Whether anything is standing behind the app — the one thing panel translucency hangs off. */
    fun hasWallpaper(): Boolean = when (background) {
        BackgroundKind.NONE -> false
        BackgroundKind.GRADIENT -> true
        BackgroundKind.IMAGE -> image != null
    }

    /** The colours as they end up: the palette for this lighting, with the chosen accent over it. */
    fun colors(): Palette {
        val dark = isDark()
        val base = palette.variant(dark)
        return when {
            palette == ThemePalette.CUSTOM -> base.copy(
                accent = customAccent,
                accentSoft = rgba(customAccent, if (dark) 0.24 else 0.14),
            )
            accent == AccentColor.AUTO -> base
            else -> base.copy(
                accent = (if (dark) accent.dark else accent.light) ?: base.accent,
                accentSoft = (if (dark) accent.darkSoft else accent.lightSoft) ?: base.accentSoft,
            )
        }
    }

    /** The CSS behind the app: a gradient, a picture, or nothing at all. */
    fun backgroundCss(): String = when (background) {
        BackgroundKind.NONE -> "none"
        BackgroundKind.GRADIENT -> gradient.css
        BackgroundKind.IMAGE -> image?.let { "url(\"$it\")" } ?: "none"
    }
}

/** The colour a custom theme starts from — the app's own blue, so "Custom" opens on familiar ground. */
const val DEFAULT_CUSTOM_ACCENT = "#3b82f6"

/**
 * How much of a panel's own colour survives when there is a wallpaper behind it to show through.
 *
 * `--bg` is the higher of the two stakes here despite being the quieter colour: it is not only the
 * hover wash of a row, it is the wash the content pane itself wears over a wallpaper (index.html), and
 * that pane holds text — a heading, a section title, the "drag things here" hint — with nothing but
 * the picture behind it. Too transparent and that text is grey on somebody's photograph.
 */
private const val PANEL_OVER_WALLPAPER = 0.74
private const val BG_OVER_WALLPAPER = 0.5
private const val BORDER_OVER_WALLPAPER = 0.55

/**
 * The name in the sidebar, and the chip the mark beside it sits on.
 *
 * The brand is one colour and stays one colour — but a deep purple written on a near-black panel is a
 * word nobody can read, so the dark lighting gets the same hue lightened rather than a different
 * colour. The mark has the opposite problem: it is a pale gradient star, which stands out on a dark
 * panel and dissolves into a light one, so on light themes it is given a chip of that same purple to
 * sit on and on dark ones it is left to speak for itself.
 */
private const val BRAND_LIGHT = "#50219B"
private const val BRAND_DARK = "#b39cee"

/*
 * Where each piece of it is kept. `theme` and `accent` were already these two names before there was
 * anything else here, and they keep them: an install that has been choosing a theme for a year has no
 * reason to lose it to a rename.
 */
private const val MODE_PREF = "theme"
private const val PALETTE_PREF = "palette"
private const val ACCENT_PREF = "accent"
private const val CUSTOM_ACCENT_PREF = "accentCustom"
private const val DENSITY_PREF = "cardDensity"
private const val RADIUS_PREF = "cornerRadius"
private const val BACKGROUND_PREF = "background"
private const val GRADIENT_PREF = "bgGradient"
private const val WALLPAPER_PREF = "bgImage"

/** Everything the last session chose, or the defaults for an install that has chosen nothing. */
internal fun loadAppearance(): Appearance = Appearance(
    mode = prefGet(MODE_PREF) ?: "auto",
    palette = ThemePalette.from(prefGet(PALETTE_PREF)),
    accent = AccentColor.from(prefGet(ACCENT_PREF)),
    customAccent = prefGet(CUSTOM_ACCENT_PREF)?.takeIf { isHexColor(it) } ?: DEFAULT_CUSTOM_ACCENT,
    density = CardDensity.from(prefGet(DENSITY_PREF)),
    radius = CornerRadius.from(prefGet(RADIUS_PREF)),
    background = BackgroundKind.from(prefGet(BACKGROUND_PREF)),
    gradient = Gradient.from(prefGet(GRADIENT_PREF)),
    image = prefGet(WALLPAPER_PREF),
)

/**
 * Write it all down for the next open — all of it but the picture, which is written by [saveWallpaper]
 * when it is picked. It is the one preference here measured in hundreds of kilobytes, and rewriting it
 * every time a corner radius changes would be a great deal of copying for no reason at all.
 */
internal fun saveAppearance(a: Appearance) {
    prefSet(MODE_PREF, a.mode)
    prefSet(PALETTE_PREF, a.palette.id)
    prefSet(ACCENT_PREF, a.accent.id)
    prefSet(CUSTOM_ACCENT_PREF, a.customAccent)
    prefSet(DENSITY_PREF, a.density.id)
    prefSet(RADIUS_PREF, a.radius.id)
    prefSet(BACKGROUND_PREF, a.background.id)
    prefSet(GRADIENT_PREF, a.gradient.id)
}

/**
 * Keep [image] as the wallpaper, or — with null — forget the one that was there. Returns whether it
 * was actually kept: a browser is within its rights to refuse a value this size, and a wallpaper that
 * silently failed to save would come back as no wallpaper at all on the next open.
 */
internal fun saveWallpaper(image: String?): Boolean {
    if (image == null) {
        prefRemove(WALLPAPER_PREF)
        return true
    }
    return prefSet(WALLPAPER_PREF, image)
}

/**
 * Put [a] on the page: colours as custom properties on `<html>`, the rest as `data-*` attributes the
 * stylesheet keys off. Called for every change to any of it — this is a handful of attribute writes,
 * not a re-render, so there is nothing to be clever about.
 */
internal fun applyAppearance(a: Appearance) {
    val c = a.colors()
    val dark = a.isDark()
    val wallpaper = a.hasWallpaper()

    // A panel over a wallpaper is glass, not paint: enough of its own colour to hold text, enough of
    // what is behind it to let the picture through. With nothing behind it, it is simply itself —
    // translucency over a flat background would only wash the colours out for no gain.
    setRootVar("--bg", if (wallpaper) rgba(c.bg, BG_OVER_WALLPAPER) else c.bg)
    setRootVar("--panel", if (wallpaper) rgba(c.panel, PANEL_OVER_WALLPAPER) else c.panel)
    setRootVar("--border", if (wallpaper) rgba(c.border, BORDER_OVER_WALLPAPER) else c.border)
    // The opaque originals, for the places that use a panel colour as *ink* rather than as a surface —
    // the tooltip's text, the badge's outline — where translucency would be a bug.
    setRootVar("--panel-solid", c.panel)
    setRootVar("--bg-solid", c.bg)
    setRootVar("--text", c.text)
    setRootVar("--muted", c.muted)
    setRootVar("--accent", c.accent)
    setRootVar("--accent-soft", c.accentSoft)
    setRootVar("--danger", c.danger)
    setRootVar("--brand", if (dark) BRAND_DARK else BRAND_LIGHT)
    setRootVar("--brand-chip", if (dark) "transparent" else BRAND_LIGHT)
    setRootVar("--page-bg", a.backgroundCss())

    // `data-theme` still says what the *user* asked for, not what it resolved to: the stylesheet's own
    // palette (the first frame, before any of this runs) is written against the choice, and the OS
    // media query it falls back to is exactly what "auto" means.
    setRootAttribute("data-theme", a.mode)
    setRootAttribute("data-radius", a.radius.id)
    setRootAttribute("data-density", a.density.id)
    setRootAttribute("data-wallpaper", if (wallpaper) "1" else null)
}

/**
 * `#rrggbb` at [alpha], as `rgba(…)`. Not `color-mix()` and not an eight-digit hex: the value goes
 * into a custom property that ends up in `background`, `border-color` and `box-shadow` alike, and
 * `rgba()` is the one spelling all of them have always understood.
 */
internal fun rgba(hex: String, alpha: Double): String {
    val h = hex.removePrefix("#")
    if (h.length != 6) return hex
    val r = h.substring(0, 2).toIntOrNull(16) ?: return hex
    val g = h.substring(2, 4).toIntOrNull(16) ?: return hex
    val b = h.substring(4, 6).toIntOrNull(16) ?: return hex
    return "rgba($r, $g, $b, $alpha)"
}

/** Whether a string is a colour a `<input type="color">` would hand back: `#rrggbb`, nothing else. */
internal fun isHexColor(value: String): Boolean =
    value.length == 7 && value.startsWith("#") && value.drop(1).all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }
