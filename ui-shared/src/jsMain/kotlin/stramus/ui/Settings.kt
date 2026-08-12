package stramus.ui

import react.ChildrenBuilder
import react.FC
import react.Props
import react.dom.html.ReactHTML.button
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.h3
import react.dom.html.ReactHTML.h4
import react.dom.html.ReactHTML.input
import react.dom.html.ReactHTML.label
import react.dom.html.ReactHTML.option
import react.dom.html.ReactHTML.p
import react.dom.html.ReactHTML.select
import react.dom.html.ReactHTML.span
import react.useState
import stramus.core.platform.AiAvailability
import web.cssom.ClassName
import web.html.InputType

/** The idle timeouts offered for auto-locking a section; 0 = never lock on its own. */
private val AUTO_LOCK_CHOICES = listOf(1, 5, 15, 30, 60, 0)

/** The default: five minutes away from the machine and an unlocked section shuts itself again. */
const val DEFAULT_AUTO_LOCK_MINUTES = 5

/** An `<input type="color">` — the same unsafeCast the file picker needs, for the same reason. */
private val COLOR_INPUT_TYPE: InputType = "color".unsafeCast<InputType>()

external interface SettingsModalProps : Props {
    var strings: Strings

    /** How the app looks, whole — see [Appearance]. Every control in the Appearance pane changes one
     *  field of it and hands the result back through [onAppearanceChange]. */
    var appearance: Appearance
    var onAppearanceChange: (Appearance) -> Unit

    /** A picture the user picked to stand behind the app, as the browser handed it over. It is scaled
     *  down and stored by the app, not here — see `makeWallpaper`. */
    var onWallpaperPick: (mime: String, dataUri: String) -> Unit
    var onWallpaperClear: () -> Unit

    /** Whether the last picture picked was too large to keep even after being scaled down. */
    var wallpaperRejected: Boolean

    /** Current language id: "en" | "ru". */
    var lang: String
    var onLangChange: (String) -> Unit

    /** Whether a link card spells its address out under its title. Off by default. */
    var showCardUrls: Boolean
    var onShowCardUrlsChange: (Boolean) -> Unit

    /** Whether the sections sidebar sits on the right instead of its usual left, and the tabs/history
     *  sidebar on the left instead of its usual right. Only worth offering where there is a second
     *  sidebar to swap places with. */
    var hasRightSidebar: Boolean
    var swapSidebars: Boolean
    var onSwapSidebarsChange: (Boolean) -> Unit

    /** Whether the tabs sidebar shows its rows as a grid of cards — the same shape and width as the
     *  middle pane's saved cards — instead of its usual list. Same guard as [swapSidebars]: nothing to
     *  offer without a tabs sidebar to reshape. */
    var tabsCardView: Boolean
    var onTabsCardViewChange: (Boolean) -> Unit

    /** Whether a collection's card sections are drawn as folders — closed tiles in a grid, each opening
     *  out where it stands — instead of one open section under another. Unlike the two above this is
     *  about the middle pane, which every host has, so it is offered everywhere. */
    var groupsFolderView: Boolean
    var onGroupsFolderViewChange: (Boolean) -> Unit

    /** Whether the browsing statistics go up to the account with everything else. Off unless asked for.
     *  Meaningless — and hidden — where there is no account for it to go up to; see [signedIn]. */
    var syncUsage: Boolean
    var onSyncUsageChange: (Boolean) -> Unit

    /** Whether this browser is signed into an account right now. Settles what the Account pane offers:
     *  a door in, or the account controls (and the statistics switch, which needs an account to mean
     *  anything) on the way out. */
    var signedIn: Boolean
    /** The signed-in address, for display. Null while signed out. */
    var accountEmail: String?
    var onSignIn: () -> Unit
    var onSignOut: () -> Unit

    /** Whether the server answered the last health check. Greys out the sign-in door: there is no
     *  point opening it on a server that would only send back an error. Signing out stays open — it
     *  is a local decision the server cannot stand in the way of. */
    var serverOnline: Boolean

    /** What the page opens on: "last" | "first". See [StartView]. */
    var startView: String
    var onStartViewChange: (String) -> Unit
    /** Minutes of inactivity before unlocked sections lock again; 0 = never. */
    var autoLockMinutes: Int
    var onAutoLockChange: (Int) -> Unit

    /** Whether the host gives access to the browser's tabs at all — the web app has none to settle. */
    var hasTabs: Boolean

    /** Whether saving a whole window's tabs into a collection also closes them in the browser. */
    var closeSavedTabs: Boolean
    var onCloseSavedTabsChange: (Boolean) -> Unit

    /**
     * Whether the ✨ that sorts a window's tabs with the built-in model is offered at all. Off until
     * asked for: it is the one thing here that hands a window of titles to a model and takes minutes
     * doing it, and what it hands back is a proposal that is right about *most* of a window on a good
     * day. That is worth having and it is not worth defaulting to.
     */
    var aiTriage: Boolean
    var onAiTriageChange: (Boolean) -> Unit

    /**
     * Whether the tab triage asks the cloud model (currently GPT-5.6 Luna) instead of the one on this
     * machine. A second, narrower opt-in on top of [aiTriage] — that switch must also be on, and this
     * one is hidden entirely for an account that is not signed in ([signedIn]), since it is meaningless
     * without one: the request is answered on the server, on that account's own hundred-a-month.
     */
    var aiTriageCloud: Boolean
    var onAiTriageCloudChange: (Boolean) -> Unit

    /** Who answers a question from the search box: "local" | "chatgpt" | "gemini" | "claude". */
    var aiProvider: String
    var onAiProviderChange: (String) -> Unit

    /**
     * Whether the browser's own model can answer at all. Where it cannot, choosing it would be choosing
     * silence: the option is dead, and the row underneath says why.
     */
    var aiLocalAvailable: Boolean

    /** The built-in model, named — or null where the browser has none. Only shown for the local one. */
    var aiName: String?

    /** Its state, so "no AI here" can say *why*: no such browser API, or a machine that cannot run it. */
    var aiState: AiAvailability?

    var onExportCsv: () -> Unit
    var onExportBookmarks: () -> Unit

    /** A file the user picked to import, by name and contents. See `importFile`. */
    var onImport: (name: String, text: String) -> Unit

    /** What the last import did, in the user's words — null until one has been done. */
    var importStatus: String?
    var onClose: () -> Unit
}

