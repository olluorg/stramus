@file:OptIn(ExperimentalUuidApi::class)

package stramus.ui

import react.ChildrenBuilder
import react.FC
import react.Props
import react.dom.html.ReactHTML.a
import react.dom.html.ReactHTML.audio
import react.dom.html.ReactHTML.button
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.h3
import react.dom.html.ReactHTML.img
import react.dom.html.ReactHTML.input
import react.dom.html.ReactHTML.label
import react.dom.html.ReactHTML.video
import react.useEffect
import react.useEffectOnce
import react.useRef
import react.useState
import stramus.core.model.Card
import stramus.core.repo.CardRepository
import web.cssom.ClassName
import web.html.HTMLDivElement
import web.html.InputType
import kotlin.uuid.ExperimentalUuidApi

// The file input's type is a value class over a string; casting sidesteps a fiddly member lookup.
private val FILE_INPUT_TYPE: InputType = "file".unsafeCast<InputType>()

/** Backdrop + centered panel shared by the note, file and settings modals. Clicking the backdrop closes. */
internal fun ChildrenBuilder.modalShell(onClose: () -> Unit, panelClass: String, body: ChildrenBuilder.() -> Unit) {
    div {
        className = ClassName("modal-backdrop")
        onClick = { onClose() }
        div {
            className = ClassName(panelClass)
            // Clicks inside the panel must not fall through to the backdrop's close handler.
            onClick = { it.stopPropagation() }
            body()
        }
    }
}

external interface NoteEditorProps : Props {
    var strings: Strings
    var heading: String
    var showTitle: Boolean
    var initialTitle: String
    var initialContent: String
    /** A note in a read-only collection: shown, not edited — no toolbar, no caret, no Save. */
    var readOnly: Boolean
    var onSave: (title: String, content: String) -> Unit
    var onClose: () -> Unit
}

/**
 * A WYSIWYG note editor: a formatting toolbar over a contenteditable surface. The note is *stored*
 * as markdown — [initialContent] markdown seeds the editor (via [renderMarkdown]) and on save the
 * live DOM is converted back with [htmlToMarkdown].
 */
val NoteEditor = FC<NoteEditorProps> { props ->
    // NB: must NOT be named `title` — inside a `button { }`/`input { }` builder an unqualified
    // `title` binds to this local (shadowing the element's `title` attribute extension property), so
    // `title = hint` on a toolbar button would call this setter during render → infinite re-render.
    var noteTitle by useState(props.initialTitle)
    val editorRef = useRef<HTMLDivElement>(null)
    val s = props.strings
    // Not named `readOnly`: inside an `input { }` builder that local would shadow the element's own
    // readOnly attribute, exactly as the `title` note above describes.
    val viewOnly = props.readOnly

    useEffectOnce {
        val el = editorRef.current?.asDynamic() ?: return@useEffectOnce
        el.contentEditable = if (viewOnly) "false" else "true"
        el.innerHTML = renderMarkdown(props.initialContent)
    }

    fun cmd(command: String, value: String? = null) {
        editorRef.current?.asDynamic()?.focus()
        execCommand(command, value)
    }
    fun wrapSelection(before: String, placeholder: String, after: String) {
        editorRef.current?.asDynamic()?.focus()
        val sel = selectionText()
        execCommand("insertHTML", "$before${sel.ifBlank { placeholder }}$after")
    }
    fun addLink() {
        val sel = selectionText()
        val url = browserPrompt(s.linkUrlPrompt, "https://")
        if (!url.isNullOrBlank()) {
            val u = url.trim()
            wrapSelection("<a href=\"$u\">", sel.ifBlank { u }, "</a>")
        }
    }

    // Toolbar buttons must not steal focus (which would drop the editor's selection); preventing the
    // default mousedown keeps the caret/selection in the contenteditable so execCommand applies.
    fun ChildrenBuilder.toolButton(label: String, tooltip: String, action: () -> Unit) {
        button {
            className = ClassName("tool")
            hint(tooltip)
            onMouseDown = { it.preventDefault() }
            onClick = { action() }
            +label
        }
    }

    modalShell(props.onClose, "modal note-modal") {
        div {
            className = ClassName("modal-head")
            h3 { +props.heading }
            button { className = ClassName("icon del"); onClick = { props.onClose() }; +"×" }
        }
        if (props.showTitle) {
            input {
                className = ClassName("modal-title-input")
                placeholder = s.titlePlaceholder
                value = noteTitle
                readOnly = props.readOnly
                onChange = { e -> noteTitle = e.target.value }
            }
        }
        if (!viewOnly) {
            div {
                className = ClassName("wysiwyg-toolbar")
                toolButton("B", s.toolBold) { cmd("bold") }
                toolButton("I", s.toolItalic) { cmd("italic") }
                toolButton("H", s.toolHighlight) { wrapSelection("<mark>", s.highlightPlaceholder, "</mark>") }
                toolButton("</>", s.toolCode) { wrapSelection("<code>", s.codePlaceholder, "</code>") }
                toolButton("🔗", s.toolLink) { addLink() }
                toolButton("H2", s.toolHeading) { cmd("formatBlock", "H2") }
                toolButton(s.toolListLabel, s.toolList) { cmd("insertUnorderedList") }
            }
        }
        div {
            className = ClassName("wysiwyg")
            ref = editorRef
        }
        div {
            className = ClassName("modal-actions")
            button { className = ClassName("btn"); onClick = { props.onClose() }; +(if (viewOnly) s.close else s.cancel) }
            if (!viewOnly) {
                button {
                    className = ClassName("btn primary")
                    onClick = {
                        props.onSave(noteTitle.trim().ifBlank { s.noteDefaultTitle }, htmlToMarkdown(editorRef.current))
                    }
                    +s.save
                }
            }
        }
    }
}

