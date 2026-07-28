@file:OptIn(ExperimentalUuidApi::class)

package stramus.ui

import kotlinx.coroutines.awaitCancellation
import react.ChildrenBuilder
import react.FC
import react.Key
import react.Props
import react.dom.html.ReactHTML.a
import react.dom.html.ReactHTML.audio
import react.dom.html.ReactHTML.button
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.h3
import react.dom.html.ReactHTML.img
import react.dom.html.ReactHTML.input
import react.dom.html.ReactHTML.label
import react.dom.html.ReactHTML.span
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
internal val FILE_INPUT_TYPE: InputType = "file".unsafeCast<InputType>()

/**
 * Backdrop + centered panel shared by every modal in the app — the note editor, the file viewer,
 * settings, the PIN dialogs, the AI window. Clicking the backdrop closes it, and so does Escape:
 * a window over the app is left the way every other one on the machine is.
 *
 * Escape is watched here rather than in each modal, which means this function calls hooks and is
 * therefore itself one: every caller must call it unconditionally, once, from its own render — all of
 * them do, it *is* their root element.
 */
internal fun ChildrenBuilder.modalShell(onClose: () -> Unit, panelClass: String, body: ChildrenBuilder.() -> Unit) {
    // The listener is registered once, so it must not close over the first render's onClose (a fresh
    // lambda each render, and a stale one may hold stale state): it reads the current one through a ref.
    val close = useRef(onClose)
    close.current = onClose
    useEffectOnce {
        val stopWatching = onKeyStroke { event ->
            if (event.key == "Escape") {
                event.preventDefault()
                close.current?.invoke()
            }
        }
        try {
            awaitCancellation()
        } finally {
            stopWatching()
        }
    }

    // A click's target follows mouseup, not mousedown: dragging a text selection from inside the
    // panel out over the backdrop and releasing there fires a "click" on the backdrop (their nearest
    // common ancestor), which looks identical to an outside click unless the press is tracked too.
    val pressedBackdrop = useRef(false)

    div {
        className = ClassName("modal-backdrop")
        onMouseDown = { pressedBackdrop.current = it.target == it.currentTarget }
        onClick = { if (pressedBackdrop.current == true) onClose() }
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

    /** The heading while the note is being written. */
    var heading: String

    /** The heading while it is only being read; null = the same one. */
    var viewHeading: String?

    /**
     * Whether to open straight into editing rather than into reading. True for text that exists to be
     * written — a new note, a description the user has just asked to change.
     */
    var startInEdit: Boolean
    var showTitle: Boolean
    var initialTitle: String
    var initialContent: String

    /**
     * Where the unsaved text of this note is kept between openings (see `Drafts.kt`) — null for an
     * editor whose text is not worth keeping (a read-only note).
     */
    var draftKey: String?

    /** A note in a read-only collection: read, never written — there is no way into editing at all. */
    var readOnly: Boolean
    var onSave: (title: String, content: String) -> Unit
    var onClose: () -> Unit
}

/**
 * A WYSIWYG note editor: a formatting toolbar over a contenteditable surface. The note is *stored*
 * as markdown — [initialContent] markdown seeds the editor (via [renderMarkdown]) and on save the
 * live DOM is converted back with [htmlToMarkdown].
 *
 * An existing note opens to be *read* — no toolbar, no caret, nothing that a stray keystroke can
 * change — and editing starts on the button that says so. Most openings of a note are to look
 * something up, and a note one is looking at is not a note one is holding open for changes.
 * [NoteEditorProps.startInEdit] is the exception: text that only exists once it is written.
 *
 * Nothing here waits for Save to keep the text: every keystroke also writes a draft under
 * [NoteEditorProps.draftKey], and the editor opens on that draft rather than on the saved note when
 * one is there. So a tab closed mid-sentence — the one thing this app cannot ask about first — costs
 * the user nothing: reopening the note brings the sentence back. A draft changes nothing else about
 * the modal — same mode, same buttons — beyond the strip that says the text is one, which goes as soon
 * as the user writes anything. Saving (or resetting) drops the draft, which is what makes its mere
 * existence mean "this note has unsaved text in it".
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
    val locked = props.readOnly
    var editing by useState(!locked && props.startInEdit)
    // Reading, whether because the note opened that way or because it can never be anything else.
    val viewOnly = !editing
    // A note that cannot be written has nothing unsaved to keep.
    val draftKey = props.draftKey.takeUnless { locked }

    // The saved note as the *editor* would produce it. The markdown that comes back out of the DOM is
    // not character-for-character the markdown that went in (the editor normalizes as it renders), so
    // comparing what the user has now against props.initialContent would call an untouched note dirty
    // and leave a draft behind for a note nobody edited. Comparing against the round-trip does not.
    val savedContent = useRef("")
    // Whether the text on screen came back from a draft — worth telling the user, since it is not what
    // the collection shows for this note.
    var restored by useState(false)

    /** Keep — or drop — the draft of what is in the editor right now. */
    fun syncDraft(currentTitle: String) {
        // The strip announces a draft *put back*. From the first edit on, the text is simply what the
        // user is writing, and saying anything about where it came from is noise.
        restored = false
        val key = draftKey ?: return
        val content = htmlToMarkdown(editorRef.current)
        // Back to what is saved (an edit typed out again, or undone) — then there is nothing unsaved
        // to keep, and the draft must go, or the next opening would announce a "draft" identical to
        // the note itself.
        if (currentTitle == props.initialTitle && content == savedContent.current) clearNoteDraft(key)
        else saveNoteDraft(key, NoteDraft(currentTitle, content))
    }

    useEffectOnce {
        val el = editorRef.current?.asDynamic() ?: return@useEffectOnce
        el.innerHTML = renderMarkdown(props.initialContent)
        savedContent.current = htmlToMarkdown(editorRef.current)

        // A draft changes nothing about how the note opens — only *what is in it*: the text the user
        // left behind, and a strip saying so.
        val draft = draftKey?.let { loadNoteDraft(it) }
        if (draft != null) {
            el.innerHTML = renderMarkdown(draft.content)
            noteTitle = draft.title
            restored = true
        }
    }

    // The caret is what tells the two modes apart, so it follows the mode — and when writing begins
    // it goes into the text, so that starting to write takes no click beyond the one that asked for it.
    useEffect(editing) {
        val el = editorRef.current?.asDynamic() ?: return@useEffect
        el.contentEditable = if (editing) "true" else "false"
        if (editing) el.focus()
    }

    /** Throw the unsaved text away: the editor goes back to the note as it is stored. */
    fun discardDraft() {
        draftKey?.let { clearNoteDraft(it) }
        editorRef.current?.asDynamic()?.innerHTML = renderMarkdown(props.initialContent)
        noteTitle = props.initialTitle
        restored = false
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

    // The editor's own div is written to by hand (innerHTML, contentEditable) and React knows nothing
    // of what is inside it — so React must be told, by key, that it is the *same* element from one
    // render to the next. Without keys it matches the panel's children by position, and the strip and
    // the toolbar, which come and go, shift every element under them by one: React would then reuse the
    // editor's div as the toolbar (text and all) and hand the editor a blank div with no caret. Hence a
    // key on every child of the panel, not only on the ones that appear and disappear.
    modalShell(props.onClose, "modal note-modal") {
        div {
            key = "head".unsafeCast<Key>()
            className = ClassName("modal-head")
            h3 { +(if (editing) props.heading else props.viewHeading ?: props.heading) }
            button { className = ClassName("icon del"); onClick = { props.onClose() }; +"×" }
        }
        if (props.showTitle) {
            input {
                key = "title".unsafeCast<Key>()
                className = ClassName("modal-title-input")
                placeholder = s.titlePlaceholder
                value = noteTitle
                readOnly = viewOnly
                onChange = { e ->
                    noteTitle = e.target.value
                    syncDraft(e.target.value)
                }
            }
        }
        if (restored) {
            div {
                key = "draft".unsafeCast<Key>()
                className = ClassName("draft-bar")
                span { +s.draftRestored }
                button {
                    className = ClassName("draft-discard")
                    onClick = { discardDraft() }
                    +s.discardDraft
                }
            }
        }
        if (!viewOnly) {
            div {
                key = "toolbar".unsafeCast<Key>()
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
            key = "editor".unsafeCast<Key>()
            className = ClassName(if (viewOnly) "wysiwyg reading" else "wysiwyg")
            ref = editorRef
            // Every edit — typed, pasted, or made by a toolbar button, which `execCommand` reports as
            // an input of its own — goes straight to the draft. Nothing is left waiting for a Save
            // that the closing of a tab may never let happen.
            onInput = { syncDraft(noteTitle) }
        }
        div {
            key = "actions".unsafeCast<Key>()
            className = ClassName("modal-actions")
            button { className = ClassName("btn"); onClick = { props.onClose() }; +(if (viewOnly) s.close else s.cancel) }
            when {
                // Reading a note that takes edits: the way into writing it.
                viewOnly && !locked -> button {
                    className = ClassName("btn primary")
                    onClick = { editing = true }
                    +s.editNoteAction
                }
                !viewOnly -> button {
                    className = ClassName("btn primary")
                    onClick = {
                        // Saved is saved: the draft has served its purpose and would otherwise come
                        // back over the note the next time it is opened.
                        draftKey?.let { clearNoteDraft(it) }
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