/**
 * The panes of the settings page, in sidebar order. Each carries the glyph and title its nav button
 * wears; which pane is showing is [SettingsModal]'s only piece of local state. Some panes are not
 * always there — [TABS] only where the host has tabs to settle — so the list is filtered per host
 * before it is drawn.
 */
private enum class SettingsTab(val icon: String, val title: (Strings) -> String) {
    ACCOUNT("user", { it.account }),
    APPEARANCE("palette", { it.appearance }),
    STARTUP("rocket", { it.startupSection }),
    TABS("layout", { it.tabsSection }),
    SECURITY("lock", { it.security }),
    AI("sparkles", { it.aiSection }),
    DATA("save", { it.dataSection }),
    ABOUT("info", { it.about }),
}

/** What the settings page says about the model: which one, and whether it can actually answer. */
private fun aiStatusOf(name: String?, state: AiAvailability?, s: Strings): Pair<String, String> = when {
    name == null -> s.aiModelNone to s.aiModelNoneHint
    state == AiAvailability.UNAVAILABLE || state == null -> s.aiModelUnsupported(name) to s.aiModelUnsupportedHint
    state == AiAvailability.DOWNLOADABLE -> name to s.aiModelDownloadableHint
    state == AiAvailability.DOWNLOADING -> name to s.aiModelDownloadingHint
    else -> name to s.aiModelReadyHint
}

/**
 * A settings row whose control is the theme picker's segmented button strip: a label on the left,
 * one button per [choices] entry on the right, the one equal to [current] worn active. The workhorse
 * of this page — theme, language, and every on/off question are all this same shape.
 */
private fun <T> ChildrenBuilder.toggleRow(
    title: String,
    hint: String,
    current: T,
    choices: List<Pair<T, String>>,
    onPick: (T) -> Unit,
    titleExtra: (ChildrenBuilder.() -> Unit)? = null,
    // Only the theme picker tells its three choices apart with an icon as well as a word; every other
    // row here is plain text, so this stays null and unused for all of them.
    icons: List<String>? = null,
) {
    div {
        className = ClassName("settings-row")
        div {
            className = ClassName("settings-label")
            span {
                className = ClassName("settings-title")
                +title
                titleExtra?.invoke(this)
            }
            span { className = ClassName("settings-hint"); +hint }
        }
        div {
            className = ClassName("theme-toggle")
            choices.forEachIndexed { i, (value, label) ->
                button {
                    className = ClassName(if (current == value) "theme-opt active" else "theme-opt")
                    onClick = { onPick(value) }
                    icons?.getOrNull(i)?.let { icon(it); +" " }
                    +label
                }
            }
        }
    }
}

