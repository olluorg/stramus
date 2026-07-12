@file:OptIn(ExperimentalUuidApi::class)

package stramus.ui

import kotlinx.coroutines.MainScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import react.ChildrenBuilder
import react.FC
import react.Key
import react.Props
import react.dom.html.ReactHTML.aside
import react.dom.html.ReactHTML.button
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.h2
import react.dom.html.ReactHTML.input
import react.dom.html.ReactHTML.li
import react.dom.html.ReactHTML.main
import react.dom.html.ReactHTML.option
import react.dom.html.ReactHTML.select
import react.dom.html.ReactHTML.span
import react.dom.html.ReactHTML.ul
import react.useEffect
import react.useEffectOnce
import react.useRef
import react.useState
import stramus.core.db.StramusStore
import stramus.core.db.openStramusStore
import stramus.core.model.Card
import stramus.core.model.CardKind
import stramus.core.model.CardSection
import stramus.core.model.Collection
import stramus.core.model.Section
import stramus.core.platform.CapturedTab
import stramus.core.platform.HistoryAccess
import stramus.core.platform.HistoryEntry
import stramus.core.platform.TabCapture
import web.cssom.ClassName
import web.data.DropEffect
import web.data.move
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

private val scope = MainScope()

internal fun key(id: Uuid): Key = id.toString().unsafeCast<Key>()

/** Which modal is open. [existing] non-null = editing/viewing that card; null = creating a new one. */
private data class NoteModal(val collectionId: Uuid, val cardSectionId: Uuid?, val existing: Card?)
private data class FileModal(val collectionId: Uuid, val cardSectionId: Uuid?, val existing: Card?)
/** Editing a card section's description (title kept, body edited as markdown). */
private data class DescModal(val sectionId: Uuid, val title: String, val description: String)
/** Setting a section's PIN. [change] = it already has one and this replaces it. */
private data class PinDialog(val sectionId: Uuid, val change: Boolean)

/**
 * The card group a drag is currently hovering, highlighted as the drop target. [sectionId] null is
 * the ungrouped area — a distinct target from "nothing hovered", which is why this is wrapped.
 */
private data class DropGroup(val sectionId: Uuid?)

/**
 * The drop zone for one group of cards: the ungrouped area or one card section. It wraps the whole
 * group — header, description and grid — so that a card or tab dropped anywhere inside lands in this
 * group; the header strip alone would be a needle to thread. [active] highlights it while hovered.
 *
 * It claims the drag ([accepts]) so that the content area behind it, which saves a dropped tab into
 * no section at all, does not also act on it. The hovered group is tracked on dragover rather than
 * dragenter: dragover keeps firing, so leaving this group for the content area's padding hands the
 * drag back cleanly, with no dragleave bookkeeping.
 */
private fun ChildrenBuilder.cardGroup(
    accepts: Boolean,
    active: Boolean,
    onOver: () -> Unit,
    onDropHere: () -> Unit,
    groupKey: Key? = null,
    content: ChildrenBuilder.() -> Unit,
) {
    div {
        key = groupKey
        className = ClassName(if (active) "card-group drop-active" else "card-group")
        if (accepts) {
            onDragOver = { e ->
                e.preventDefault()
                e.stopPropagation()
                e.dataTransfer.dropEffect = DropEffect.move
                onOver()
            }
            onDrop = { e ->
                e.preventDefault()
                e.stopPropagation()
                onDropHere()
            }
        }
        content()
    }
}

/**
 * One group's cards. A tile takes a drop only while another card is dragged ([draggingCardId]) — and
 * in a [readOnly] collection it neither drags nor drops nor offers its rename and delete buttons.
 */
private fun ChildrenBuilder.cardGrid(
    strings: Strings,
    cards: List<Card>,
    draggingCardId: Uuid?,
    readOnly: Boolean,
    onOpen: (Card) -> Unit,
    onRename: (Card, String) -> Unit,
    onDelete: (Card) -> Unit,
    onStartDrag: (Card) -> Unit,
    onEndDrag: () -> Unit,
    onDropOnTile: (Card) -> Unit,
) {
    div {
        className = ClassName("grid")
        cards.forEach { card ->
            cardTile(
                strings = strings,
                card = card,
                isDraggable = !readOnly,
                readOnly = readOnly,
                isDragging = draggingCardId == card.id,
                acceptsDrop = !readOnly && draggingCardId != null && draggingCardId != card.id,
                onOpen = { onOpen(card) },
                onRename = { name -> onRename(card, name) },
                onDelete = { onDelete(card) },
                onStartDrag = { onStartDrag(card) },
                onEndDrag = onEndDrag,
                onDropHere = { onDropOnTile(card) },
            )
        }
    }
}

/**
 * One browser window in the tabs sidebar: its label and its tab list. Like [cardGroup] it is the drop
 * zone for the whole block, so a tab dropped on the window — rather than on one of its tabs — joins
 * it at the end; that is also how a tab is moved to another window. [active] highlights it while
 * hovered, and [accepts] is set only while a tab is being dragged.
 */
private fun ChildrenBuilder.tabWindow(
    groupKey: Key,
    label: String,
    count: Int,
    accepts: Boolean,
    active: Boolean,
    onOver: () -> Unit,
    onDropHere: () -> Unit,
    content: ChildrenBuilder.() -> Unit,
) {
    div {
        key = groupKey
        className = ClassName(if (active) "tab-window drop-active" else "tab-window")
        if (accepts) {
            onDragOver = { e ->
                e.preventDefault()
                e.dataTransfer.dropEffect = DropEffect.move
                onOver()
            }
            onDrop = { e ->
                e.preventDefault()
                onDropHere()
            }
        }
        div {
            className = ClassName("tab-window-head")
            span { +label }
            span { className = ClassName("count"); +count.toString() }
        }
        content()
    }
}

/**
 * One open browser tab. It is a drag source (onto a collection, to be saved; onto another tab, to be
 * reordered), and — while another tab is dragged ([acceptsDrop]) — a drop target of its own. Clicking
 * it jumps to the tab; the × closes it. Its dragover stops there, so the window behind it does not
 * also claim the drop; the window's own dragover keeps firing in the gaps between tabs and takes the
 * highlight back, which is why nothing here has to track a dragleave.
 */
