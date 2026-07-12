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
    var onExportCsv: () -> Unit
    var onExportBookmarks: () -> Unit
    var onClose: () -> Unit
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