/**
 * One tile of the theme grid. A tile is not quite a palette: the first two are the default palette in
 * one lighting or the other, which is what "Light" and "Dark" have always meant here, while the rest
 * are palettes that keep whichever lighting the switch above is set to. [mode] is null for those.
 */
private class ThemeTile(val palette: ThemePalette, val mode: String?, val label: (Strings) -> String)

private val THEME_TILES = listOf(
    ThemeTile(ThemePalette.DEFAULT, "light") { it.themeLight },
    ThemeTile(ThemePalette.DEFAULT, "dark") { it.themeDark },
    // Proper nouns, and so the same word in every language the app speaks.
    ThemeTile(ThemePalette.NORD, null) { "Nord" },
    ThemeTile(ThemePalette.SOLARIZED, null) { "Solarized" },
    ThemeTile(ThemePalette.TOKYO, null) { "Tokyo Night" },
    ThemeTile(ThemePalette.CUSTOM, null) { it.themeCustom },
)

/** Whether [tile] is the one the app is wearing right now. */
private fun ThemeTile.isCurrent(a: Appearance): Boolean = when {
    palette != a.palette -> false
    // The two default tiles are told apart by the lighting, and "auto" lands on whichever one the OS
    // has actually produced — the grid shows what is on screen, not what was typed into a preference.
    mode != null -> (mode == "dark") == a.isDark()
    else -> true
}

/**
 * The theme grid: one tile per [THEME_TILES] entry, each a miniature of the app drawn in the colours it
 * would bring. A palette is a thing to look at, not a word to read — six names in a row would say
 * nothing at all about what picking one does.
 *
 * The miniature is the palette's own colours handed to CSS as custom properties; the shapes it draws
 * them as (`.tt-*`) live in index.html with the rest of the app's look.
 */
private fun ChildrenBuilder.themeGrid(a: Appearance, onPick: (Appearance) -> Unit, s: Strings) {
    div {
        className = ClassName("theme-grid")
        THEME_TILES.forEach { tile ->
            val dark = tile.mode?.let { it == "dark" } ?: a.isDark()
            // Custom has no colours of its own beyond the default's, so its tile shows what it *would*
            // be: the neutrals plus the colour the user has mixed.
            val colors = when (tile.palette) {
                ThemePalette.CUSTOM -> a.copy(palette = ThemePalette.CUSTOM, mode = if (dark) "dark" else "light").colors()
                else -> tile.palette.variant(dark)
            }
            button {
                className = ClassName(if (tile.isCurrent(a)) "theme-tile active" else "theme-tile")
                onClick = {
                    onPick(a.copy(palette = tile.palette, mode = tile.mode ?: a.mode))
                }
                val css = js("({})")
                css["--t-bg"] = colors.bg
                css["--t-panel"] = colors.panel
                css["--t-border"] = colors.border
                css["--t-accent"] = colors.accent
                css["--t-muted"] = colors.muted
                asDynamic().style = css
                span {
                    className = ClassName("theme-tile-art")
                    span {
                        className = ClassName("tt-side")
                        span { className = ClassName("tt-dot") }
                        span { className = ClassName("tt-line") }
                        span { className = ClassName("tt-line short") }
                    }
                    span {
                        className = ClassName("tt-main")
                        span { className = ClassName("tt-bar") }
                        span {
                            className = ClassName("tt-cards")
                            repeat(4) { span { className = ClassName("tt-card") } }
                        }
                    }
                }
                span { className = ClassName("theme-tile-name"); +tile.label(s) }
            }
        }
    }
}

/**
 * The accent picker: a row of round swatches, one per [AccentColor], each drawn in its own colour
 * rather than named — unlike [toggleRow]'s text buttons, the choice here *is* a colour, so showing it
 * beats spelling it. The first swatch is the theme's own accent, which is what a palette picked for
 * its colours should keep until something else is asked for.
 */
