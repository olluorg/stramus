package stramus.ui

import react.FC
import react.Props
import react.dom.html.ReactHTML.button
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.h3
import react.dom.html.ReactHTML.h4
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
    /** Minutes of inactivity before unlocked sections lock again; 0 = never. */
    var autoLockMinutes: Int
    var onAutoLockChange: (Int) -> Unit

    /** Whether the host gives access to the browser's tabs at all — the web app has none to settle. */
    var hasTabs: Boolean

    /** Whether saving a whole window's tabs into a collection also closes them in the browser. */
    var closeSavedTabs: Boolean
    var onCloseSavedTabsChange: (Boolean) -> Unit

    /** The model answering questions from the search box, named — or null where there is none. */
    var aiName: String?

    /** Its state, so "no AI here" can say *why*: no such browser API, or a machine that cannot run it. */
    var aiState: AiAvailability?

    var onExportCsv: () -> Unit
    var onExportBookmarks: () -> Unit
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

            // ---- The model behind the search box's "ask the AI" ----
            //
            // It is nobody's business but the user's what is answering them, so this says which model
            // it is, where it runs, and — when it cannot answer at all — why not.
            div {
                className = ClassName("settings-section")
                h4 { +s.aiSection }
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
        }

        div {
            className = ClassName("modal-actions")
            button { className = ClassName("btn"); onClick = { props.onClose() }; +s.close }
        }
    }
}