private fun ChildrenBuilder.tabRow(
    strings: Strings,
    tab: CapturedTab,
    isDragging: Boolean,
    acceptsDrop: Boolean,
    isDropTarget: Boolean,
    onGoTo: () -> Unit,
    onClose: () -> Unit,
    onStartDrag: () -> Unit,
    onEndDrag: () -> Unit,
    onOver: () -> Unit,
    onDropHere: () -> Unit,
) {
    li {
        key = tab.id.toString().unsafeCast<Key>()
        className = ClassName(
            buildString {
                append("tab")
                if (tab.active) append(" current")
                if (isDragging) append(" dragging")
                if (isDropTarget) append(" drop-target")
            },
        )
        title = strings.goToTab
        draggable = true
        onClick = { onGoTo() }
        onDragStart = { e ->
            // Some browsers require drag data to be set or they reject drops.
            e.dataTransfer.setData("text/plain", tab.id.toString())
            onStartDrag()
        }
        onDragEnd = { onEndDrag() }
        if (acceptsDrop) {
            onDragOver = { e ->
                e.preventDefault()
                e.stopPropagation()
                e.dataTransfer.dropEffect = DropEffect.move
                onOver()
            }
            onDrop = { e ->
                e.preventDefault()
                e.stopPropagation()
                onDropHere()
            }
        }
        Favicon {
            url = tab.url
            favicon = tab.favicon
        }
        span {
            className = ClassName("tab-title")
            +tab.title.ifBlank { hostOf(tab.url) }
        }
        button {
            className = ClassName("icon del")
            title = strings.closeTab
            onClick = { e ->
                e.stopPropagation() // closing the tab is not jumping to it
                onClose()
            }
            +"×"
        }
    }
}

external interface AppProps : Props {
    /** Present in the extension (chrome.tabs); null in the web app. Enables "Save open tabs". */
    var tabCapture: TabCapture?

    /** Present in the extension (chrome.history); null in the web app. Enables the history pane. */
    var historyAccess: HistoryAccess?
}