private fun ChildrenBuilder.accentRow(a: Appearance, onPick: (Appearance) -> Unit, s: Strings) {
    div {
        className = ClassName("settings-row")
        div {
            className = ClassName("settings-label")
            span { className = ClassName("settings-title"); +s.accentColor }
            span { className = ClassName("settings-hint"); +s.accentColorHint }
        }
        div {
            className = ClassName("accent-swatches")
            val dark = a.isDark()
            AccentColor.entries.forEach { color ->
                button {
                    className = ClassName(if (a.accent == color) "accent-swatch active" else "accent-swatch")
                    hint(color.label(s))
                    val css = js("({})")
                    css.background = when (color) {
                        // The theme's own: whatever the chosen palette brought with it.
                        AccentColor.AUTO -> a.palette.variant(dark).accent
                        else -> (if (dark) color.dark else color.light)
                    }
                    asDynamic().style = css
                    onClick = { onPick(a.copy(accent = color)) }
                }
            }
        }
    }
}

/**
 * The one control that is not a choice among things we picked: the colour behind a custom theme, as a
 * native colour well. Only offered for [ThemePalette.CUSTOM] — anywhere else it would quietly do
 * nothing, since a palette's accent is either its own or one of the swatches above.
 */
private fun ChildrenBuilder.customAccentRow(a: Appearance, onPick: (Appearance) -> Unit, s: Strings) {
    div {
        className = ClassName("settings-row")
        div {
            className = ClassName("settings-label")
            span { className = ClassName("settings-title"); +s.accentCustom }
            span { className = ClassName("settings-hint"); +s.accentCustomHint }
        }
        div {
            className = ClassName("color-well")
            input {
                type = COLOR_INPUT_TYPE
                className = ClassName("color-input")
                value = a.customAccent
                onChange = { e ->
                    val picked = e.target.value
                    if (isHexColor(picked)) onPick(a.copy(customAccent = picked))
                }
            }
            span { className = ClassName("color-hex"); +a.customAccent.uppercase() }
        }
    }
}

/**
 * What stands behind the app: nothing, one of the gradients, or a picture of the user's own. The
 * choice comes first, and only what it needs is drawn under it — a row of gradients nobody is using,
 * or the file picker and whatever is currently up.
 */
private fun ChildrenBuilder.backgroundRows(props: SettingsModalProps, s: Strings) {
    val a = props.appearance
    val onPick = props.onAppearanceChange

    toggleRow(
        s.background, s.backgroundHint, a.background,
        BackgroundKind.entries.map { it to it.label(s) },
        { kind ->
            // Choosing "Image" with no picture yet is a request to pick one, not a background: it is
            // kept as the chosen kind so the picker below appears, and `hasWallpaper()` still says no.
            onPick(a.copy(background = kind))
        },
    )

    if (a.background == BackgroundKind.GRADIENT) {
        div {
            className = ClassName("settings-row")
            div {
                className = ClassName("gradient-swatches")
                Gradient.entries.forEach { gradient ->
                    button {
                        className = ClassName(
                            if (a.gradient == gradient) "gradient-swatch active" else "gradient-swatch"
                        )
                        val css = js("({})")
                        css.backgroundImage = gradient.css
                        asDynamic().style = css
                        onClick = { onPick(a.copy(gradient = gradient)) }
                    }
                }
            }
        }
    }

    if (a.background == BackgroundKind.IMAGE) {
        div {
            className = ClassName("settings-row")
            div {
                className = ClassName("settings-label")
                span { className = ClassName("settings-title"); +s.backgroundPicture }
                span {
                    className = ClassName("settings-hint")
                    +(if (props.wallpaperRejected) s.backgroundTooLarge else s.backgroundPictureHint)
                }
            }
            div {
                className = ClassName("settings-actions wallpaper-actions")
                a.image?.let { image ->
                    span {
                        className = ClassName("wallpaper-preview")
                        val css = js("({})")
                        css.backgroundImage = "url(\"$image\")"
                        asDynamic().style = css
                    }
                }
                label {
                    className = ClassName("btn")
                    icon("upload")
                    +" ${if (a.image == null) s.backgroundPick else s.backgroundReplace}"
                    input {
                        type = FILE_INPUT_TYPE
                        className = ClassName("hidden-file-input")
                        accept = "image/*"
                        onChange = { e ->
                            readPickedFile(e.target) { _, mime, dataUri -> props.onWallpaperPick(mime, dataUri) }
                        }
                    }
                }
                if (a.image != null) {
                    button {
                        className = ClassName("btn")
                        onClick = { props.onWallpaperClear() }
                        +s.backgroundRemove
                    }
                }
            }
        }
    }
}

