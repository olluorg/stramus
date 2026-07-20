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

/** The idle timeouts offered for auto-locking a section; 0 = never lock on its own. */
private val AUTO_LOCK_CHOICES = listOf(1, 5, 15, 30, 60, 0)

/** The default: five minutes away from the machine and an unlocked section shuts itself again. */
const val DEFAULT_AUTO_LOCK_MINUTES = 5

external interface SettingsModalProps : Props {
    var strings: Strings
    /** Current theme id: "auto" | "light" | "dark". */
    var theme: String
    var onThemeChange: (String) -> Unit
    /** Current language id: "en" | "ru". */
    var lang: String
    var onLangChange: (String) -> Unit

    /** Whether a link card spells its address out under its title. Off by default. */
    var showCardUrls: Boolean
    var onShowCardUrlsChange: (Boolean) -> Unit

    /** Whether the browsing statistics go up to the account with everything else. Off unless asked for. */
    var syncUsage: Boolean
    var onSyncUsageChange: (Boolean) -> Unit

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
    APPEARANCE("🎨", { it.appearance }),
    ACCOUNT("👤", { it.account }),
    STARTUP("🚀", { it.startupSection }),
    TABS("🗂", { it.tabsSection }),
    SECURITY("🔒", { it.security }),
    AI("✨", { it.aiSection }),
    DATA("💾", { it.dataSection }),
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
            choices.forEach { (value, label) ->
                button {
                    className = ClassName(if (current == value) "theme-opt active" else "theme-opt")
                    onClick = { onPick(value) }
                    +label
                }
            }
        }
    }
}

private fun ChildrenBuilder.appearancePane(props: SettingsModalProps, s: Strings) {
    div {
        className = ClassName("settings-section")
        h4 { +s.appearance }

        toggleRow(
            s.theme, s.themeHint, props.theme,
            listOf("auto" to s.themeAuto, "light" to s.themeLight, "dark" to s.themeDark),
            props.onThemeChange,
        )

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
    }
}

// Collections are things the user chose to keep. The statistics are a trace of what they did —
// which pages, how often — and that is a different kind of thing to hand a server. So it is a
// question, asked once, answered "no" until they say otherwise.
private fun ChildrenBuilder.accountPane(props: SettingsModalProps, s: Strings) {
    div {
        className = ClassName("settings-section")
        h4 { +s.account }
        toggleRow(
            s.syncUsage, s.syncUsageHint, props.syncUsage,
            listOf(false to s.optionOff, true to s.optionOn),
            props.onSyncUsageChange,
        )
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
            button { className = ClassName("btn"); onClick = { props.onExportCsv() }; +s.exportCsv }
            button { className = ClassName("btn"); onClick = { props.onExportBookmarks() }; +s.exportBookmarks }
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
                +s.importFile
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
    var active by useState(SettingsTab.APPEARANCE)
    // A host that dropped the active pane out from under us (unlikely — hasTabs is fixed per host, but
    // cheap to be safe): fall back to the first pane there is.
    if (active !in tabs) active = tabs.first()

    modalShell(props.onClose, "modal settings-modal") {
        div {
            className = ClassName("modal-head")
            h3 { +s.settings }
            button { className = ClassName("icon del"); onClick = { props.onClose() }; +"×" }
        }

        div {
            className = ClassName("settings-layout")

            div {
                className = ClassName("settings-nav")
                tabs.forEach { tab ->
                    button {
                        className = ClassName(if (tab == active) "settings-nav-item active" else "settings-nav-item")
                        onClick = { active = tab }
                        span { className = ClassName("settings-nav-icon"); +tab.icon }
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
                }
            }
        }

        div {
            className = ClassName("modal-actions")
            button { className = ClassName("btn"); onClick = { props.onClose() }; +s.close }
        }
    }
}
