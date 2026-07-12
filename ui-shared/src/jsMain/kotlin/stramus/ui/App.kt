@file:OptIn(ExperimentalUuidApi::class)

package stramus.ui

import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import react.FC
import react.Key
import react.Props
import react.dom.html.ReactHTML.aside
import react.dom.html.ReactHTML.button
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.h2
import react.dom.html.ReactHTML.img
import react.dom.html.ReactHTML.input
import react.dom.html.ReactHTML.li
import react.dom.html.ReactHTML.main
import react.dom.html.ReactHTML.option
import react.dom.html.ReactHTML.select
import react.dom.html.ReactHTML.span
import react.dom.html.ReactHTML.ul
import react.useEffect
import react.useEffectOnce
import react.useState
import stramus.core.db.StramusStore
import stramus.core.db.openStramusStore
import stramus.core.model.Card
import stramus.core.model.CardKind
import stramus.core.model.CardSection
import stramus.core.model.Collection
import stramus.core.model.Section
import stramus.core.platform.CapturedTab
import stramus.core.platform.TabCapture
import web.cssom.ClassName
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

private val scope = MainScope()

internal fun key(id: Uuid): Key = id.toString().unsafeCast<Key>()

/** Which modal is open. [existing] non-null = editing/viewing that card; null = creating a new one. */
private data class NoteModal(val collectionId: Uuid, val cardSectionId: Uuid?, val existing: Card?)
private data class FileModal(val collectionId: Uuid, val cardSectionId: Uuid?, val existing: Card?)
/** Editing a card section's description (title kept, body edited as markdown). */
private data class DescModal(val sectionId: Uuid, val title: String, val description: String)

external interface AppProps : Props {
    /** Present in the extension (chrome.tabs); null in the web app. Enables "Save open tabs". */
    var tabCapture: TabCapture?
}