private fun ChildrenBuilder.appearancePane(props: SettingsModalProps, s: Strings) {
    val a = props.appearance
    val onPick = props.onAppearanceChange

    div {
        className = ClassName("settings-section")
        h4 { +s.theme }

        toggleRow(
            s.themeMode, s.themeHint, a.mode,
            listOf("auto" to s.themeAuto, "light" to s.themeLight, "dark" to s.themeDark),
            { mode -> onPick(a.copy(mode = mode)) },
            icons = listOf("circle-half", "sun", "moon"),
        )

        themeGrid(a, onPick, s)

        // One or the other, never both: a custom theme *is* its accent, so a row of swatches beside
        // the colour well would be nine buttons that quietly do nothing.
        if (a.palette == ThemePalette.CUSTOM) customAccentRow(a, onPick, s) else accentRow(a, onPick, s)

        toggleRow(
            s.cardStyle, s.cardStyleHint, a.density,
            CardDensity.entries.map { it to it.label(s) },
            { density -> onPick(a.copy(density = density)) },
        )

        toggleRow(
            s.cornerRadius, s.cornerRadiusHint, a.radius,
            CornerRadius.entries.map { it to it.label(s) },
            { radius -> onPick(a.copy(radius = radius)) },
        )

        backgroundRows(props, s)
    }

    div {
        className = ClassName("settings-section")
        h4 { +s.appearance }

        toggleRow(
            s.language, s.languageHint, props.lang,
            Lang.entries.map { it.id to it.label },
            props.onLangChange,
        )

        toggleRow(
            s.cardUrls, s.cardUrlsHint, props.showCardUrls,
            listOf(false to s.cardUrlsHide, true to s.cardUrlsShow),
            props.onShowCardUrlsChange,
        )

        toggleRow(
            s.groupsView, s.groupsViewHint, props.groupsFolderView,
            listOf(false to s.groupsViewList, true to s.groupsViewFolders),
            props.onGroupsFolderViewChange,
            titleExtra = { span { className = ClassName("settings-badge"); +s.experimental } },
        )

        // Only worth offering where there is a second sidebar to trade places with — the web app's
        // lone left sidebar has nothing to swap.
        if (props.hasRightSidebar) {
            toggleRow(
                s.swapSidebars, s.swapSidebarsHint, props.swapSidebars,
                listOf(false to s.swapSidebarsLeft, true to s.swapSidebarsRight),
                props.onSwapSidebarsChange,
            )

            toggleRow(
                s.tabsCardView, s.tabsCardViewHint, props.tabsCardView,
                listOf(false to s.tabsCardViewList, true to s.tabsCardViewCards),
                props.onTabsCardViewChange,
            )
        }
    }
}

// Collections are things the user chose to keep. The statistics are a trace of what they did —
// which pages, how often — and that is a different kind of thing to hand a server. So it is a
// question, asked once, answered "no" until they say otherwise.
//
// The switch only means anything once there is an account for the statistics to go up to — signed
// out, there is no server on the other end of it, so the row is replaced by the door in rather than
// shown disabled or, worse, left on the screen turning a setting that does nothing.
private fun ChildrenBuilder.accountPane(props: SettingsModalProps, s: Strings) {
    div {
        className = ClassName("settings-section")
        h4 { +s.account }

        if (props.signedIn) {
            props.accountEmail?.let { p { className = ClassName("settings-hint"); +it } }
            div {
                className = ClassName("settings-actions")
                button { className = ClassName("btn"); onClick = { props.onSignOut() }; +s.signOut }
            }
            toggleRow(
                s.syncUsage, s.syncUsageHint, props.syncUsage,
                listOf(false to s.optionOff, true to s.optionOn),
                props.onSyncUsageChange,
            )
        } else {
            p { className = ClassName("settings-hint"); +s.accountSignedOutHint }
            div {
                className = ClassName("settings-actions")
                button {
                    className = ClassName("btn")
                    disabled = !props.serverOnline
                    onClick = { props.onSignIn() }
                    +s.signInAccount
                }
            }
            if (!props.serverOnline) {
                p { className = ClassName("settings-hint"); +s.serverUnavailable }
            }
        }
    }
}

private fun ChildrenBuilder.startupPane(props: SettingsModalProps, s: Strings) {
    div {
        className = ClassName("settings-section")
        h4 { +s.startupSection }
        toggleRow(
            s.startView, s.startViewHint, props.startView,
            StartView.entries.map { it.id to it.label(s) },
            props.onStartViewChange,
        )
    }
}