external interface FileViewerProps : Props {
    var strings: Strings
    /** Non-null = view an existing file card; null = pick a new file to add. */
    var existing: Card?

    /** Where an existing card's bytes are read from — they are not carried on the card itself. */
    var cards: CardRepository?
    var onSave: (name: String, mime: String, dataUri: String) -> Unit
    var onClose: () -> Unit
}

/**
 * Views an existing file card (image/media preview) or picks + previews a new file to save.
 *
 * Opening the file is the moment its bytes are read: the card carries only a small preview, so this
 * is where the whole thing is loaded — for exactly as long as the modal is up.
 */
val FileViewer = FC<FileViewerProps> { props ->
    val existing = props.existing
    // Avoid `name` — it collides with the `name` HTML-attribute extension property in builder scopes.
    var fileName by useState(existing?.title ?: "")
    var mime by useState(existing?.mime ?: "")
    var dataUri by useState("")
    val s = props.strings

    useEffect(existing?.id) {
        val card = existing ?: return@useEffect
        val repo = props.cards ?: return@useEffect
        dataUri = repo.blob(card.id) ?: ""
    }

    modalShell(props.onClose, "modal file-modal") {
        div {
            className = ClassName("modal-head")
            h3 { +(if (existing != null) existing.title else s.addFile) }
            button { className = ClassName("icon del"); onClick = { props.onClose() }; +"×" }
        }

        if (existing == null) {
            label {
                className = ClassName("file-picker")
                +s.chooseFile
                input {
                    type = FILE_INPUT_TYPE
                    className = ClassName("hidden-file-input")
                    onChange = { e ->
                        readPickedFile(e.target) { n, m, d ->
                            fileName = n
                            mime = m
                            dataUri = d
                        }
                    }
                }
            }
        }

        if (dataUri.isNotBlank()) {
            filePreview(s, mime, dataUri, fileName)
        }

        div {
            className = ClassName("modal-actions")
            if (dataUri.isNotBlank()) {
                a {
                    className = ClassName("btn")
                    href = dataUri
                    download = fileName
                    +s.download
                }
            }
            button { className = ClassName("btn"); onClick = { props.onClose() }; +s.close }
            if (existing == null) {
                button {
                    className = ClassName("btn primary")
                    disabled = dataUri.isBlank()
                    onClick = {
                        if (dataUri.isNotBlank()) props.onSave(fileName.ifBlank { s.fileDefaultTitle }, mime, dataUri)
                    }
                    +s.save
                }
            }
        }
    }
}

/** Inline preview for a file: images/video/audio render directly; anything else shows a note. */
internal fun ChildrenBuilder.filePreview(strings: Strings, mime: String, dataUri: String, name: String) {
    div {
        className = ClassName("file-preview")
        when {
            mime.startsWith("image/") -> img { src = dataUri; alt = name; className = ClassName("preview-media") }
            mime.startsWith("video/") -> video {
                className = ClassName("preview-media")
                controls = true
                src = dataUri
            }
            mime.startsWith("audio/") -> audio {
                controls = true
                src = dataUri
            }
            else -> div { className = ClassName("empty small"); +strings.noPreviewFor(mime) }
        }
    }
}