val App = FC<AppProps> { props ->
    val tabCapture = props.tabCapture
    val historyAccess = props.historyAccess

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
    var tabQuery by useState("")
    // The browser window this page is open in: its tabs are the group shown first, as "This window".
    var currentWindowId by useState<Int?>(null)
    var draggingTab by useState<CapturedTab?>(null)
    // The history pane's own list and search box; a dragged history entry is saved, never moved, so
    // unlike a tab it is only ever a drag source.
    var historyEntries by useState<List<HistoryEntry>>(emptyList())
    var historyQuery by useState("")
    var draggingHistory by useState<HistoryEntry?>(null)
    // The tabs-sidebar drop target a dragged tab is over: a window (append) or a tab (take its slot).
    var dropTabWindowId by useState<Int?>(null)
    var dropTabId by useState<Int?>(null)
    var dropCollectionId by useState<Uuid?>(null)
    var dropSectionId by useState<Uuid?>(null)
    var dropGroup by useState<DropGroup?>(null)
    var contentDropActive by useState(false)
    var noteModal by useState<NoteModal?>(null)
    var fileModal by useState<FileModal?>(null)
    var descModal by useState<DescModal?>(null)
    var pinDialog by useState<PinDialog?>(null)
    // The PIN-protected sections opened in this session, and the message under a rejected PIN. The set
    // is state, not a preference: it is deliberately forgotten when the page goes away, so a section
    // is locked again on the next open — and by the idle timer well before that.
    var unlockedSections by useState<Set<Uuid>>(emptySet())
    var unlockError by useState<String?>(null)
    // The locked section whose PIN screen is up because its header was clicked (as opposed to being
    // the section of the selected collection).
    var pendingUnlockId by useState<Uuid?>(null)
    // The unlocked-but-protected section whose lock menu (lock now / change PIN / unprotect) is open.
    var lockMenuId by useState<Uuid?>(null)
    var settingsOpen by useState(false)
    // The section — sidebar or card section; their ids share one space — being renamed in place.
    var renamingId by useState<Uuid?>(null)
    // The pending collapse of a section whose title was just clicked once. See [onTitleClick].
    val clickTimer = useRef<Int>(null)

    // Persisted UI preferences (localStorage): theme, language, card sort, and sidebar collapse state.
    var theme by useState(prefGet("theme") ?: "auto")
    var lang by useState(Lang.from(prefGet("lang")))
    var sortMode by useState(SortMode.from(prefGet("sort")))
    var leftCollapsed by useState(prefGet("leftCollapsed") == "1")
    var rightCollapsed by useState(prefGet("rightCollapsed") == "1")
    var rightPane by useState(RightPane.from(prefGet("rightPane")))
    var autoLockMinutes by useState(prefGet("autoLock")?.toIntOrNull() ?: DEFAULT_AUTO_LOCK_MINUTES)

    // Active translations: every string below reads from here, and the same table is handed to the
    // child components. Named `t`, not `s` — `s` is the store in this file's many `val s = store` blocks.
    val t = lang.strings

    // The sections still behind their PIN, and the collections they hold. Everything that could give
    // one of them away goes through these two sets: the sidebar does not name the collections, their
    // cards are never read out of the database, the global search drops them, an export leaves them
    // out, and nothing can be dropped into them.
    val lockedSectionIds = sections.filter { it.locked && it.id !in unlockedSections }.map { it.id }.toSet()
    val hiddenCollectionIds = collections.filter { it.sectionId in lockedSectionIds }.map { it.id }.toSet()

    // The section whose PIN screen stands in the content area: the one whose header was clicked, or —
    // when the selected collection turns out to be behind a lock, which is what the idle timer does to
    // it — the section holding it.
    val lockTarget = sections.firstOrNull {
        it.id == (
            pendingUnlockId?.takeIf { id -> id in lockedSectionIds }
                ?: collections.firstOrNull { c -> c.id == selectedId }?.sectionId?.takeIf { id -> id in lockedSectionIds }
            )
    }

    // The right sidebar exists where the host gives access to the browser at all, and shows the pane
    // the user last chose — as long as the capability behind it is there, which in the web app (no
    // tabs, no history) is why it has no sidebar to begin with.
    val hasRightSidebar = tabCapture != null || historyAccess != null
    val pane = when {
        historyAccess != null && (rightPane == RightPane.HISTORY || tabCapture == null) -> RightPane.HISTORY
        else -> RightPane.TABS
    }

    useEffectOnce {
        scope.launch {
            val s = openStramusStore()
            // Before the first card is drawn, so a cached icon is there from the very first paint.
            initFaviconCache(s.favicons)
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

    useEffect(lang) {
        applyLang(lang.id)
        prefSet("lang", lang.id)
    }

    // The cards of a locked section are not merely hidden: they are never read out of the database, so
    // there is nothing in the page for a devtools poke at the React tree to find. A collection inside
    // one shows the PIN screen instead, and entering the PIN (which changes `unlockedSections`) runs
    // this again — as does the idle timer locking it back up, which drops the cards from the page.
    useEffect(store, selectedId, unlockedSections) {
        val s = store ?: return@useEffect
        val sel = selectedId
        if (sel == null || sel in hiddenCollectionIds) {
            cards = emptyList()
            cardSections = emptyList()
        } else {
            scope.launch {
                cards = s.cards.byCollection(sel)
                cardSections = s.cardSections.byCollection(sel)
            }
        }
    }

    // Auto-lock: the whole point of a PIN is the machine left unattended, so an unlocked section does
    // not stay unlocked — after `autoLockMinutes` with no sign of the user, every one of them shuts.
    // The watch only runs while something is actually open (and the user has not set the timeout to
    // "never"), and is torn down with the effect, so changing the timeout restarts the countdown.
    useEffect(unlockedSections, autoLockMinutes) {
        if (unlockedSections.isEmpty() || autoLockMinutes <= 0) return@useEffect
        val stopWatching = onIdle(autoLockMinutes * 60_000) {
            unlockedSections = emptySet()
            unlockError = null
            lockMenuId = null
        }
        try {
            awaitCancellation()
        } finally {
            stopWatching()
        }
    }

    useEffect(store, query) {
        val s = store ?: return@useEffect
        val q = query.trim()
        if (q.isBlank()) searchResults = emptyList() else scope.launch { searchResults = s.cards.search(q) }
    }

    // The extension provides tab access: load the open tabs and keep the list live via tab events —
    // opening, closing, navigating, reordering and moving a tab between windows all land here.
    useEffectOnce {
        val tc = tabCapture ?: return@useEffectOnce
        scope.launch {
            currentWindowId = tc.currentWindowId()
            openTabs = tc.currentTabs()
        }
        // App is the page-root and mounts once, so the subscription lives for the page's lifetime.
        tc.onTabsChanged { scope.launch { openTabs = tc.currentTabs() } }
    }

    // The history pane, live in the same way — but only while it is the pane on screen, and re-read
    // whenever the query changes: the search is the browser's own, over every visit it kept, not a
    // filter over the few hundred entries held here.
    //
    // The effect body is a coroutine which React cancels when the pane, the query or the page goes
    // away, so `awaitCancellation` is what keeps the subscription alive exactly as long as the search
    // it belongs to — and a slow search whose query has moved on lands nowhere.
    useEffect(historyAccess, pane, historyQuery) {
        val ha = historyAccess
        if (ha == null || pane != RightPane.HISTORY) return@useEffect
        val q = historyQuery.trim()
        suspend fun load() {
            historyEntries = ha.search(q, HISTORY_LIMIT)
        }
        load()
        val unsubscribe = ha.onHistoryChanged { launch { load() } }
        try {
            awaitCancellation()
        } finally {
            unsubscribe()
        }
    }

    fun reloadCards(collectionId: Uuid?) {
        val s = store ?: return
        val sel = collectionId ?: selectedId ?: return
        if (sel in hiddenCollectionIds) return // a locked section's cards stay out of the page
        scope.launch {
            cards = s.cards.byCollection(sel)
            cardSections = s.cardSections.byCollection(sel)
        }
    }

    // ---- The section PIN lock ----

    // The typed PIN is checked against the section's stored hash and then forgotten; what is
    // remembered is only that this section may be shown, and only until the idle timer or a reload.
    fun unlockSection(id: Uuid, pin: String) {
        val s = store ?: return
        scope.launch {
            if (s.sections.verifyPin(id, pin)) {
                unlockError = null
                pendingUnlockId = null
                unlockedSections = unlockedSections + id
            } else {
                unlockError = t.wrongPin
            }
        }
    }

    // Whoever just chose the PIN is not asked to type it straight back — the section stays open for
    // this session, and locks itself once they walk away.
    fun savePin(id: Uuid, pin: String) {
        val s = store ?: return
        scope.launch {
            s.sections.setPin(id, pin)
            sections = s.sections.all()
            unlockedSections = unlockedSections + id
        }
        pinDialog = null
        lockMenuId = null
    }

    fun removePin(id: Uuid) {
        val s = store ?: return
        scope.launch {
            s.sections.clearPin(id)
            sections = s.sections.all()
        }
        lockMenuId = null
    }

    /** Put the lock back on now — the PIN itself stays as it was. */
    fun lockNow(id: Uuid) {
        unlockedSections = unlockedSections - id
        unlockError = null
        lockMenuId = null
    }

    /** The collection's guard against a slip of the hand — no PIN, just a switch its owner can flip. */
    fun toggleReadOnly(collection: Collection) {
        val s = store ?: return
        scope.launch {
            s.collections.setReadOnly(collection.id, !collection.readOnly)
            collections = s.collections.all()
        }
    }

    // Save a dragged-in tab as a card in [collectionId], under [cardSectionId] (null = ungrouped) —
    // the tab lands in whichever section it was dropped on — then close the browser tab.
    fun saveTab(tab: CapturedTab, collectionId: Uuid, cardSectionId: Uuid? = null) {
        val s = store ?: return
        val tc = tabCapture ?: return
        scope.launch {
            s.cards.add(
                collectionId,
                tab.title.ifBlank { hostOf(tab.url) },
                tab.url,
                tab.favicon ?: faviconFor(tab.url),
                cardSectionId,
            )
            tc.closeTab(tab.id)
            reloadCards(collectionId)
            openTabs = tc.currentTabs()
        }
    }

    // ---- The user's browser tabs, driven from the right sidebar (extension only) ----

    // Every one of these refreshes the list itself rather than waiting for the tab event to come
    // back, so the sidebar redraws the moment the tab does.
    fun goToTab(tab: CapturedTab) {
        val tc = tabCapture ?: return
        scope.launch { tc.activateTab(tab.id, tab.windowId) }
    }

    fun closeTab(tab: CapturedTab) {
        val tc = tabCapture ?: return
        scope.launch {
            tc.closeTab(tab.id)
            openTabs = tc.currentTabs()
        }
    }

    // Drop the dragged tab into [windowId] at [index] — a reorder, or a move to another window, which
    // to the browser is the same thing. [index] is a slot in that window's own tab strip; -1 appends.
    fun moveTabTo(windowId: Int, index: Int) {
        val tc = tabCapture ?: return
        val dragged = draggingTab
        if (dragged != null && !(dragged.windowId == windowId && dragged.index == index)) {
            scope.launch {
                tc.moveTab(dragged.id, windowId, index)
                openTabs = tc.currentTabs()
            }
        }
        draggingTab = null
        dropTabWindowId = null
        dropTabId = null
    }

    // A tab dropped on another tab takes that tab's slot — the browser closes the gap the dragged tab
    // leaves behind, so within one window this reads as "drop it where the target is".
    fun dropOnTab(target: CapturedTab) {
        val dragged = draggingTab ?: return
        if (dragged.id == target.id) return
        moveTabTo(target.windowId, target.index)
    }

    // Only one drop target may be lit at a time: a drag entering the tabs sidebar takes the highlight
    // from whatever the content area was showing, and [leaveTabsSidebar] hands it back.
    fun hoverTabs(windowId: Int, tabId: Int?) {
        if (dropTabWindowId != windowId) dropTabWindowId = windowId
        if (dropTabId != tabId) dropTabId = tabId
        if (dropGroup != null) dropGroup = null
        if (contentDropActive) contentDropActive = false
    }

    fun leaveTabsSidebar() {
        if (dropTabWindowId != null) dropTabWindowId = null
        if (dropTabId != null) dropTabId = null
    }

    // ---- The user's browsing history, driven from the right sidebar's other pane (extension only) ----

    // Forgetting a page is the browser's own removal, so it disappears from the history everywhere.
    // The row is dropped from the list at once; the removal event then re-reads it anyway.
    fun deleteHistoryEntry(entry: HistoryEntry) {
        val ha = historyAccess ?: return
        scope.launch {
            ha.deleteUrl(entry.url)
            historyEntries = historyEntries.filter { it.url != entry.url }
        }
    }

    // Save a dragged-in history entry as a card in [collectionId], under [cardSectionId] (null =
    // ungrouped). Unlike a saved tab there is nothing to close afterwards, and a visit carries no icon
    // URL of its own — the favicon cache resolves one from the host.
    fun saveHistoryEntry(entry: HistoryEntry, collectionId: Uuid, cardSectionId: Uuid? = null) {
        val s = store ?: return
        scope.launch {
            s.cards.add(
                collectionId,
                entry.title.ifBlank { hostOf(entry.url) },
                entry.url,
                faviconFor(entry.url),
                cardSectionId,
            )
            reloadCards(collectionId)
        }
        draggingHistory = null
    }

    // Every card drop lands here: the card joins [collectionId] / [cardSectionId] at [index] within
    // that group (Int.MAX_VALUE = append). Collection, section and order always move together.
    fun moveCard(cardId: Uuid, collectionId: Uuid, cardSectionId: Uuid?, index: Int) {
        val s = store ?: return
        scope.launch {
            s.cards.move(cardId, collectionId, cardSectionId, index)
            reloadCards(selectedId)
        }
        draggingCardId = null
        dropGroup = null
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

    fun renameCard(card: Card, title: String) {
        val s = store ?: return
        scope.launch { s.cards.rename(card.id, title); reloadCards(card.collectionId) }
    }

    fun deleteCard(card: Card) {
        val s = store ?: return
        scope.launch { s.cards.delete(card.id); reloadCards(card.collectionId) }
    }

    fun cancelPendingCollapse() {
        clickTimer.current?.let { cancelDelay(it) }
        clickTimer.current = null
    }

    // A section title takes both clicks: one collapses it, two rename it in place. The collapse is
    // held back for the double-click window so the second click can call it off — run at once, it
    // would collapse and re-expand the section under the pointer before the rename field appeared.
    fun onTitleClick(collapse: () -> Unit) {
        cancelPendingCollapse()
        clickTimer.current = delay(DOUBLE_CLICK_MS) {
            clickTimer.current = null
            collapse()
        }
    }

    fun onTitleDoubleClick(id: Uuid) {
        cancelPendingCollapse()
        renamingId = id
    }

    fun renameSection(section: Section, title: String) {
        val s = store ?: return
        scope.launch {
            s.sections.rename(section.id, title)
            sections = s.sections.all()
        }
        renamingId = null
    }

    fun renameCardSection(cs: CardSection, title: String) {
        val s = store ?: return
        scope.launch {
            s.cardSections.update(cs.id, title, cs.description)
            reloadCards(selectedId)
        }
        renamingId = null
    }

    div {
        className = ClassName("app")

        // ---- Left sidebar (sections + collections), collapsible ----
        if (leftCollapsed) {
            aside {
                className = ClassName("sidebar collapsed")
                button {
                    className = ClassName("rail-toggle")
                    title = t.expandSidebar
                    onClick = { leftCollapsed = false; prefSet("leftCollapsed", "0") }
                    +"»"
                }
                button {
                    className = ClassName("rail-toggle settings-rail")
                    title = t.settings
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
                        title = t.collapseSidebar
                        onClick = { leftCollapsed = true; prefSet("leftCollapsed", "1") }
                        +"«"
                    }
                }
                button {
                    className = ClassName("btn new-section")
                    onClick = {
                        val t = browserPrompt(t.sectionNamePrompt, t.sectionNameDefault)
                        val s = store
                        if (s != null && !t.isNullOrBlank()) {
                            scope.launch {
                                s.sections.create(t.trim())
                                sections = s.sections.all()
                            }
                        }
                    }
                    +t.newSection
                }
                sections.forEach { section ->
                    // A locked section is a closed door: no collections under it, nothing to drop into
                    // it, nothing to rename or delete. Clicking its header asks for the PIN.
                    val isLocked = section.id in lockedSectionIds
                    div {
                        key = key(section.id)
                        className = ClassName(
                            buildString {
                                append("section")
                                if (isLocked) append(" locked")
                                if (section.id == dropSectionId) append(" drop-section")
                            },
                        )
                        // Dropping a collection anywhere on the section (not on a specific item) moves
                        // it to the end of this section.
                        onDragOver = { e -> if (draggingCollectionId != null && !isLocked) e.preventDefault() }
                        onDragEnter = { if (draggingCollectionId != null && !isLocked) dropSectionId = section.id }
                        onDrop = { e ->
                            if (draggingCollectionId != null && !isLocked) {
                                e.preventDefault()
                                moveCollection(section.id, collections.count { it.sectionId == section.id })
                            }
                        }
                        div {
                            className = ClassName("section-head")
                            // Clicking the title collapses/expands the section (hides its
                            // collections); double-clicking renames it right where the text is. While
                            // the section is locked the title does one thing only: ask for the PIN.
                            span {
                                className = ClassName("section-title")
                                title = if (isLocked) t.lockedSection else t.renameHint
                                onClick = {
                                    if (isLocked) {
                                        pendingUnlockId = section.id
                                        unlockError = null
                                    } else {
                                        onTitleClick {
                                            val s = store
                                            if (s != null) {
                                                scope.launch {
                                                    s.sections.setCollapsed(section.id, !section.collapsed)
                                                    sections = s.sections.all()
                                                }
                                            }
                                        }
                                    }
                                }
                                onDoubleClick = { if (!isLocked) onTitleDoubleClick(section.id) }
                                span {
                                    className = ClassName("chevron")
                                    +(if (isLocked) "🔒" else if (section.collapsed) "▸" else "▾")
                                }
                                if (renamingId == section.id && !isLocked) {
                                    InlineEdit {
                                        initial = section.title
                                        onCommit = { name -> renameSection(section, name) }
                                        onCancel = { renamingId = null }
                                    }
                                } else {
                                    +section.title
                                }
                            }
                            if (!isLocked) {
                                // The protection itself: an open section is offered a PIN, a protected
                                // one (necessarily unlocked to be seen) the menu of what to do with it.
                                button {
                                    className = ClassName("icon lock")
                                    title = if (section.locked) t.unlockedSection else t.protectSection
                                    onClick = { e ->
                                        e.stopPropagation()
                                        if (section.locked) lockMenuId = section.id
                                        else pinDialog = PinDialog(section.id, change = false)
                                    }
                                    +(if (section.locked) "🔓" else "🔐")
                                }
                                button {
                                    className = ClassName("icon add")
                                    onClick = { e ->
                                        e.stopPropagation()
                                        val name = browserPrompt(t.collectionNamePrompt, t.collectionNameDefault)
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
                        }
                        if (!section.collapsed && !isLocked) {
                            ul {
                                className = ClassName("collections")
                                collections.filter { it.sectionId == section.id }.forEach { c ->
                                    // A read-only collection takes nothing in — a tab dropped on it
                                    // would be an edit like any other — but it can still be dragged
                                    // and reordered: where it sits is not its content.
                                    val takesContent = !c.readOnly
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
                                        onClick = { selectedId = c.id; pendingUnlockId = null; unlockError = null }
                                        onDragStart = { e ->
                                            e.dataTransfer.setData("text/plain", c.id.toString())
                                            draggingCollectionId = c.id
                                        }
                                        onDragEnd = {
                                            draggingCollectionId = null
                                            dropCollectionId = null
                                            dropSectionId = null
                                        }
                                        // Drop target for: a dragged tab or history entry (saved here),
                                        // a dragged card (moved to this collection), or a dragged
                                        // collection (reordered to this item's position).
                                        // preventDefault marks a valid target — withheld where the
                                        // drop would edit a read-only collection.
                                        onDragEnter = { e ->
                                            if (draggingCollectionId != null || takesContent) {
                                                e.preventDefault()
                                                if (dropCollectionId != c.id) dropCollectionId = c.id
                                            }
                                        }
                                        onDragOver = { e ->
                                            if (draggingCollectionId != null || takesContent) e.preventDefault()
                                            // The drag left the content area; drop the group it was
                                            // last over, or two targets would light up at once.
                                            if (dropGroup != null) dropGroup = null
                                            leaveTabsSidebar()
                                        }
                                        onDragLeave = { if (dropCollectionId == c.id) dropCollectionId = null }
                                        onDrop = { e ->
                                            e.preventDefault()
                                            e.stopPropagation() // don't also fire the section's drop
                                            val tab = draggingTab
                                            val visit = draggingHistory
                                            val draggedCard = draggingCardId
                                            val draggedCol = draggingCollectionId
                                            val s = store
                                            when {
                                                draggedCol != null -> {
                                                    // The dragged collection is spliced into an order
                                                    // it is no longer part of, so its own slot must
                                                    // not count towards the target's index.
                                                    val idx = collections
                                                        .filter { it.sectionId == c.sectionId && it.id != draggedCol }
                                                        .indexOfFirst { it.id == c.id }
                                                    moveCollection(c.sectionId, if (idx < 0) 0 else idx)
                                                }
                                                !takesContent -> Unit // read-only: nothing lands here
                                                tab != null -> saveTab(tab, c.id)
                                                visit != null -> saveHistoryEntry(visit, c.id)
                                                // A card dropped on a collection leaves its section
                                                // behind — that section belongs to the collection it
                                                // came from — and lands ungrouped, at the end.
                                                draggedCard != null && s != null ->
                                                    moveCard(draggedCard, c.id, null, Int.MAX_VALUE)
                                            }
                                            draggingCardId = null
                                            draggingTab = null
                                            draggingHistory = null
                                            dropCollectionId = null
                                        }
                                        span {
                                            className = ClassName("col-title")
                                            +c.title
                                        }
                                        if (c.readOnly) {
                                            span {
                                                className = ClassName("col-lock")
                                                title = t.readOnlyHint
                                                +"🔒"
                                            }
                                        }
                                        // Deleting a read-only collection is the one edit that cannot
                                        // be undone, so the button goes with the rest of them.
                                        if (takesContent) {
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
                }
                // Sidebar footer, pinned to the bottom: opens the settings page.
                div {
                    className = ClassName("sidebar-footer")
                    button {
                        className = ClassName("btn settings-btn")
                        onClick = { settingsOpen = true }
                        span { className = ClassName("gear"); +"⚙" }
                        +" ${t.settings}"
                    }
                }
            }
        }

        main {
            // A tab dropped on a group lands in that group and highlights it instead, so the whole
            // content area only lights up when the drop would go nowhere more specific.
            className = ClassName(
                if (contentDropActive && dropGroup == null) "content drop-active" else "content",
            )
            // The whole content area is a fallback drop zone for a dragged tab or history entry: saved
            // ungrouped into the selected collection. Anything dropped on a group is that group's.
            // What is on the content area has to be a collection that takes edits at all — not a PIN
            // screen (locked section), not a read-only collection, not a page of search results.
            val droppableCollection = collections.firstOrNull {
                it.id == selectedId && it.id !in hiddenCollectionIds && !it.readOnly
            }?.takeIf { query.isBlank() }
            val takesPage = droppableCollection != null && (draggingTab != null || draggingHistory != null)

            onDragEnter = { e -> if (takesPage) e.preventDefault() }
            onDragOver = { e ->
                // A group that is under the pointer stops this event, so reaching here means the
                // drag is over the content area but over no group: no group is the drop target.
                if (dropGroup != null) dropGroup = null
                leaveTabsSidebar()
                if (takesPage) {
                    e.preventDefault()
                    if (!contentDropActive) contentDropActive = true
                }
            }
            onDragLeave = { contentDropActive = false }
            onDrop = { e ->
                e.preventDefault()
                val tab = draggingTab
                val visit = draggingHistory
                if (droppableCollection != null) {
                    when {
                        tab != null -> saveTab(tab, droppableCollection.id)
                        visit != null -> saveHistoryEntry(visit, droppableCollection.id)
                    }
                }
                contentDropActive = false
                draggingTab = null
                draggingHistory = null
            }

            div {
                className = ClassName("topbar")
                input {
                    className = ClassName("search")
                    placeholder = t.searchPlaceholder
                    value = query
                    // Searching is leaving the PIN screen a clicked-on section header put up; the
                    // section stays locked, its cards simply are not among the results.
                    onChange = { e -> query = e.target.value; pendingUnlockId = null }
                }
                div {
                    className = ClassName("toolbar")
                    if (hasRightSidebar && rightCollapsed) {
                        button {
                            className = ClassName("btn")
                            title = t.showTabs
                            onClick = { rightCollapsed = false; prefSet("rightCollapsed", "0") }
                            +t.tabsButton
                        }
                    }
                }
            }

            if (query.isNotBlank()) {
                // The search runs over every card in the database, so it is here that a locked section
                // would otherwise hand its cards to anyone who typed the right word.
                val visibleResults = searchResults.filter { it.collectionId !in hiddenCollectionIds }
                val readOnlyCollections = collections.filter { it.readOnly }.map { it.id }.toSet()
                h2 { +t.resultsFor(query.trim()) }
                if (visibleResults.isEmpty()) {
                    div { className = ClassName("empty"); +t.noMatchingLinks }
                } else {
                    div {
                        className = ClassName("grid")
                        sortMode.apply(visibleResults).forEach { card ->
                            cardTile(
                                strings = t,
                                card = card,
                                isDraggable = false,
                                // A card found by a search is still a card of its collection: if that
                                // one is read-only, the result carries no rename or delete either.
                                readOnly = card.collectionId in readOnlyCollections,
                                onOpen = { openCard(card) },
                                onRename = { name ->
                                    val s = store
                                    if (s != null) scope.launch {
                                        s.cards.rename(card.id, name)
                                        searchResults = s.cards.search(query.trim())
                                        reloadCards(selectedId)
                                    }
                                },
                                onDelete = {
                                    val s = store
                                    if (s != null) scope.launch { s.cards.delete(card.id); searchResults = s.cards.search(query.trim()); reloadCards(selectedId) }
                                },
                            )
                        }
                    }
                }
            } else if (lockTarget != null) {
                // The PIN screen stands in for the whole section: not its cards, not its collections,
                // not even how many of either there are.
                LockScreen {
                    strings = t
                    sectionTitle = lockTarget.title
                    error = unlockError
                    onSubmit = { pin -> unlockSection(lockTarget.id, pin) }
                }
            } else {
                val current = collections.firstOrNull { it.id == selectedId }
                if (current == null) {
                    div { className = ClassName("empty"); +t.createCollectionToStart }
                } else {
                    // Read-only: the collection is read and its links opened, but every control that
                    // would change it — add, sort into, rename, delete, drag — is simply not there.
                    val editable = !current.readOnly
                    div {
                        className = ClassName("content-head")
                        h2 {
                            +current.title
                            if (!editable) {
                                span {
                                    className = ClassName("ro-badge")
                                    title = t.readOnlyHint
                                    +t.readOnlyBadge
                                }
                            }
                        }
                        div {
                            className = ClassName("actions")
                            // Sort order for this collection's cards. Sorting shows the cards in
                            // another order, it does not move them, so it stays even when read-only.
                            select {
                                className = ClassName("control")
                                title = t.sortLinks
                                value = sortMode.id
                                onChange = { e ->
                                    sortMode = SortMode.from(e.target.value)
                                    prefSet("sort", e.target.value)
                                }
                                SortMode.entries.forEach { m ->
                                    option { value = m.id; +m.label(t) }
                                }
                            }
                            // The read-only switch, and — while it is off — everything that edits.
                            button {
                                className = ClassName("btn")
                                onClick = { toggleReadOnly(current) }
                                +(if (editable) t.makeReadOnly else t.allowEditing)
                            }
                            if (editable) {
                                button {
                                    className = ClassName("btn")
                                    onClick = {
                                        val t = browserPrompt(t.sectionNamePrompt, t.sectionNameDefault)
                                        val s = store
                                        if (s != null && !t.isNullOrBlank()) {
                                            scope.launch {
                                                s.cardSections.create(current.id, t.trim(), null)
                                                reloadCards(current.id)
                                            }
                                        }
                                    }
                                    +t.addCardSection
                                }
                            }
                            if (editable) tabCapture?.let { tc ->
                                button {
                                    className = ClassName("btn")
                                    onClick = {
                                        val s = store
                                        if (s != null) {
                                            scope.launch {
                                                // This window's tabs only: the sidebar lists every
                                                // window, but the button is about the one in front.
                                                val wid = currentWindowId
                                                tc.currentTabs()
                                                    .filter { wid == null || it.windowId == wid }
                                                    .forEach { tab ->
                                                        s.cards.add(
                                                            current.id,
                                                            tab.title.ifBlank { hostOf(tab.url) },
                                                            tab.url,
                                                            tab.favicon ?: faviconFor(tab.url),
                                                        )
                                                    }
                                                reloadCards(current.id)
                                            }
                                        }
                                    }
                                    +t.saveOpenTabs
                                }
                            }
                            // "Add link" is the primary action; hovering reveals a menu to add a
                            // markdown note or upload a file instead.
                            if (editable) div {
                                className = ClassName("add-menu")
                                button {
                                    className = ClassName("btn add-card")
                                    onClick = {
                                        val url = browserPrompt(t.pasteUrl)
                                        val s = store
                                        if (s != null && !url.isNullOrBlank()) {
                                            val clean = url.trim()
                                            scope.launch {
                                                s.cards.add(current.id, hostOf(clean), clean, faviconFor(clean))
                                                reloadCards(current.id)
                                            }
                                        }
                                    }
                                    +t.addLink
                                }
                                div {
                                    className = ClassName("add-dropdown")
                                    button {
                                        className = ClassName("add-item")
                                        onClick = {
                                            val url = browserPrompt(t.pasteUrl)
                                            val s = store
                                            if (s != null && !url.isNullOrBlank()) {
                                                val clean = url.trim()
                                                scope.launch {
                                                    s.cards.add(current.id, hostOf(clean), clean, faviconFor(clean))
                                                    reloadCards(current.id)
                                                }
                                            }
                                        }
                                        +t.addLinkItem
                                    }
                                    button {
                                        className = ClassName("add-item")
                                        onClick = { noteModal = NoteModal(current.id, null, null) }
                                        +t.addNoteItem
                                    }
                                    button {
                                        className = ClassName("add-item")
                                        onClick = { fileModal = FileModal(current.id, null, null) }
                                        +t.addFileItem
                                    }
                                }
                            }
                        }
                    }

                    val ungrouped = sortMode.apply(cards.filter { it.cardSectionId == null })
                    val hasSections = cardSections.isNotEmpty()

                    if (cards.isEmpty() && !hasSections) {
                        div { className = ClassName("empty"); +t.noLinksYet }
                    } else {
                        // A card, a tab or a history entry is dropped on a *group* — the ungrouped area
                        // or one card section — and lands in it. [index] is its place among that
                        // group's cards; Int.MAX_VALUE appends.
                        fun dropOnGroup(cardSectionId: Uuid?, index: Int) {
                            val dragged = draggingCardId
                            val tab = draggingTab
                            val visit = draggingHistory
                            when {
                                dragged != null -> moveCard(dragged, current.id, cardSectionId, index)
                                tab != null -> saveTab(tab, current.id, cardSectionId)
                                visit != null -> saveHistoryEntry(visit, current.id, cardSectionId)
                            }
                            draggingCardId = null
                            draggingTab = null
                            draggingHistory = null
                            dropGroup = null
                            contentDropActive = false
                        }

                        // Dropping a card on a tile puts it in that tile's group, right before it.
                        // Under a sort other than MANUAL the order on screen is the sort's to decide,
                        // so there the position would be invisible: the card just joins the group.
                        fun dropOnTile(target: Card) {
                            val dragged = draggingCardId ?: return
                            if (dragged == target.id) return
                            val index = if (sortMode == SortMode.MANUAL) {
                                // The dragged card is spliced into an order it is no longer part of,
                                // so its own slot must not count towards the target's index.
                                cards.filter { it.cardSectionId == target.cardSectionId && it.id != dragged }
                                    .indexOfFirst { it.id == target.id }
                                    .coerceAtLeast(0)
                            } else {
                                Int.MAX_VALUE
                            }
                            dropOnGroup(target.cardSectionId, index)
                        }

                        // Cards, tabs and history entries can all be dropped into a group; collections
                        // cannot, and a read-only collection takes none of the three.
                        val groupAccepts = editable &&
                            (draggingCardId != null || draggingTab != null || draggingHistory != null)

                        // The ungrouped area. With sections around it gets a header of its own —
                        // dropping a card there is how it is taken back out of a section.
                        cardGroup(
                            accepts = groupAccepts,
                            active = dropGroup == DropGroup(null),
                            onOver = {
                                if (dropGroup != DropGroup(null)) dropGroup = DropGroup(null)
                                leaveTabsSidebar()
                            },
                            onDropHere = { dropOnGroup(null, Int.MAX_VALUE) },
                        ) {
                            if (hasSections) {
                                div {
                                    className = ClassName("card-section-head")
                                    span { className = ClassName("card-section-title"); +t.ungrouped }
                                }
                            }
                            if (ungrouped.isNotEmpty()) {
                                cardGrid(
                                    strings = t,
                                    cards = ungrouped,
                                    draggingCardId = draggingCardId,
                                    readOnly = !editable,
                                    onOpen = ::openCard,
                                    onRename = ::renameCard,
                                    onDelete = ::deleteCard,
                                    onStartDrag = { draggingCardId = it.id },
                                    onEndDrag = { draggingCardId = null; dropGroup = null },
                                    onDropOnTile = ::dropOnTile,
                                )
                            } else if (hasSections) {
                                div { className = ClassName("empty small"); +t.dragLinksHere }
                            }
                        }

                        cardSections.sortedBy { it.position }.forEach { cs ->
                            val groupCards = sortMode.apply(cards.filter { it.cardSectionId == cs.id })
                            cardGroup(
                                groupKey = key(cs.id),
                                accepts = groupAccepts,
                                active = dropGroup == DropGroup(cs.id),
                                onOver = {
                                    if (dropGroup != DropGroup(cs.id)) dropGroup = DropGroup(cs.id)
                                    leaveTabsSidebar()
                                },
                                onDropHere = { dropOnGroup(cs.id, Int.MAX_VALUE) },
                            ) {
                                div {
                                    className = ClassName("card-section-head")
                                    span {
                                        className = ClassName("card-section-title")
                                        // Collapsing is not editing — it stays. Renaming does not.
                                        title = if (editable) t.renameHint else ""
                                        onClick = {
                                            onTitleClick {
                                                val s = store
                                                if (s != null) scope.launch {
                                                    s.cardSections.setCollapsed(cs.id, !cs.collapsed)
                                                    cardSections = s.cardSections.byCollection(current.id)
                                                }
                                            }
                                        }
                                        onDoubleClick = { if (editable) onTitleDoubleClick(cs.id) }
                                        span { className = ClassName("chevron"); +(if (cs.collapsed) "▸" else "▾") }
                                        if (renamingId == cs.id && editable) {
                                            InlineEdit {
                                                initial = cs.title
                                                onCommit = { name -> renameCardSection(cs, name) }
                                                onCancel = { renamingId = null }
                                            }
                                        } else {
                                            +cs.title
                                        }
                                        span { className = ClassName("count"); +" ${groupCards.size}" }
                                    }
                                    if (editable) div {
                                        className = ClassName("card-section-tools")
                                        button {
                                            className = ClassName("icon edit")
                                            title = t.editDescription
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
                                // A collapsed section still takes drops — its header is inside the
                                // group — and the card joins it, out of sight.
                                if (!cs.collapsed) {
                                    if (groupCards.isEmpty()) {
                                        div { className = ClassName("empty small"); +t.dragLinksHere }
                                    } else {
                                        cardGrid(
                                            strings = t,
                                            cards = groupCards,
                                            draggingCardId = draggingCardId,
                                            readOnly = !editable,
                                            onOpen = ::openCard,
                                            onRename = ::renameCard,
                                            onDelete = ::deleteCard,
                                            onStartDrag = { draggingCardId = it.id },
                                            onEndDrag = { draggingCardId = null; dropGroup = null },
                                            onDropOnTile = ::dropOnTile,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // ---- Right sidebar: the browser itself — open tabs or history (extension only), collapsible.
        // Both panes save a page the same way: drag it onto a collection, a card section, or the
        // content area. A tab is *moved* out of the browser (it is closed once saved); a visited page
        // is only copied — the history keeps it. ----
        if (hasRightSidebar) {
            if (rightCollapsed) {
                aside {
                    className = ClassName("tabs collapsed")
                    button {
                        className = ClassName("rail-toggle")
                        title = t.showTabs
                        onClick = { rightCollapsed = false; prefSet("rightCollapsed", "0") }
                        +"«"
                    }
                }
            } else {
                aside {
                    className = ClassName("tabs")
                    div {
                        className = ClassName("tabs-head")
                        // The pane switch. Each half is drawn only where the host grants it, so a
                        // build without one of the two capabilities simply shows the other.
                        div {
                            className = ClassName("pane-switch")
                            if (tabCapture != null) {
                                button {
                                    className = ClassName(
                                        if (pane == RightPane.TABS) "pane-tab active" else "pane-tab",
                                    )
                                    onClick = {
                                        rightPane = RightPane.TABS
                                        prefSet("rightPane", RightPane.TABS.id)
                                    }
                                    +t.paneTabs
                                }
                            }
                            if (historyAccess != null) {
                                button {
                                    className = ClassName(
                                        if (pane == RightPane.HISTORY) "pane-tab active" else "pane-tab",
                                    )
                                    onClick = {
                                        rightPane = RightPane.HISTORY
                                        prefSet("rightPane", RightPane.HISTORY.id)
                                    }
                                    +t.paneHistory
                                }
                            }
                        }
                        button {
                            className = ClassName("icon collapse-btn")
                            title = t.hideTabs
                            onClick = { rightCollapsed = true; prefSet("rightCollapsed", "1") }
                            +"»"
                        }
                    }

                    if (pane == RightPane.TABS) {
                        // Tabs matching the sidebar's own search box (title or URL). The search only
                        // hides rows: a tab still knows its real place in its window, so a drag lands
                        // correctly.
                        val tabFilter = tabQuery.trim().lowercase()
                        val matchingTabs = if (tabFilter.isBlank()) {
                            openTabs
                        } else {
                            openTabs.filter {
                                tabFilter in it.title.lowercase() || tabFilter in it.url.lowercase()
                            }
                        }
                        // One group per browser window, this page's window first ("This window"), the
                        // rest numbered in the browser's own order. Numbering comes from every window,
                        // not just the matching ones, so a search cannot renumber the windows under
                        // the user.
                        val windowIds = openTabs.map { it.windowId }
                            .distinct()
                            .sortedWith(compareBy({ it != currentWindowId }, { it }))

                        if (openTabs.isNotEmpty()) {
                            input {
                                className = ClassName("tab-search")
                                placeholder = t.searchTabs
                                value = tabQuery
                                onChange = { e -> tabQuery = e.target.value }
                            }
                        }
                        when {
                            openTabs.isEmpty() -> div { className = ClassName("empty small"); +t.noOpenTabs }
                            matchingTabs.isEmpty() -> div { className = ClassName("empty small"); +t.noMatchingTabs }
                            else -> div {
                                className = ClassName("tabs-body")
                                windowIds.forEachIndexed { i, windowId ->
                                    val windowTabs = matchingTabs
                                        .filter { it.windowId == windowId }
                                        .sortedBy { it.index }
                                    // A window whose tabs the search filtered out drops out of the list.
                                    if (windowTabs.isEmpty()) return@forEachIndexed
                                    tabWindow(
                                        groupKey = windowId.toString().unsafeCast<Key>(),
                                        label = if (windowId == currentWindowId) t.thisWindow else t.windowLabel(i + 1),
                                        count = windowTabs.size,
                                        // Only a dragged tab can be dropped here; cards, collections
                                        // and visited pages have no place in the browser's tab strip.
                                        accepts = draggingTab != null,
                                        active = dropTabWindowId == windowId && dropTabId == null,
                                        onOver = { hoverTabs(windowId, null) },
                                        // Dropped on the window but on none of its tabs: append (-1).
                                        onDropHere = { moveTabTo(windowId, -1) },
                                    ) {
                                        ul {
                                            className = ClassName("tab-list")
                                            windowTabs.forEach { tab ->
                                                tabRow(
                                                    strings = t,
                                                    tab = tab,
                                                    isDragging = draggingTab?.id == tab.id,
                                                    acceptsDrop = draggingTab != null && draggingTab?.id != tab.id,
                                                    isDropTarget = dropTabId == tab.id,
                                                    onGoTo = { goToTab(tab) },
                                                    onClose = { closeTab(tab) },
                                                    onStartDrag = { draggingTab = tab },
                                                    onEndDrag = {
                                                        draggingTab = null
                                                        dropCollectionId = null
                                                        dropGroup = null
                                                        contentDropActive = false
                                                        dropTabWindowId = null
                                                        dropTabId = null
                                                    },
                                                    onOver = { hoverTabs(tab.windowId, tab.id) },
                                                    onDropHere = { dropOnTab(tab) },
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        // The history's search box stays on screen even when nothing matched — it is
                        // what the user has to reach for to get their history back.
                        input {
                            className = ClassName("tab-search")
                            placeholder = t.searchHistory
                            value = historyQuery
                            onChange = { e -> historyQuery = e.target.value }
                        }
                        when {
                            historyEntries.isNotEmpty() -> historyPane(
                                strings = t,
                                locale = lang.id,
                                entries = historyEntries,
                                draggingUrl = draggingHistory?.url,
                                onOpen = { entry -> openUrl(entry.url) },
                                onDelete = ::deleteHistoryEntry,
                                onStartDrag = { entry -> draggingHistory = entry },
                                onEndDrag = {
                                    draggingHistory = null
                                    dropCollectionId = null
                                    dropGroup = null
                                    contentDropActive = false
                                },
                            )
                            historyQuery.isBlank() -> div { className = ClassName("empty small"); +t.noHistory }
                            else -> div { className = ClassName("empty small"); +t.noMatchingHistory }
                        }
                    }
                }
            }
        }

        // ---- Modals ----
        noteModal?.let { m ->
            NoteEditor {
                strings = t
                heading = if (m.existing != null) t.editNote else t.newNote
                showTitle = true
                initialTitle = m.existing?.title ?: ""
                initialContent = m.existing?.content ?: ""
                // A note of a read-only collection opens to be read, not written: the editor comes up
                // without its toolbar, its caret or its Save.
                readOnly = collections.any { it.id == m.collectionId && it.readOnly }
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
                strings = t
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
        pinDialog?.let { d ->
            PinModal {
                strings = t
                change = d.change
                onSave = { pin -> savePin(d.sectionId, pin) }
                onClose = { pinDialog = null }
            }
        }
        lockMenuId?.let { id ->
            val section = sections.firstOrNull { it.id == id }
            if (section != null) {
                LockMenuModal {
                    strings = t
                    sectionTitle = section.title
                    onLockNow = { lockNow(id) }
                    onChangePin = { lockMenuId = null; pinDialog = PinDialog(id, change = true) }
                    onRemove = { removePin(id) }
                    onClose = { lockMenuId = null }
                }
            }
        }
        if (settingsOpen) {
            SettingsModal {
                strings = t
                this.theme = theme
                onThemeChange = { theme = it }
                this.lang = lang.id
                onLangChange = { lang = Lang.from(it) }
                this.autoLockMinutes = autoLockMinutes
                onAutoLockChange = { minutes ->
                    autoLockMinutes = minutes
                    prefSet("autoLock", minutes.toString())
                }
                // An export is a file the user can open anywhere, so a section whose PIN has not been
                // entered stays out of it — otherwise it would be the way around the lock.
                onExportCsv = { store?.let { s -> scope.launch { exportCsv(s, lockedSectionIds) } } }
                onExportBookmarks = { store?.let { s -> scope.launch { exportBookmarks(s, lockedSectionIds) } } }
                onClose = { settingsOpen = false }
            }
        }
        descModal?.let { m ->
            NoteEditor {
                strings = t
                heading = t.sectionDescription
                showTitle = false
                initialTitle = m.title
                initialContent = m.description
                readOnly = false // only reachable from a collection that takes edits
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