// Only reached where there are tabs to save: the web app cannot see the browser's, and the ⤓ that
// this settles is not on its screen.
private fun ChildrenBuilder.tabsPane(props: SettingsModalProps, s: Strings) {
    div {
        className = ClassName("settings-section")
        h4 { +s.tabsSection }

        toggleRow(
            s.closeSavedTabs, s.closeSavedTabsHint, props.closeSavedTabs,
            listOf(true to s.closeSavedTabsClose, false to s.closeSavedTabsKeep),
            props.onCloseSavedTabsChange,
        )

        // Only where there is a model to do it: on a browser without one the switch would turn on a
        // button that could never appear.
        if (props.aiLocalAvailable) {
            toggleRow(
                s.aiTriageSetting, s.aiTriageSettingHint, props.aiTriage,
                listOf(true to s.on, false to s.off),
                props.onAiTriageChange,
                // Said plainly, and next to the name rather than buried in the hint: what this turns
                // on is not finished, and the user is agreeing to that and not merely to a feature.
                titleExtra = { span { className = ClassName("settings-badge"); +s.experimental } },
            )
        }

        // Hidden rather than merely disabled where there is no account: a switch nobody signed in could
        // ever turn on is not a setting, it is a question the page has no business asking yet. And it
        // only means anything once the feature above is itself on — the model this asks is a choice
        // about triage, not a second way to turn triage on.
        if (props.aiTriage && props.signedIn) {
            toggleRow(
                s.aiTriageCloudSetting, s.aiTriageCloudSettingHint, props.aiTriageCloud,
                listOf(true to s.on, false to s.off),
                props.onAiTriageCloudChange,
            )
        }
    }
}

private fun ChildrenBuilder.securityPane(props: SettingsModalProps, s: Strings) {
    div {
        className = ClassName("settings-section")
        h4 { +s.security }
        div {
            className = ClassName("settings-row")
            div {
                className = ClassName("settings-label")
                span { className = ClassName("settings-title"); +s.autoLock }
                span { className = ClassName("settings-hint"); +s.autoLockHint }
            }
            select {
                className = ClassName("control")
                value = props.autoLockMinutes.toString()
                onChange = { e -> props.onAutoLockChange(e.target.value.toIntOrNull() ?: DEFAULT_AUTO_LOCK_MINUTES) }
                AUTO_LOCK_CHOICES.forEach { minutes ->
                    option {
                        value = minutes.toString()
                        +(if (minutes == 0) s.autoLockNever else s.autoLockMinutes(minutes))
                    }
                }
            }
        }
    }
}

// It is nobody's business but the user's what is answering them — and whether it answers here or
// somewhere on the web. So this is where that is chosen, and where the built-in model says which one
// it is, where it runs, and — when it cannot answer at all — why not.
private fun ChildrenBuilder.aiPane(props: SettingsModalProps, s: Strings) {
    div {
        className = ClassName("settings-section")
        h4 { +s.aiSection }
        val provider = AiProvider.from(props.aiProvider)
        div {
            className = ClassName("settings-row")
            div {
                className = ClassName("settings-label")
                span { className = ClassName("settings-title"); +s.aiAssistant }
                span { className = ClassName("settings-hint"); +s.aiAssistantHint }
            }
            div {
                // Four options: wider than the theme picker's two, but the same control.
                className = ClassName("theme-toggle")
                AiProvider.entries.forEach { option ->
                    // A model this browser cannot run is not a choice to offer — it is greyed out,
                    // and the row underneath says what is wrong with it.
                    val dead = option == AiProvider.LOCAL && !props.aiLocalAvailable
                    button {
                        className = ClassName(if (provider == option) "theme-opt active" else "theme-opt")
                        disabled = dead
                        onClick = { props.onAiProviderChange(option.id) }
                        +option.label(s)
                    }
                }
            }
        }

        // What there is to say about the built-in model: which one it is, and — when it cannot
        // answer — why not, which is *also* said when it is not the one answering, since that is the
        // whole reason a web chat is. The web chats themselves have nothing to report: they are the
        // user's own accounts, and all this app does with them is open one.
        if (provider == AiProvider.LOCAL || !props.aiLocalAvailable) {
            val (title, hint) = aiStatusOf(props.aiName, props.aiState, s)
            div {
                className = ClassName("settings-row")
                div {
                    className = ClassName("settings-label")
                    span { className = ClassName("settings-title"); +s.aiModel }
                    span { className = ClassName("settings-hint"); +hint }
                }
                span { className = ClassName("ai-model"); +title }
            }
        }
        if (provider != AiProvider.LOCAL) {
            div {
                className = ClassName("settings-row")
                div {
                    className = ClassName("settings-label")
                    span { className = ClassName("settings-title"); +provider.label(s) }
                    span { className = ClassName("settings-hint"); +s.aiWebChatHint(provider.label(s)) }
                }
            }
        }
    }
}

