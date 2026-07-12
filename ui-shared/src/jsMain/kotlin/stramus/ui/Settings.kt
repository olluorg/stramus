package stramus.ui

import react.FC
import react.Props
import react.dom.html.ReactHTML.button
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.h3
import react.dom.html.ReactHTML.h4
import react.dom.html.ReactHTML.p
import react.dom.html.ReactHTML.span
import web.cssom.ClassName

external interface SettingsModalProps : Props {
    /** Current theme id: "auto" | "light" | "dark". */
    var theme: String
    var onThemeChange: (String) -> Unit
    var onExportCsv: () -> Unit
    var onExportBookmarks: () -> Unit
    var onClose: () -> Unit
}

/**
 * The settings "page": a modal opened from the left sidebar footer. Groups app-wide preferences and
 * data export (theme, CSV export, bookmarks export) that used to live in the content toolbar.
 */
val SettingsModal = FC<SettingsModalProps> { props ->
    modalShell(props.onClose, "modal settings-modal") {
        div {
            className = ClassName("modal-head")
            h3 { +"Settings" }
            button { className = ClassName("icon del"); onClick = { props.onClose() }; +"×" }
        }

        div {
            className = ClassName("settings-body")

            // ---- Appearance ----
            div {
                className = ClassName("settings-section")
                h4 { +"Appearance" }
                div {
                    className = ClassName("settings-row")
                    div {
                        className = ClassName("settings-label")
                        span { className = ClassName("settings-title"); +"Theme" }
                        span { className = ClassName("settings-hint"); +"Follow the system, or force day/night." }
                    }
                    div {
                        className = ClassName("theme-toggle")
                        listOf("auto" to "◑ Auto", "light" to "☀ Light", "dark" to "☾ Dark").forEach { (id, label) ->
                            button {
                                className = ClassName(if (props.theme == id) "theme-opt active" else "theme-opt")
                                onClick = { props.onThemeChange(id) }
                                +label
                            }
                        }
                    }
                }
            }

            // ---- Data export ----
            div {
                className = ClassName("settings-section")
                h4 { +"Export" }
                p { className = ClassName("settings-hint"); +"Download every saved link across all collections." }
                div {
                    className = ClassName("settings-actions")
                    button {
                        className = ClassName("btn")
                        onClick = { props.onExportCsv() }
                        +"⤒ Export CSV"
                    }
                    button {
                        className = ClassName("btn")
                        onClick = { props.onExportBookmarks() }
                        +"⤒ Export bookmarks"
                    }
                }
            }
        }

        div {
            className = ClassName("modal-actions")
            button { className = ClassName("btn"); onClick = { props.onClose() }; +"Close" }
        }
    }
}