val App = FC<AppProps> { props ->
    val tabCapture = props.tabCapture

    var store by useState<StramusStore?>(null)
    var sections by useState<List<Section>>(emptyList())
    var collections by useState<List<Collection>>(emptyList())
    var selectedId by useState<Uuid?>(null)
    var cards by useState<List<Card>>(emptyList())
    var cardSections by useState<List<CardSection>>(emptyList())
    var query by useState("")
    var searchResults by useState<List<Card>>(emptyList())
    var draggingCardId by useState<Uuid?>(null)
    var draggingCollectionId by useState<Uuid?>(null)
    var openTabs by useState<List<CapturedTab>>(emptyList())
    var draggingTab by useState<CapturedTab?>(null)
    var dropCollectionId by useState<Uuid?>(null)
    var dropSectionId by useState<Uuid?>(null)
    var contentDropActive by useState(false)
    var noteModal by useState<NoteModal?>(null)
    var fileModal by useState<FileModal?>(null)
    var descModal by useState<DescModal?>(null)
    var settingsOpen by useState(false)

    // Persisted UI preferences (localStorage): theme, card sort, and sidebar collapse state.
    var theme by useState(prefGet("theme") ?: "auto")
    var sortMode by useState(SortMode.from(prefGet("sort")))
    var leftCollapsed by useState(prefGet("leftCollapsed") == "1")
    var rightCollapsed by useState(prefGet("rightCollapsed") == "1")

    useEffectOnce {
        scope.launch {
            val s = openStramusStore()
            val secs = s.sections.all()
            val cols = s.collections.all()
            store = s
            sections = secs
            collections = cols
            selectedId = cols.firstOrNull()?.id
        }
    }

    useEffect(theme) {
        applyTheme(theme)
        prefSet("theme", theme)
    }

    useEffect(store, selectedId) {
        val s = store ?: return@useEffect
        val sel = selectedId
        if (sel == null) {
            cards = emptyList()
            cardSections = emptyList()
        } else {
            scope.launch {
                cards = s.cards.byCollection(sel)
                cardSections = s.cardSections.byCollection(sel)
            }
        }
    }

    useEffect(store, query) {
        val s = store ?: return@useEffect
        val q = query.trim()
        if (q.isBlank()) searchResults = emptyList() else scope.launch { searchResults = s.cards.search(q) }
    }

    // The extension provides tab access: load the open tabs and keep the list live via tab events.
    useEffectOnce {
        val tc = tabCapture ?: return@useEffectOnce
        scope.launch { openTabs = tc.currentTabs() }
        // App is the page-root and mounts once, so the subscription lives for the page's lifetime.
        tc.onTabsChanged { scope.launch { openTabs = tc.currentTabs() } }
    }

    fun reloadCards(collectionId: Uuid?) {
        val s = store ?: return
        val sel = collectionId ?: selectedId ?: return
        scope.launch {
            cards = s.cards.byCollection(sel)
            cardSections = s.cardSections.byCollection(sel)
        }
    }

    // Save a dragged-in tab as a card in [collectionId], then close the browser tab.
    fun saveTab(tab: CapturedTab, collectionId: Uuid) {
        val s = store ?: return
        val tc = tabCapture ?: return
        scope.launch {
            s.cards.add(collectionId, tab.title.ifBlank { hostOf(tab.url) }, tab.url, tab.favicon ?: faviconFor(tab.url))
            tc.closeTab(tab.id)
            reloadCards(collectionId)
            openTabs = tc.currentTabs()
        }
    }

    // Move the dragged collection into [sectionId] at [index], then refresh the sidebar list.
    fun moveCollection(sectionId: Uuid, index: Int) {
        val dragged = draggingCollectionId
        val s = store ?: return
        if (dragged != null) {
            scope.launch {
                s.collections.move(dragged, sectionId, index)
                collections = s.collections.all()
            }
        }
        draggingCollectionId = null
        dropCollectionId = null
        dropSectionId = null
    }

    // Open a card by its kind: links navigate, notes open the markdown editor, files open the viewer.
    fun openCard(card: Card) {
        when (card.kind) {
            CardKind.LINK -> openUrl(card.url)
            CardKind.NOTE -> noteModal = NoteModal(card.collectionId, card.cardSectionId, card)
            CardKind.FILE -> fileModal = FileModal(card.collectionId, card.cardSectionId, card)
        }
    }

    div {
        className = ClassName("app")

        // ---- Left sidebar (sections + collections), collapsible ----
        if (leftCollapsed) {
            aside {
                className = ClassName("sidebar collapsed")
                button {
                    className = ClassName("rail-toggle")
                    title = "Expand sidebar"
                    onClick = { leftCollapsed = false; prefSet("leftCollapsed", "0") }
                    +"»"
                }
                button {
                    className = ClassName("rail-toggle settings-rail")
                    title = "Settings"
                    onClick = { settingsOpen = true }
                    +"⚙"
                }
            }
        } else {
            aside {
                className = ClassName("sidebar")
                div {
                    className = ClassName("brand")
                    span { +"stramus" }
                    button {
                        className = ClassName("icon collapse-btn")
                        title = "Collapse sidebar"
                        onClick = { leftCollapsed = true; prefSet("leftCollapsed", "1") }
                        +"«"
                    }
                }
                button {
                    className = ClassName("btn new-section")
                    onClick = {
                        val t = browserPrompt("Section name", "New section")
                        val s = store
                        if (s != null && !t.isNullOrBlank()) {
                            scope.launch {
                                s.sections.create(t.trim())
                                sections = s.sections.all()
                            }
                        }
                    }
                    +"+ New section"
                }
                sections.forEach { section ->
                    div {
                        key = key(section.id)
                        className = ClassName(if (section.id == dropSectionId) "section drop-section" else "section")
                        // Dropping a collection anywhere on the section (not on a specific item) moves
                        // it to the end of this section.
                        onDragOver = { e -> if (draggingCollectionId != null) e.preventDefault() }
                        onDragEnter = { if (draggingCollectionId != null) dropSectionId = section.id }
                        onDrop = { e ->
                            if (draggingCollectionId != null) {
                                e.preventDefault()
                                moveCollection(section.id, collections.count { it.sectionId == section.id })
                            }
                        }
                        div {
                            className = ClassName("section-head")
                            // Clicking the title collapses/expands the section (hides its collections).
                            span {
                                className = ClassName("section-title")
                                onClick = {
                                    val s = store
                                    if (s != null) {
                                        scope.launch {
                                            s.sections.setCollapsed(section.id, !section.collapsed)
                                            sections = s.sections.all()
                                        }
                                    }
                                }
                                span { className = ClassName("chevron"); +(if (section.collapsed) "▸" else "▾") }
                                +section.title
                            }
                            button {
                                className = ClassName("icon add")
                                onClick = { e ->
                                    e.stopPropagation()
                                    val name = browserPrompt("Collection name", "New collection")
                                    val s = store
                                    if (s != null && !name.isNullOrBlank()) {
                                        scope.launch {
                                            val created = s.collections.create(name.trim(), section.id)
                                            collections = s.collections.all()
                                            selectedId = created.id
                                        }
                                    }
                                }
                                +"+"
                            }
                            if (section.deletable) {
                                button {
                                    className = ClassName("icon del")
                                    onClick = { e ->
                                        e.stopPropagation()
                                        val s = store
                                        if (s != null) {
                                            scope.launch {
                                                s.sections.delete(section.id)
                                                sections = s.sections.all()
                                                collections = s.collections.all()
                                            }
                                        }
                                    }
                                    +"×"
                                }
                            }
                        }
                        if (!section.collapsed) {
                            ul {
                                className = ClassName("collections")
                                collections.filter { it.sectionId == section.id }.forEach { c ->
                                    li {
                                        key = key(c.id)
                                        className = ClassName(
                                            buildString {
                                                append("col")
                                                if (c.id == selectedId) append(" selected")
                                                if (c.id == dropCollectionId) append(" drop-target")
                                            },
                                        )
                                        draggable = true
                                        onClick = { selectedId = c.id }
                                        onDragStart = { e ->
                                            e.dataTransfer.setData("text/plain", c.id.toString())
                                            draggingCollectionId = c.id
                                        }
                                        onDragEnd = {
                                            draggingCollectionId = null
                                            dropCollectionId = null
                                            dropSectionId = null
                                        }
                                        // Drop target for: a dragged tab (saved here), a dragged card
                                        // (moved to this collection), or a dragged collection (reordered
                                        // to this item's position). preventDefault marks a valid target.
                                        onDragEnter = { e ->
                                            e.preventDefault()
                                            if (dropCollectionId != c.id) dropCollectionId = c.id
                                        }
                                        onDragOver = { e -> e.preventDefault() }
                                        onDragLeave = { if (dropCollectionId == c.id) dropCollectionId = null }
                                        onDrop = { e ->
                                            e.preventDefault()
                                            e.stopPropagation() // don't also fire the section's drop
                                            val tab = draggingTab
                                            val draggedCard = draggingCardId
                                            val draggedCol = draggingCollectionId
                                            val s = store
                                            when {
                                                draggedCol != null -> {
                                                    val idx = collections.filter { it.sectionId == c.sectionId }
                                                        .indexOfFirst { it.id == c.id }
                                                    moveCollection(c.sectionId, if (idx < 0) 0 else idx)
                                                }
                                                tab != null -> saveTab(tab, c.id)
                                                draggedCard != null && s != null -> scope.launch {
                                                    s.cards.move(draggedCard, c.id, Int.MAX_VALUE)
                                                    reloadCards(selectedId)
                                                }
                                            }
                                            draggingCardId = null
                                            draggingTab = null
                                            dropCollectionId = null
                                        }
                                        span {
                                            className = ClassName("col-title")
                                            +c.title
                                        }
                                        button {
                                            className = ClassName("icon del")
                                            onClick = { e ->
                                                e.stopPropagation()
                                                val s = store
                                                if (s != null) {
                                                    scope.launch {
                                                        s.collections.delete(c.id)
                                                        val cols = s.collections.all()
                                                        collections = cols
                                                        if (selectedId == c.id) selectedId = cols.firstOrNull()?.id
                                                    }
                                                }
                                            }
                                            +"×"
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                // Sidebar footer, pinned to the bottom: opens the settings page.
                div {
                    className = ClassName("sidebar-footer")
                    button {
                        className = ClassName("btn settings-btn")
                        onClick = { settingsOpen = true }
                        span { className = ClassName("gear"); +"⚙" }
                        +" Settings"
                    }
                }
            }
        }

        main {
            className = ClassName(if (contentDropActive) "content drop-active" else "content")
            // The whole content area is a drop zone for a dragged tab (saved into the selected
            // collection). Card reorder still happens on the individual tiles below.
            onDragEnter = { e -> if (draggingTab != null) e.preventDefault() }
            onDragOver = { e ->
                if (draggingTab != null) {
                    e.preventDefault()
                    if (!contentDropActive) contentDropActive = true
                }
            }
            onDragLeave = { contentDropActive = false }
            onDrop = { e ->
                e.preventDefault()
                val tab = draggingTab
                val current = collections.firstOrNull { it.id == selectedId }
                if (tab != null && current != null && query.isBlank()) {
                    saveTab(tab, current.id)
                }
                contentDropActive = false
                draggingTab = null
            }

            div {
                className = ClassName("topbar")
                input {
                    className = ClassName("search")
                    placeholder = "Search all links…"
                    value = query
                    onChange = { e -> query = e.target.value }
                }
                div {
                    className = ClassName("toolbar")
                    if (tabCapture != null && rightCollapsed) {
                        button {
                            className = ClassName("btn")
                            title = "Show open tabs"
                            onClick = { rightCollapsed = false; prefSet("rightCollapsed", "0") }
                            +"⧉ Tabs"
                        }
                    }
                }
            }

            if (query.isNotBlank()) {
                h2 { +"Results for “${query.trim()}”" }
                if (searchResults.isEmpty()) {
                    div { className = ClassName("empty"); +"No matching links." }
                } else {
                    div {
                        className = ClassName("grid")
                        sortMode.apply(searchResults).forEach { card ->
                            cardTile(card, isDraggable = false, onOpen = { openCard(card) }, onDelete = {
                                val s = store
                                if (s != null) scope.launch { s.cards.delete(card.id); searchResults = s.cards.search(query.trim()); reloadCards(selectedId) }
                            })
                        }
                    }
                }
            } else {
                val current = collections.firstOrNull { it.id == selectedId }
                if (current == null) {
                    div { className = ClassName("empty"); +"Create a collection to start saving links." }
                } else {
                    div {
                        className = ClassName("content-head")
                        h2 { +current.title }
                        div {
                            className = ClassName("actions")
                            // Sort order for this collection's cards.
                            select {
                                className = ClassName("control")
                                title = "Sort links"
                                value = sortMode.id
                                onChange = { e ->
                                    sortMode = SortMode.from(e.target.value)
                                    prefSet("sort", e.target.value)
                                }
                                SortMode.entries.forEach { m ->
                                    option { value = m.id; +m.label }
                                }
                            }
                            button {
                                className = ClassName("btn")
                                onClick = {
                                    val t = browserPrompt("Section name", "New section")
                                    val s = store
                                    if (s != null && !t.isNullOrBlank()) {
                                        scope.launch {
                                            s.cardSections.create(current.id, t.trim(), null)
                                            reloadCards(current.id)
                                        }
                                    }
                                }
                                +"+ Section"
                            }
                            tabCapture?.let { tc ->
                                button {
                                    className = ClassName("btn")
                                    onClick = {
                                        val s = store
                                        if (s != null) {
                                            scope.launch {
                                                tc.currentTabs().forEach { t ->
                                                    s.cards.add(current.id, t.title.ifBlank { hostOf(t.url) }, t.url, t.favicon ?: faviconFor(t.url))
                                                }
                                                reloadCards(current.id)
                                            }
                                        }
                                    }
                                    +"⤓ Save open tabs"
                                }
                            }
                            // "Add link" is the primary action; hovering reveals a menu to add a
                            // markdown note or upload a file instead.
                            div {
                                className = ClassName("add-menu")
                                button {
                                    className = ClassName("btn add-card")
                                    onClick = {
                                        val url = browserPrompt("Paste a URL")
                                        val s = store
                                        if (s != null && !url.isNullOrBlank()) {
                                            val clean = url.trim()
                                            scope.launch {
                                                s.cards.add(current.id, hostOf(clean), clean, faviconFor(clean))
                                                reloadCards(current.id)
                                            }
                                        }
                                    }
                                    +"+ Add link ▾"
                                }
                                div {
                                    className = ClassName("add-dropdown")
                                    button {
                                        className = ClassName("add-item")
                                        onClick = {
                                            val url = browserPrompt("Paste a URL")
                                            val s = store
                                            if (s != null && !url.isNullOrBlank()) {
                                                val clean = url.trim()
                                                scope.launch {
                                                    s.cards.add(current.id, hostOf(clean), clean, faviconFor(clean))
                                                    reloadCards(current.id)
                                                }
                                            }
                                        }
                                        +"🔗 Link"
                                    }
                                    button {
                                        className = ClassName("add-item")
                                        onClick = { noteModal = NoteModal(current.id, null, null) }
                                        +"📝 Note"
                                    }
                                    button {
                                        className = ClassName("add-item")
                                        onClick = { fileModal = FileModal(current.id, null, null) }
                                        +"📎 File"
                                    }
                                }
                            }
                        }
                    }

                    val ungrouped = sortMode.apply(cards.filter { it.cardSectionId == null })
                    val hasSections = cardSections.isNotEmpty()

                    if (cards.isEmpty() && !hasSections) {
                        div { className = ClassName("empty"); +"No links yet — add one, or drag one here." }
                    } else {
                        // Card reorder within the collection: dropping a card on a tile moves it just
                        // before that tile (using its position in the raw, collection-wide order).
                        fun tileDrop(card: Card) {
                            val dragged = draggingCardId
                            val s = store
                            if (dragged != null && s != null && dragged != card.id) {
                                val targetIndex = cards.indexOfFirst { it.id == card.id }
                                scope.launch { s.cards.move(dragged, current.id, targetIndex); reloadCards(current.id) }
                            }
                            draggingCardId = null
                        }

                        // Ungrouped cards. When there are sections, show a header that also acts as a
                        // drop target for removing a card from a section.
                        if (hasSections) {
                            div {
                                className = ClassName("card-section-head")
                                onDragOver = { e -> if (draggingCardId != null) e.preventDefault() }
                                onDrop = { e ->
                                    e.preventDefault()
                                    val dragged = draggingCardId
                                    val s = store
                                    if (dragged != null && s != null) scope.launch { s.cards.moveToSection(dragged, null); reloadCards(current.id) }
                                    draggingCardId = null
                                }
                                span { className = ClassName("card-section-title"); +"Ungrouped" }
                            }
                        }
                        if (ungrouped.isNotEmpty()) {
                            div {
                                className = ClassName("grid")
                                ungrouped.forEach { card ->
                                    cardTile(
                                        card = card,
                                        isDraggable = true,
                                        onOpen = { openCard(card) },
                                        onDelete = {
                                            val s = store
                                            if (s != null) scope.launch { s.cards.delete(card.id); reloadCards(current.id) }
                                        },
                                        onStartDrag = { draggingCardId = card.id },
                                        onEndDrag = { draggingCardId = null },
                                        onDropHere = { tileDrop(card) },
                                    )
                                }
                            }
                        }

                        cardSections.sortedBy { it.position }.forEach { cs ->
                            val groupCards = sortMode.apply(cards.filter { it.cardSectionId == cs.id })
                            div {
                                key = key(cs.id)
                                className = ClassName("card-section-head")
                                // Dropping a card here assigns it to this section.
                                onDragOver = { e -> if (draggingCardId != null) e.preventDefault() }
                                onDrop = { e ->
                                    e.preventDefault()
                                    val dragged = draggingCardId
                                    val s = store
                                    if (dragged != null && s != null) scope.launch { s.cards.moveToSection(dragged, cs.id); reloadCards(current.id) }
                                    draggingCardId = null
                                }
                                span {
                                    className = ClassName("card-section-title")
                                    onClick = {
                                        val s = store
                                        if (s != null) scope.launch {
                                            s.cardSections.setCollapsed(cs.id, !cs.collapsed)
                                            cardSections = s.cardSections.byCollection(current.id)
                                        }
                                    }
                                    span { className = ClassName("chevron"); +(if (cs.collapsed) "▸" else "▾") }
                                    +cs.title
                                    span { className = ClassName("count"); +" ${groupCards.size}" }
                                }
                                div {
                                    className = ClassName("card-section-tools")
                                    button {
                                        className = ClassName("icon edit")
                                        title = "Edit description"
                                        onClick = { e ->
                                            e.stopPropagation()
                                            descModal = DescModal(cs.id, cs.title, cs.description ?: "")
                                        }
                                        +"✎"
                                    }
                                    button {
                                        className = ClassName("icon del")
                                        onClick = { e ->
                                            e.stopPropagation()
                                            val s = store
                                            if (s != null) scope.launch { s.cardSections.delete(cs.id); reloadCards(current.id) }
                                        }
                                        +"×"
                                    }
                                }
                            }
                            if (!cs.collapsed && !cs.description.isNullOrBlank()) {
                                markdownBlock("card-section-desc", cs.description!!)
                            }
                            if (!cs.collapsed) {
                                if (groupCards.isEmpty()) {
                                    div { className = ClassName("empty small"); +"Drag links here." }
                                } else {
                                    div {
                                        className = ClassName("grid")
                                        groupCards.forEach { card ->
                                            cardTile(
                                                card = card,
                                                isDraggable = true,
                                                onOpen = { openCard(card) },
                                                onDelete = {
                                                    val s = store
                                                    if (s != null) scope.launch { s.cards.delete(card.id); reloadCards(current.id) }
                                                },
                                                onStartDrag = { draggingCardId = card.id },
                                                onEndDrag = { draggingCardId = null },
                                                onDropHere = { tileDrop(card) },
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // ---- Right sidebar: live open browser tabs (extension only), collapsible ----
        tabCapture?.let {
            if (rightCollapsed) {
                aside {
                    className = ClassName("tabs collapsed")
                    button {
                        className = ClassName("rail-toggle")
                        title = "Show open tabs"
                        onClick = { rightCollapsed = false; prefSet("rightCollapsed", "0") }
                        +"«"
                    }
                }
            } else {
                aside {
                    className = ClassName("tabs")
                    div {
                        className = ClassName("tabs-head")
                        span { +"Open tabs" }
                        button {
                            className = ClassName("icon collapse-btn")
                            title = "Hide open tabs"
                            onClick = { rightCollapsed = true; prefSet("rightCollapsed", "1") }
                            +"»"
                        }
                    }
                    if (openTabs.isEmpty()) {
                        div { className = ClassName("empty small"); +"No open tabs to save." }
                    } else {
                        ul {
                            className = ClassName("tab-list")
                            openTabs.forEach { tab ->
                                li {
                                    key = tab.id.toString().unsafeCast<Key>()
                                    className = ClassName("tab")
                                    draggable = true
                                    onDragStart = { e ->
                                        // Some browsers require drag data to be set or they reject drops.
                                        e.dataTransfer.setData("text/plain", tab.id.toString())
                                        draggingTab = tab
                                    }
                                    onDragEnd = {
                                        draggingTab = null
                                        dropCollectionId = null
                                    }
                                    img {
                                        className = ClassName("fav")
                                        src = tab.favicon ?: faviconFor(tab.url)
                                        alt = ""
                                        draggable = false // let the <li> be the drag source, not the image
                                    }
                                    span {
                                        className = ClassName("tab-title")
                                        +tab.title.ifBlank { hostOf(tab.url) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // ---- Modals ----
        noteModal?.let { m ->
            NoteEditor {
                heading = if (m.existing != null) "Edit note" else "New note"
                showTitle = true
                initialTitle = m.existing?.title ?: ""
                initialContent = m.existing?.content ?: ""
                onClose = { noteModal = null }
                onSave = { t, c ->
                    val s = store
                    if (s != null) scope.launch {
                        val existing = m.existing
                        if (existing != null) s.cards.updateNote(existing.id, t, c)
                        else s.cards.addNote(m.collectionId, t, c, m.cardSectionId)
                        reloadCards(m.collectionId)
                    }
                    noteModal = null
                }
            }
        }
        fileModal?.let { m ->
            FileViewer {
                existing = m.existing
                onClose = { fileModal = null }
                onSave = { name, mimeType, data ->
                    val s = store
                    if (s != null) scope.launch {
                        s.cards.addFile(m.collectionId, name, data, mimeType, m.cardSectionId)
                        reloadCards(m.collectionId)
                    }
                    fileModal = null
                }
            }
        }
        if (settingsOpen) {
            SettingsModal {
                this.theme = theme
                onThemeChange = { theme = it }
                onExportCsv = { store?.let { s -> scope.launch { exportCsv(s) } } }
                onExportBookmarks = { store?.let { s -> scope.launch { exportBookmarks(s) } } }
                onClose = { settingsOpen = false }
            }
        }
        descModal?.let { m ->
            NoteEditor {
                heading = "Section description"
                showTitle = false
                initialTitle = m.title
                initialContent = m.description
                onClose = { descModal = null }
                onSave = { _, c ->
                    val s = store
                    if (s != null) scope.launch {
                        s.cardSections.update(m.sectionId, m.title, c)
                        reloadCards(selectedId)
                    }
                    descModal = null
                }
            }
        }
    }
}