private fun ChildrenBuilder.dataPane(props: SettingsModalProps, s: Strings) {
    // ---- Export ----
    div {
        className = ClassName("settings-section")
        h4 { +s.export }
        p { className = ClassName("settings-hint"); +s.exportHint }
        div {
            className = ClassName("settings-actions")
            button {
                className = ClassName("btn")
                onClick = { props.onExportCsv() }
                icon("upload")
                +" ${s.exportCsv}"
            }
            button {
                className = ClassName("btn")
                onClick = { props.onExportBookmarks() }
                icon("upload")
                +" ${s.exportBookmarks}"
            }
        }
    }

    // ---- Import: the way back in, and the way in from another browser ----
    //
    // The button is a <label> over a hidden file input — the only way to open the file dialog without
    // one, and the reason this reads as a button while the input itself is never seen.
    div {
        className = ClassName("settings-section")
        h4 { +s.import }
        p { className = ClassName("settings-hint"); +s.importHint }
        div {
            className = ClassName("settings-actions")
            label {
                className = ClassName("btn")
                icon("download")
                +" ${s.importFile}"
                input {
                    type = FILE_INPUT_TYPE
                    className = ClassName("hidden-file-input")
                    accept = ".html,.htm,.csv,text/html,text/csv"
                    onChange = { e ->
                        readPickedText(e.target) { name, text -> props.onImport(name, text) }
                    }
                }
            }
        }
        props.importStatus?.let { status ->
            p { className = ClassName("settings-hint"); +status }
        }
    }
}

/**
 * The settings "page": a modal opened from the left sidebar footer. Groups app-wide preferences and
 * data export (theme, language, CSV export, bookmarks export) that used to live in the content
 * toolbar. Its own left sidebar names the panes; only the chosen one is drawn.
 */
val SettingsModal = FC<SettingsModalProps> { props ->
    val s = props.strings

    // The tabs actually on offer for this host: everything, minus the ones that would settle nothing
    // here (the web app has no browser tabs to close, so it shows no Tabs pane).
    val tabs = SettingsTab.entries.filter { it != SettingsTab.TABS || props.hasTabs }
    var active by useState(SettingsTab.ACCOUNT)
    // A host that dropped the active pane out from under us (unlikely — hasTabs is fixed per host, but
    // cheap to be safe): fall back to the first pane there is.
    if (active !in tabs) active = tabs.first()

    modalShell(props.onClose, "modal settings-modal") {
        div {
            className = ClassName("modal-head")
            h3 { +s.settings }
            button { className = ClassName("icon del"); onClick = { props.onClose() }; icon("x") }
        }

        div {
            className = ClassName("settings-layout")

            div {
                className = ClassName("settings-nav")
                tabs.forEach { tab ->
                    button {
                        className = ClassName(if (tab == active) "settings-nav-item active" else "settings-nav-item")
                        onClick = { active = tab }
                        span { className = ClassName("settings-nav-icon"); icon(tab.icon) }
                        +tab.title(s)
                    }
                }
            }

            div {
                className = ClassName("settings-body")
                when (active) {
                    SettingsTab.APPEARANCE -> appearancePane(props, s)
                    SettingsTab.ACCOUNT -> accountPane(props, s)
                    SettingsTab.STARTUP -> startupPane(props, s)
                    SettingsTab.TABS -> tabsPane(props, s)
                    SettingsTab.SECURITY -> securityPane(props, s)
                    SettingsTab.AI -> aiPane(props, s)
                    SettingsTab.DATA -> dataPane(props, s)
                    SettingsTab.ABOUT -> aboutPane(s)
                }
            }
        }

        div {
            className = ClassName("modal-actions")
            button { className = ClassName("btn"); onClick = { props.onClose() }; +s.close }
        }
    }
}
