package stramus.ui

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

/** What the settings page says about the model: which one, and whether it can actually answer. */
private fun aiStatusOf(name: String?, state: AiAvailability?, s: Strings): Pair<String, String> = when {
    name == null -> s.aiModelNone to s.aiModelNoneHint
    state == AiAvailability.UNAVAILABLE || state == null -> s.aiModelUnsupported(name) to s.aiModelUnsupportedHint
    state == AiAvailability.DOWNLOADABLE -> name to s.aiModelDownloadableHint
    state == AiAvailability.DOWNLOADING -> name to s.aiModelDownloadingHint
    else -> name to s.aiModelReadyHint
}

/**
 * The settings "page": a modal opened from the left sidebar footer. Groups app-wide preferences and
 * data export (theme, language, CSV export, bookmarks export) that used to live in the content toolbar.
 */
val SettingsModal = FC<SettingsModalProps> { props ->
    val s = props.strings

    modalShell(props.onClose, "modal settings-modal") {
        div {
            className = ClassName("modal-head")
            h3 { +s.settings }
            button { className = ClassName("icon del"); onClick = { props.onClose() }; +"×" }
        }

        div {
            className = ClassName("settings-body")

            // ---- Appearance ----
            div {
                className = ClassName("settings-section")
                h4 { +s.appearance }
                div {
                    className = ClassName("settings-row")
                    div {
                        className = ClassName("settings-label")
                        span { className = ClassName("settings-title"); +s.theme }
                        span { className = ClassName("settings-hint"); +s.themeHint }
                    }
                    div {
                        className = ClassName("theme-toggle")
                        listOf("auto" to s.themeAuto, "light" to s.themeLight, "dark" to s.themeDark)
                            .forEach { (id, label) ->
                                button {
                                    className = ClassName(if (props.theme == id) "theme-opt active" else "theme-opt")
                                    onClick = { props.onThemeChange(id) }
                                    +label
                                }
                            }
                    }
                }

                // ---- Language ----
                div {
                    className = ClassName("settings-row")
                    div {
                        className = ClassName("settings-label")
                        span { className = ClassName("settings-title"); +s.language }
                        span { className = ClassName("settings-hint"); +s.languageHint }
                    }
                    div {
                        // Same segmented control as the theme picker — two options fit comfortably.
                        className = ClassName("theme-toggle")
                        Lang.entries.forEach { lang ->
                            button {
                                className =
                                    ClassName(if (props.lang == lang.id) "theme-opt active" else "theme-opt")
                                onClick = { props.onLangChange(lang.id) }
                                +lang.label
                            }
                        }
                    }
                }

                // ---- Whether a card says where it goes ----
                div {
                    className = ClassName("settings-row")
                    div {
                        className = ClassName("settings-label")
                        span { className = ClassName("settings-title"); +s.cardUrls }
                        span { className = ClassName("settings-hint"); +s.cardUrlsHint }
                    }
                    div {
                        // The theme picker's segmented control once more: two ways, one chosen.
                        className = ClassName("theme-toggle")
                        listOf(false to s.cardUrlsHide, true to s.cardUrlsShow).forEach { (show, label) ->
                            button {
                                className = ClassName(
                                    if (props.showCardUrls == show) "theme-opt active" else "theme-opt",
                                )
                                onClick = { props.onShowCardUrlsChange(show) }
                                +label
                            }
                        }
                    }
                }
            }

            // ---- What leaves this machine ----
            // Collections are things the user chose to keep. The statistics are a trace of what they did —
            // which pages, how often — and that is a different kind of thing to hand a server. So it is a
            // question, asked once, answered "no" until they say otherwise.
            div {
                className = ClassName("settings-section")
                h4 { +s.account }
                div {
                    className = ClassName("settings-row")
                    div {
                        className = ClassName("settings-label")
                        span { className = ClassName("settings-title"); +s.syncUsage }
                        span { className = ClassName("settings-hint"); +s.syncUsageHint }
                    }
                    div {
                        className = ClassName("theme-toggle")
                        listOf(false to s.optionOff, true to s.optionOn).forEach { (on, label) ->
                            button {
                                className = ClassName(
                                    if (props.syncUsage == on) "theme-opt active" else "theme-opt",
                                )
                                onClick = { props.onSyncUsageChange(on) }
                                +label
                            }
                        }
                    }
                }
            }

            // ---- Startup: the collection the page comes back to ----
            div {
                className = ClassName("settings-section")
                h4 { +s.startupSection }
                div {
                    className = ClassName("settings-row")
                    div {
                        className = ClassName("settings-label")
                        span { className = ClassName("settings-title"); +s.startView }
                        span { className = ClassName("settings-hint"); +s.startViewHint }
                    }
                    div {
                        // The theme picker's control again: two ways to open, one chosen.
                        className = ClassName("theme-toggle")
                        StartView.entries.forEach { view ->
                            button {
                                className =
                                    ClassName(if (props.startView == view.id) "theme-opt active" else "theme-opt")
                                onClick = { props.onStartViewChange(view.id) }
                                +view.label(s)
                            }
                        }
                    }
                }
            }

            // ---- Tabs: what saving a window's worth of them leaves behind ----
            //
            // Only where there are tabs to save: the web app cannot see the browser's, and the ⤓ that
            // this settles is not on its screen.
            if (props.hasTabs) {
                div {
                    className = ClassName("settings-section")
                    h4 { +s.tabsSection }
                    div {
                        className = ClassName("settings-row")
                        div {
                            className = ClassName("settings-label")
                            span { className = ClassName("settings-title"); +s.closeSavedTabs }
                            span { className = ClassName("settings-hint"); +s.closeSavedTabsHint }
                        }
                        div {
                            // The same segmented control as the theme picker: two ways, one chosen.
                            className = ClassName("theme-toggle")
                            listOf(true to s.closeSavedTabsClose, false to s.closeSavedTabsKeep)
                                .forEach { (close, label) ->
                                    button {
                                        className = ClassName(
                                            if (props.closeSavedTabs == close) "theme-opt active" else "theme-opt",
                                        )
                                        onClick = { props.onCloseSavedTabsChange(close) }
                                        +label
                                    }
                                }
                        }
                    }
                }
            }

            // ---- Security: how long an unlocked section stays open ----
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

            // ---- Who answers the search box's "ask the AI" ----
            //
            // It is nobody's business but the user's what is answering them — and whether it answers
            // here or somewhere on the web. So this is where that is chosen, and where the built-in
            // model says which one it is, where it runs, and — when it cannot answer at all — why not.
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
                            // A model this browser cannot run is not a choice to offer — it is greyed
                            // out, and the row underneath says what is wrong with it.
                            val dead = option == AiProvider.LOCAL && !props.aiLocalAvailable
                            button {
                                className =
                                    ClassName(if (provider == option) "theme-opt active" else "theme-opt")
                                disabled = dead
                                onClick = { props.onAiProviderChange(option.id) }
                                +option.label(s)
                            }
                        }
                    }
                }

                // What there is to say about the built-in model: which one it is, and — when it cannot
                // answer — why not, which is *also* said when it is not the one answering, since that
                // is the whole reason a web chat is. The web chats themselves have nothing to report:
                // they are the user's own accounts, and all this app does with them is open one.
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

            // ---- Data export ----
            div {
                className = ClassName("settings-section")
                h4 { +s.export }
                p { className = ClassName("settings-hint"); +s.exportHint }
                div {
                    className = ClassName("settings-actions")
                    button {
                        className = ClassName("btn")
                        onClick = { props.onExportCsv() }
                        +s.exportCsv
                    }
                    button {
                        className = ClassName("btn")
                        onClick = { props.onExportBookmarks() }
                        +s.exportBookmarks
                    }
                }
            }

            // ---- Data import: the way back in, and the way in from another browser ----
            //
            // The button is a <label> over a hidden file input — the only way to open the file dialog
            // without one, and the reason this reads as a button while the input itself is never seen.
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

        div {
            className = ClassName("modal-actions")
            button { className = ClassName("btn"); onClick = { props.onClose() }; +s.close }
        }
    }
}
