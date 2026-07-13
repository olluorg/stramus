@file:OptIn(ExperimentalUuidApi::class)

package stramus.ui

import kotlinx.coroutines.MainScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import react.ChildrenBuilder
import react.FC
import react.Key
import react.Props
import react.memo
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
import react.useCallback
import react.useEffect
import react.useEffectOnce
import react.useMemo
import react.useRef
import react.useState
import stramus.core.db.StramusStore
import stramus.core.db.openStramusStore
import stramus.core.model.Card
import stramus.core.model.CardKind
import stramus.core.model.CardSection
import stramus.core.model.Collection
import stramus.core.model.Section
import stramus.core.platform.AiAssistant
import stramus.core.platform.AiAvailability
import stramus.core.platform.CapturedTab
import stramus.core.platform.HistoryAccess
import stramus.core.platform.HistoryEntry
import stramus.core.platform.TabCapture
import stramus.core.platform.WebSearchAccess
import web.cssom.ClassName
import web.data.DropEffect
import web.data.move
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

private val scope = MainScope()

/**
 * How long the search box has to stand still before the query reaches the database and the browser's
 * history. Short: the tabs, the collections and the user's top sites are ranked from memory and appear
 * on the very keystroke, so this is only what the slower half of the list waits for.
 */
private const val SEARCH_DEBOUNCE_MS = 120L

/** How many visited pages the search box asks the browser for. Ranking picks a few of them at most. */
private const val HISTORY_HITS = 12

/**
 * How many of the open collection's cards the model is told about. Enough for "what did I save about
 * X" to have an answer; few enough that a large collection does not eat the model's whole context
 * before the question is even asked.
 */
private const val AI_CONTEXT_CARDS = 50

/**
 * How long the way back stays open after a deletion. The deletion itself is done — the rows are out
 * of the database, the search no longer finds them — and this is the window in which the snapshot the
 * repository handed back can put every one of them where it was, id and all.
 */
private const val UNDO_MS = 30_000

internal fun key(id: Uuid): Key = id.toString().unsafeCast<Key>()

/**
 * A deletion the user can still take back: what to tell them, and what to run if they do. Nothing but
 * a section, a collection or a card section gets one — deleting a single card is one click to undo by
 * hand, deleting a section is not.
 */
private data class Undo(val message: String, val restore: suspend () -> Unit)

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
 *
 * Every callback is handed down as it comes, never wrapped per card ({ onOpen(card) } and the like):
 * [CardTile] is memoized on its props, and a lambda built here would be a new one on every render,
 * which is to say the memo would never hold and a drag would redraw the whole grid on every mouse
 * move. The tile passes its own card back instead, and `App` keeps these steady with `useCallback`.
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
            CardTile {
                key = key(card.id)
                this.strings = strings
                this.card = card
                this.isDraggable = !readOnly
                this.readOnly = readOnly
                this.isDragging = draggingCardId == card.id
                this.acceptsDrop = !readOnly && draggingCardId != null && draggingCardId != card.id
                this.onOpen = onOpen
                this.onRename = onRename
                this.onDelete = onDelete
                this.onStartDrag = onStartDrag
                this.onEndDrag = onEndDrag
                this.onDropHere = onDropOnTile
            }
        }
    }
}

/**
 * One browser window in the tabs sidebar: its label and its tab list. Like [cardGroup] it is the drop
 * zone for the whole block, so a tab dropped on the window — rather than on one of its tabs — joins
 * it at the end; that is also how a tab is moved to another window. [active] highlights it while
 * hovered, and [accepts] is set only while a tab is being dragged.
 *
 * The ⇅ in the header sorts this window ([onSort]). It is a `select` used as a menu of actions rather
 * than as a setting: there is no such thing as a window "currently sorted by title" — the sort moves
 * the browser's tabs once and is done, and a tab opened a second later lands wherever the browser puts
 * it. So it always shows the ⇅ back, never the choice last made.
 *
 * The ⤓ next to it saves the whole window into the open collection ([onSave]) — the drag, done to every
 * tab at once. [saveHint] is both its tooltip and its condition: null where there is no collection to
 * save into, and then the button is not there at all.
 */
private fun ChildrenBuilder.tabWindow(
    strings: Strings,
    groupKey: Key,
    label: String,
    count: Int,
    accepts: Boolean,
    active: Boolean,
    saveHint: String?,
    onOver: () -> Unit,
    onDropHere: () -> Unit,
    onSave: () -> Unit,
    onSort: (TabSort) -> Unit,
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
            div {
                className = ClassName("tab-window-tools")
                if (saveHint != null) {
                    button {
                        className = ClassName("tab-save")
                        hint(saveHint)
                        onClick = { onSave() }
                        +"⤓"
                    }
                }
                select {
                    className = ClassName("tab-sort")
                    hint(strings.sortTabs)
                    value = "" // the ⇅ itself: see above, this is a menu, not the window's state
                    onChange = { e -> TabSort.from(e.target.value)?.let(onSort) }
                    option { value = ""; +"⇅" }
                    TabSort.entries.forEach { by ->
                        option { value = by.id; +by.label(strings) }
                    }
                }
                span { className = ClassName("count"); +count.toString() }
            }
        }
        content()
    }
}

external interface TabRowProps : Props {
    var strings: Strings
    var tab: CapturedTab
    var isDragging: Boolean
    var acceptsDrop: Boolean
    var isDropTarget: Boolean

    // Each hands back the tab it happened to, for the same reason [CardTileProps] does: one callback
    // serves every row, so a memoized row's props stay the ones it already has.
    var onGoTo: (CapturedTab) -> Unit
    var onClose: (CapturedTab) -> Unit
    var onStartDrag: (CapturedTab) -> Unit
    var onEndDrag: () -> Unit
    var onOver: (CapturedTab) -> Unit
    var onDropHere: (CapturedTab) -> Unit
}

/**
 * One open browser tab. It is a drag source (onto a collection, to be saved; onto another tab, to be
 * reordered), and — while another tab is dragged (`acceptsDrop`) — a drop target of its own. Clicking
 * it jumps to the tab; the × closes it. Its dragover stops there, so the window behind it does not
 * also claim the drop; the window's own dragover keeps firing in the gaps between tabs and takes the
 * highlight back, which is why nothing here has to track a dragleave.
 *
 * Memoized like [CardTile], and for the same reason: a drag anywhere on the page runs through `App`'s
 * state, and the tab list has no part in most of it.
 */
val TabRow = memo(
    FC<TabRowProps> { props ->
        val tab = props.tab

        li {
            className = ClassName(
                buildString {
                    append("tab")
                    if (tab.active) append(" current")
                    if (props.isDragging) append(" dragging")
                    if (props.isDropTarget) append(" drop-target")
                },
            )
            hint(props.strings.goToTab)
            draggable = true
            onClick = { props.onGoTo(tab) }
            onDragStart = { e ->
                // Some browsers require drag data to be set or they reject drops.
                e.dataTransfer.setData("text/plain", tab.id.toString())
                props.onStartDrag(tab)
            }
            onDragEnd = { props.onEndDrag() }
            if (props.acceptsDrop) {
                onDragOver = { e ->
                    e.preventDefault()
                    e.stopPropagation()
                    e.dataTransfer.dropEffect = DropEffect.move
                    props.onOver(tab)
                }
                onDrop = { e ->
                    e.preventDefault()
                    e.stopPropagation()
                    props.onDropHere(tab)
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
                hint(props.strings.closeTab)
                onClick = { e ->
                    e.stopPropagation() // closing the tab is not jumping to it
                    props.onClose(tab)
                }
                +"×"
            }
        }
    },
)

external interface AppProps : Props {
    /** Present in the extension (chrome.tabs); null in the web app. Enables "Save open tabs". */
    var tabCapture: TabCapture?

    /** Present in the extension (chrome.history); null in the web app. Enables the history pane. */
    var historyAccess: HistoryAccess?

    /**
     * The browser's own on-device model, where it has one (`builtInAi()`); null everywhere else, and
     * then the search box simply never offers to ask it.
     */
    var ai: AiAssistant?

    /**
     * The browser's own search (chrome.search in the extension); null in the web app, which cannot ask
     * which engine the user chose and falls back to a URL of its own.
     */
    var webSearch: WebSearchAccess?
}

val App = FC<AppProps> { props ->
    val tabCapture = props.tabCapture
    val historyAccess = props.historyAccess
    val ai = props.ai
    val webSearch = props.webSearch

    var store by useState<StramusStore?>(null)
    var sections by useState<List<Section>>(emptyList())
    var collections by useState<List<Collection>>(emptyList())
    var selectedId by useState<Uuid?>(null)
    var cards by useState<List<Card>>(emptyList())
    var cardSections by useState<List<CardSection>>(emptyList())
    // Bumped when the one-shot preview backfill writes a thumbnail, to redraw the cards it changed.
    var thumbsVersion by useState(0)
    var query by useState("")
    // The cards matching the query (they feed both the dropdown and the full grid) and the visited
    // pages matching it — the two halves of the search that have to be asked for, rather than ranked
    // from what is already on screen.
    var searchResults by useState<List<Card>>(emptyList())
    var searchHistory by useState<List<HistoryEntry>>(emptyList())
    // Ctrl/Cmd+Enter: every matching card as a grid, instead of the best few in the dropdown.
    var showAllResults by useState(false)
    // Bumped whenever a page is opened, so what is ranked by how much the user uses it is re-ranked:
    // the frecency index itself lives outside React (see Usage.kt).
    var usageVersion by useState(0)
    // The box is a question to the model rather than a search. [aiQuestion] is the question actually
    // asked — the field is cleared for the next one, and re-asking the same thing is the same question.
    var aiMode by useState(false)
    var aiQuestion by useState("")
    // What the browser's model can do for us — shown in the settings, and what decides whether the box
    // offers to ask it at all. Null until the question has been put to the browser (or there is no
    // model to put it to).
    var aiState by useState<AiAvailability?>(null)
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
    // The section, collection or card section — their ids share one space — being renamed in place.
    var renamingId by useState<Uuid?>(null)
    // The last deletion, for as long as it can still be taken back. See [UNDO_MS].
    var undo by useState<Undo?>(null)
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
    // Saving a window's tabs into a collection closes them, as dragging one there does: the tab has
    // been put away, and leaving it open would be to have it in two places. Unset means the default.
    var closeSavedTabs by useState(prefGet("closeSavedTabs") != "0")

    // Active translations: every string below reads from here, and the same table is handed to the
    // child components. Named `t`, not `s` — `s` is the store in this file's many `val s = store` blocks.
    val t = lang.strings

    // The sections still behind their PIN, and the collections they hold. Everything that could give
    // one of them away goes through these two sets: the sidebar does not name the collections, their
    // cards are never read out of the database, the global search drops them, an export leaves them
    // out, and nothing can be dropped into them.
    //
    // Held across renders, not rebuilt on each one: a fresh set every time would be a different set to
    // React, and the callbacks below — which is to say the memoized cards hanging off them — would be
    // rebuilt with it on every mouse move of a drag.
    val lockedSectionIds = useMemo(sections, unlockedSections) {
        sections.filter { it.locked && it.id !in unlockedSections }.map { it.id }.toSet()
    }
    val hiddenCollectionIds = useMemo(collections, lockedSectionIds) {
        collections.filter { it.sectionId in lockedSectionIds }.map { it.id }.toSet()
    }

    // The selected collection's cards, already split into the groups the page draws and sorted the way
    // the user asked for — done once per change to the cards or the sort, rather than once per group
    // per render. The ungrouped ones are under the null key.
    val cardsByGroup = useMemo(cards, sortMode) {
        cards.groupBy { it.cardSectionId }.mapValues { (_, group) -> sortMode.apply(group) }
    }
    val orderedCardSections = useMemo(cardSections) { cardSections.sortedBy { it.position } }

    // Tabs matching the sidebar's own search box (title or URL). The search only hides rows: a tab
    // still knows its real place in its window, so a drag lands correctly. Lowercasing every tab's
    // title and URL is not something to redo on each render — and a drag makes for a great many.
    val matchingTabs = useMemo(openTabs, tabQuery) {
        val filter = tabQuery.trim().lowercase()
        if (filter.isBlank()) {
            openTabs
        } else {
            openTabs.filter { filter in it.title.lowercase() || filter in it.url.lowercase() }
        }
    }

    // One group per browser window, this page's window first ("This window"), the rest numbered in the
    // browser's own order. Numbering comes from every window, not just the matching ones, so a search
    // cannot renumber the windows under the user.
    val windowIds = useMemo(openTabs, currentWindowId) {
        openTabs.map { it.windowId }.distinct().sortedWith(compareBy({ it != currentWindowId }, { it }))
    }
    val tabsByWindow = useMemo(matchingTabs) {
        matchingTabs.groupBy { it.windowId }.mapValues { (_, tabs) -> tabs.sortedBy { it.index } }
    }

    // The section whose PIN screen stands in the content area: the one whose header was clicked, or —
    // when the selected collection turns out to be behind a lock, which is what the idle timer does to
    // it — the section holding it.
    val lockTarget = sections.firstOrNull {
        it.id == (
            pendingUnlockId?.takeIf { id -> id in lockedSectionIds }
                ?: collections.firstOrNull { c -> c.id == selectedId }?.sectionId?.takeIf { id -> id in lockedSectionIds }
            )
    }

    // The collection anything saved from outside the content area lands in: the selected one, as long
    // as it takes edits at all — not one inside a locked section, not a read-only one. Null means there
    // is nowhere to put a page right now, and every control that would save one is simply not offered.
    val targetCollection = collections.firstOrNull {
        it.id == selectedId && it.id !in hiddenCollectionIds && !it.readOnly
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
            // The seed is only ever used by a database that has never held anything (see `StoreSeed`),
            // and it is in the language the user arrived with — the one the browser asked for, since
            // nobody has chosen one yet on the install this actually happens on.
            val s = openStramusStore(seed = t.seed)
            // Before the first card is drawn, so a cached icon is there from the very first paint.
            initFaviconCache(s.favicons)
            // Before the first keystroke, so the very first search is already ranked by what the user
            // uses — and an empty box already offers their top sites.
            initUsageIndex(s.usage)
            usageVersion += 1
            val secs = s.sections.all()
            val cols = s.collections.all()
            store = s
            sections = secs
            collections = cols
            selectedId = cols.firstOrNull()?.id

            // Files saved before previews existed have none, and their bytes are no longer part of a
            // card: make each one a preview, once. Behind the first paint — it reads whole files, and
            // nothing on screen is waiting for it.
            if (backfillThumbs(s)) thumbsVersion += 1
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
    useEffect(store, selectedId, unlockedSections, thumbsVersion) {
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

    // The slow half of the search: every card in the database, and every page the browser remembers.
    // Neither is asked on every keystroke — React cancels this effect when the query moves on, and a
    // cancelled coroutine never reaches either of them. That also settles which answer wins: the one
    // still being typed towards, not whichever query happened to finish last.
    //
    // The fast half — the open tabs, the collections, the user's own top sites — is ranked from memory
    // and needs no effect at all; it is in the list before this one comes back.
    useEffect(store, historyAccess, query) {
        val s = store ?: return@useEffect
        val q = query.trim()
        if (q.isBlank()) {
            searchResults = emptyList()
            searchHistory = emptyList()
            return@useEffect
        }
        kotlinx.coroutines.delay(SEARCH_DEBOUNCE_MS)
        searchResults = s.cards.search(q)
        searchHistory = historyAccess?.search(q, HISTORY_HITS).orEmpty()
    }

    // Whether the browser has a model to ask at all. Asked once: the answer is about the machine, not
    // about anything the user does here.
    useEffect(ai) {
        val assistant = ai ?: return@useEffect
        aiState = assistant.availability()
    }

    // A model that has to be downloaded first is still a model to offer: the first question starts the
    // download, and the panel shows it happening rather than pretending to think.
    val aiAvailable = aiState != null && aiState != AiAvailability.UNAVAILABLE

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

    // The open tabs, reachable from a callback without being one of its dependencies: [onCardOpen] is
    // a prop of every memoized card tile, and a tab opening or navigating anywhere in the browser must
    // not rebuild that callback — and with it redraw the whole grid.
    val openTabsRef = useRef(openTabs)
    openTabsRef.current = openTabs

    /**
     * Open a page — or, if it is already open somewhere, go to the tab that has it. A page the user is
     * already looking at is not something to open a second copy of, and this is the only way out of
     * stramus, so the rule holds wherever a link is followed from: a card in the grid, a row of the
     * search box, an entry of the history.
     *
     * Counting the page happens either way: going back to something is using it as much as opening it
     * is, and the search box ranks by what the user uses.
     */
    fun openPage(url: String, title: String) {
        recordUse(url, title)
        usageVersion += 1

        val tc = tabCapture
        val key = normalizeUrl(url)
        val alreadyOpen = if (tc == null || key.isBlank()) {
            null
        } else {
            openTabsRef.current.orEmpty().firstOrNull { normalizeUrl(it.url) == key }
        }
        if (tc != null && alreadyOpen != null) {
            scope.launch { tc.activateTab(alreadyOpen.id, alreadyOpen.windowId) }
        } else {
            openUrl(url)
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

    /**
     * Save [tabs] into [collectionId] as cards, ungrouped — the ⤓ on a window's header, and the one in
     * the collection's toolbar, are both this: the drag done to a whole window at once. They land under
     * no card section for the same reason a tab dropped on the content area does: which section they
     * belong to is the user's to say afterwards, by dragging them.
     *
     * Whether the browser keeps the tabs is the user's standing answer (`closeSavedTabs`, the setting);
     * a tab dragged into a collection one at a time is always closed, that drag being a move.
     *
     * It is asked first, and the question says which way the setting has it: a whole window at once is
     * a great many cards, and — where the tabs are closed with it — a great many windows' worth of work
     * that nothing here can put back. (The deletions have their undo; the browser's tabs have none.)
     *
     * The stramus page itself is never in this list — [TabCapture] lists only http(s) pages — so
     * closing a window's tabs cannot close the page doing the closing.
     */
    fun saveTabs(tabs: List<CapturedTab>, collection: Collection) {
        val s = store ?: return
        val tc = tabCapture ?: return
        if (tabs.isEmpty()) return
        if (!browserConfirm(t.confirmSaveTabs(tabs.size, collection.title, closeSavedTabs))) return
        scope.launch {
            tabs.forEach { tab ->
                s.cards.add(
                    collection.id,
                    tab.title.ifBlank { hostOf(tab.url) },
                    tab.url,
                    tab.favicon ?: faviconFor(tab.url),
                )
            }
            if (closeSavedTabs) tabs.forEach { tc.closeTab(it.id) }
            reloadCards(collection.id)
            openTabs = tc.currentTabs()
        }
    }

    // ---- The user's browser tabs, driven from the right sidebar (extension only) ----

    // Every one of these refreshes the list itself rather than waiting for the tab event to come
    // back, so the sidebar redraws the moment the tab does.

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

    // Sort one window's tabs — where a drag moves one tab, this puts the whole strip in order, in the
    // browser itself and not merely in this list. Every tab of the window goes into the sort, not just
    // the ones a search has left on screen: the strip is the user's, and half of it quietly reshuffled
    // to fit a query they typed here would be no kind of sort at all.
    fun sortTabs(windowId: Int, by: TabSort) {
        val tc = tabCapture ?: return
        scope.launch {
            tc.reorderTabs(windowId, by.apply(openTabs.filter { it.windowId == windowId }).map { it.id })
            openTabs = tc.currentTabs()
        }
    }

    // Only one drop target may be lit at a time: a drag entering the tabs sidebar takes the highlight
    // from whatever the content area was showing, and [leaveTabsSidebar] hands it back.
    //
    // Written unconditionally, though this runs on every dragover: React drops an update that sets the
    // state it is already in, so re-assigning the same target — which is what nearly every one of
    // these events does — costs a comparison and no render. Guarding it here instead would mean
    // reading state, and this is reached from a callback that outlives the render that made it.
    val hoverTabs = useCallback { windowId: Int, tabId: Int? ->
        dropTabWindowId = windowId
        dropTabId = tabId
        dropGroup = null
        contentDropActive = false
    }

    fun leaveTabsSidebar() {
        if (dropTabWindowId != null) dropTabWindowId = null
        if (dropTabId != null) dropTabId = null
    }

    // ---- What a tab row does, held steady across renders. See the card callbacks above. ----

    val onTabGoTo = useCallback(tabCapture, usageVersion) { tab: CapturedTab ->
        val tc = tabCapture ?: return@useCallback
        // Going back to a tab is using the page as much as opening it is: it counts the same.
        recordUse(tab.url, tab.title)
        usageVersion += 1
        scope.launch { tc.activateTab(tab.id, tab.windowId) }
    }

    val onTabClose = useCallback(tabCapture) { tab: CapturedTab ->
        val tc = tabCapture ?: return@useCallback
        scope.launch {
            tc.closeTab(tab.id)
            openTabs = tc.currentTabs()
        }
    }

    val onTabStartDrag = useCallback { tab: CapturedTab -> draggingTab = tab }

    val onTabEndDrag = useCallback {
        draggingTab = null
        dropCollectionId = null
        dropGroup = null
        contentDropActive = false
        dropTabWindowId = null
        dropTabId = null
    }

    val onTabOver = useCallback(hoverTabs) { tab: CapturedTab -> hoverTabs(tab.windowId, tab.id) }

    val onTabDropHere = useCallback(tabCapture, draggingTab) { target: CapturedTab -> dropOnTab(target) }

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

    // A card, a tab or a history entry is dropped on a *group* — the ungrouped area or one card
    // section — of [collectionId] and lands in it. [index] is its place among that group's cards;
    // Int.MAX_VALUE appends.
    fun dropOnGroup(collectionId: Uuid, cardSectionId: Uuid?, index: Int) {
        val dragged = draggingCardId
        val tab = draggingTab
        val visit = draggingHistory
        when {
            dragged != null -> moveCard(dragged, collectionId, cardSectionId, index)
            tab != null -> saveTab(tab, collectionId, cardSectionId)
            visit != null -> saveHistoryEntry(visit, collectionId, cardSectionId)
        }
        draggingCardId = null
        draggingTab = null
        draggingHistory = null
        dropGroup = null
        contentDropActive = false
    }

    // ---- What a card tile does, held steady across renders ----
    //
    // These are the props of a memoized [CardTile], so what matters as much as what they do is that
    // they are the *same functions* as on the last render: rebuilt each time, they would defeat the
    // memo and every dragover — several a second — would redraw every card on screen. Each is
    // therefore tied to the state it actually reads, and changes only when that does.

    // Open a card by its kind: a link goes to its page — or to the tab already showing it, see
    // [openPage] — notes open the markdown editor, files open the viewer.
    val onCardOpen = useCallback(usageVersion, tabCapture) { card: Card ->
        when (card.kind) {
            CardKind.LINK -> openPage(card.url, card.title)
            CardKind.NOTE -> noteModal = NoteModal(card.collectionId, card.cardSectionId, card)
            CardKind.FILE -> fileModal = FileModal(card.collectionId, card.cardSectionId, card)
        }
    }

    val onCardRename = useCallback(store, hiddenCollectionIds) { card: Card, title: String ->
        val s = store ?: return@useCallback
        scope.launch { s.cards.rename(card.id, title); reloadCards(card.collectionId) }
    }

    val onCardDelete = useCallback(store, hiddenCollectionIds) { card: Card ->
        val s = store ?: return@useCallback
        scope.launch { s.cards.delete(card.id); reloadCards(card.collectionId) }
    }

    val onCardStartDrag = useCallback { card: Card -> draggingCardId = card.id }

    val onCardEndDrag = useCallback {
        draggingCardId = null
        dropGroup = null
    }

    // Dropping a card on a tile puts it in that tile's group, right before it. Under a sort other than
    // MANUAL the order on screen is the sort's to decide, so there the position would be invisible:
    // the card just joins the group.
    val onDropOnTile = useCallback(
        store,
        cards,
        sortMode,
        selectedId,
        draggingCardId,
        draggingTab,
        draggingHistory,
        hiddenCollectionIds,
    ) { target: Card ->
        val collectionId = selectedId ?: return@useCallback
        val dragged = draggingCardId
        if (dragged == target.id) return@useCallback
        val index = if (dragged != null && sortMode == SortMode.MANUAL) {
            // The dragged card is spliced into an order it is no longer part of, so its own slot must
            // not count towards the target's index.
            cards.filter { it.cardSectionId == target.cardSectionId && it.id != dragged }
                .indexOfFirst { it.id == target.id }
                .coerceAtLeast(0)
        } else {
            Int.MAX_VALUE
        }
        dropOnGroup(collectionId, target.cardSectionId, index)
    }

    // A card renamed or deleted among the search results is a card of some collection: the results on
    // screen and the collection it lives in are both re-read, or one of the two would be stale.
    val onResultRename = useCallback(store, query, selectedId, hiddenCollectionIds) { card: Card, title: String ->
        val s = store ?: return@useCallback
        scope.launch {
            s.cards.rename(card.id, title)
            searchResults = s.cards.search(query.trim())
            reloadCards(selectedId)
        }
    }

    val onResultDelete = useCallback(store, query, selectedId, hiddenCollectionIds) { card: Card ->
        val s = store ?: return@useCallback
        scope.launch {
            s.cards.delete(card.id)
            searchResults = s.cards.search(query.trim())
            reloadCards(selectedId)
        }
    }

    // ---- The search box ----

    // Everything the box offers, ranked. The tabs, the collections and the top sites are ranked here,
    // out of state already in hand, so they are in the list on the keystroke itself; the cards and the
    // visited pages arrive from the debounced effect above and re-rank it a moment later.
    //
    // A locked section is left out of both halves, as it is everywhere else: the search must not be
    // the way around a PIN.
    val collectionTitles = useMemo(collections) { collections.associate { it.id.toString() to it.title } }
    val hitGroups = useMemo(
        query,
        openTabs,
        searchResults,
        searchHistory,
        collections,
        hiddenCollectionIds,
        aiAvailable,
        usageVersion,
        lang,
    ) {
        buildHits(
            query = query,
            tabs = openTabs,
            cards = searchResults.filter { it.collectionId !in hiddenCollectionIds },
            history = searchHistory,
            collections = collections.filter { it.id !in hiddenCollectionIds },
            collectionTitles = collectionTitles,
            aiAvailable = aiAvailable,
            strings = t,
        )
    }

    /** Done searching: the field empties and the collection comes back. */
    fun clearSearch() {
        query = ""
        showAllResults = false
    }

    /**
     * A row of the dropdown was taken. Each kind of row does the one thing it stands for — the tab is
     * switched to rather than opened again, the collection is selected rather than navigated to, the
     * question goes to the model rather than to Google — and every one of them that ends in a page
     * being opened counts towards the ranking that put it there.
     */
    fun activateHit(hit: Hit) {
        when (hit) {
            is TabHit -> {
                onTabGoTo(hit.tab)
                clearSearch()
            }
            is CardHit -> {
                onCardOpen(hit.card)
                clearSearch()
            }
            is HistoryHit -> {
                openPage(hit.entry.url, hit.entry.title)
                clearSearch()
            }
            // A top site's URL is the normalised one (no scheme) — it is a key, not a link.
            is SiteHit -> {
                openPage(asUrl(hit.stat.url), hit.stat.title)
                clearSearch()
            }
            is OpenUrlHit -> {
                openPage(hit.target, hostOf(hit.target))
                clearSearch()
            }
            // The user's own search engine, not one this app chose for them — and it answers *here*,
            // in the tab the question was typed in, exactly as the address bar would. Where the
            // browser will not say which engine that is (the web app), Google stands in.
            is WebSearchHit -> {
                val browserSearch = webSearch
                if (browserSearch != null) {
                    scope.launch { browserSearch.search(hit.query) }
                } else {
                    navigateTo(webSearchUrl(hit.query))
                }
                clearSearch()
            }
            is CollectionHit -> {
                selectedId = hit.collection.id
                pendingUnlockId = null
                unlockError = null
                clearSearch()
            }
            // The box becomes the question box; the answer takes over the content area. The field is
            // emptied for the follow-up.
            is AiHit -> {
                aiMode = true
                aiQuestion = hit.query
                query = ""
                showAllResults = false
            }
        }
    }

    fun exitAi() {
        aiMode = false
        aiQuestion = ""
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

    fun renameCollection(collection: Collection, title: String) {
        val s = store ?: return
        scope.launch {
            s.collections.rename(collection.id, title)
            collections = s.collections.all()
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

    // ---- Deleting, and the way back ----
    //
    // Deleting something that holds anything is asked about once, and can be taken back for half a
    // minute afterwards; deleting an empty one is neither. The deletion is real either way — the rows
    // leave the database — and the undo re-inserts exactly what left it, ids, order and file bytes
    // included, so what comes back is the same section and not a fresh one wearing its name.

    // The countdown on the offer to undo. Restarted by every new deletion (the effect's dependency
    // changes with it), and cancelled when the offer is taken or the page goes away.
    useEffect(undo) {
        if (undo == null) return@useEffect
        kotlinx.coroutines.delay(UNDO_MS.toLong())
        undo = null
    }

    fun undoDelete() {
        val u = undo ?: return
        undo = null
        scope.launch { u.restore() }
    }

    fun deleteSection(section: Section) {
        val s = store ?: return
        scope.launch {
            // What the warning counts is cards, not collections: a section is born with a collection
            // of its own name, so counting those would put a question in front of every deletion.
            val held = collections.filter { it.sectionId == section.id }
            val cardCount = held.sumOf { s.cards.count(it.id) }
            if (cardCount > 0 && !browserConfirm(t.confirmDeleteSection(section.title, cardCount))) return@launch

            val deleted = s.sections.delete(section.id) ?: return@launch
            sections = s.sections.all()
            val remaining = s.collections.all()
            collections = remaining
            // The open collection may have gone down with the section.
            if (remaining.none { it.id == selectedId }) selectedId = remaining.firstOrNull()?.id
            undo = Undo(t.deletedSection(section.title)) {
                s.sections.restore(deleted)
                sections = s.sections.all()
                collections = s.collections.all()
            }
        }
    }

    fun deleteCollection(collection: Collection) {
        val s = store ?: return
        scope.launch {
            val cardCount = s.cards.count(collection.id)
            if (cardCount > 0 && !browserConfirm(t.confirmDeleteCollection(collection.title, cardCount))) return@launch

            val deleted = s.collections.delete(collection.id) ?: return@launch
            val remaining = s.collections.all()
            collections = remaining
            if (selectedId == collection.id) selectedId = remaining.firstOrNull()?.id
            undo = Undo(t.deletedCollection(collection.title)) {
                s.collections.restore(deleted)
                collections = s.collections.all()
                selectedId = collection.id // put the user back where they were, looking at it
            }
        }
    }

    fun deleteCardSection(cs: CardSection, collectionId: Uuid) {
        val s = store ?: return
        val cardCount = cardsByGroup[cs.id]?.size ?: 0
        scope.launch {
            if (cardCount > 0 && !browserConfirm(t.confirmDeleteCardSection(cs.title, cardCount))) return@launch

            val deleted = s.cardSections.delete(cs.id) ?: return@launch
            reloadCards(collectionId)
            undo = Undo(t.deletedCardSection(cs.title)) {
                s.cardSections.restore(deleted)
                reloadCards(collectionId)
            }
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
                    hint(t.expandSidebar)
                    onClick = { leftCollapsed = false; prefSet("leftCollapsed", "0") }
                    +"»"
                }
                button {
                    className = ClassName("rail-toggle settings-rail")
                    hint(t.settings)
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
                        hint(t.collapseSidebar)
                        onClick = { leftCollapsed = true; prefSet("leftCollapsed", "1") }
                        +"«"
                    }
                }
                button {
                    className = ClassName("btn new-section")
                    hint(t.newSectionHint)
                    onClick = {
                        val name = browserPrompt(t.sectionNamePrompt, t.sectionNameDefault)
                        val s = store
                        if (s != null && !name.isNullOrBlank()) {
                            scope.launch {
                                val created = s.sections.create(name.trim())
                                sections = s.sections.all()
                                // The section comes with a collection of its own name (see
                                // `SectionRepository.create`); it is what the user is now looking at.
                                val cols = s.collections.all()
                                collections = cols
                                cols.firstOrNull { it.sectionId == created.id }?.let { selectedId = it.id }
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
                                hint(if (isLocked) t.lockedSection else t.renameHint)
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
                                    hint(if (section.locked) t.unlockedSection else t.protectSection)
                                    onClick = { e ->
                                        e.stopPropagation()
                                        if (section.locked) lockMenuId = section.id
                                        else pinDialog = PinDialog(section.id, change = false)
                                    }
                                    +(if (section.locked) "🔓" else "🔐")
                                }
                                button {
                                    className = ClassName("icon add")
                                    hint(t.addCollectionHint)
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
                                        hint(t.deleteSectionHint)
                                        onClick = { e ->
                                            e.stopPropagation()
                                            deleteSection(section)
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
                                        // A collection being renamed is not a collection to drag: the
                                        // pointer belongs to the text field standing in its title.
                                        draggable = renamingId != c.id
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
                                            // A read-only collection is not renamed either: the name
                                            // is as much part of it as the cards are.
                                            hint(if (takesContent) t.renameCollectionHint else t.readOnlyHint)
                                            onDoubleClick = { if (takesContent) onTitleDoubleClick(c.id) }
                                            if (renamingId == c.id && takesContent) {
                                                InlineEdit {
                                                    initial = c.title
                                                    onCommit = { name -> renameCollection(c, name) }
                                                    onCancel = { renamingId = null }
                                                }
                                            } else {
                                                +c.title
                                            }
                                        }
                                        if (c.readOnly) {
                                            span {
                                                className = ClassName("col-lock")
                                                hint(t.readOnlyHint)
                                                +"🔒"
                                            }
                                        }
                                        // Deleting a read-only collection is the one edit that cannot
                                        // be undone, so the button goes with the rest of them.
                                        if (takesContent) {
                                            button {
                                                className = ClassName("icon del")
                                                hint(t.deleteCollectionHint)
                                                onClick = { e ->
                                                    e.stopPropagation()
                                                    deleteCollection(c)
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
            val droppableCollection = targetCollection?.takeIf { !showAllResults && !aiMode }
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
                SearchBox {
                    strings = t
                    this.query = query
                    groups = hitGroups
                    this.aiMode = aiMode
                    // Searching is leaving the PIN screen a clicked-on section header put up; the
                    // section stays locked, its cards simply are not among the results.
                    onQueryChange = { value ->
                        query = value
                        pendingUnlockId = null
                        if (value.isBlank()) showAllResults = false
                    }
                    onActivate = ::activateHit
                    onShowAll = { if (query.isNotBlank()) showAllResults = true }
                    onExitAi = ::exitAi
                    onForget = { hit ->
                        forgetUse(hit.stat.url)
                        usageVersion += 1
                    }
                }
                div {
                    className = ClassName("toolbar")
                    if (hasRightSidebar && rightCollapsed) {
                        button {
                            className = ClassName("btn")
                            hint(t.showTabs)
                            onClick = { rightCollapsed = false; prefSet("rightCollapsed", "0") }
                            +t.tabsButton
                        }
                    }
                }
            }

            val aiAssistant = ai
            if (aiMode && aiAssistant != null) {
                // The answer stands where the collection was; the box above it is still the box, and
                // Escape (or "back to search") brings the collection back.
                val target = targetCollection
                AiPanel {
                    strings = t
                    assistant = aiAssistant
                    question = aiQuestion
                    // What the model is told before the first question: what it is, and — so that "what
                    // did I save about X" has an answer — what is in the collection the user is looking at.
                    systemPrompt = buildString {
                        append(t.aiSystemPrompt)
                        if (target != null && cards.isNotEmpty()) {
                            append("\n\n\"${target.title}\":\n")
                            cards.take(AI_CONTEXT_CARDS).forEach { card ->
                                append("- ${card.title}")
                                if (card.url.isNotBlank()) append(" — ${card.url}")
                                append("\n")
                            }
                        }
                    }
                    canSave = target != null
                    onSaveNote = { title, content ->
                        val s = store
                        if (s != null && target != null) {
                            scope.launch {
                                s.cards.addNote(target.id, title, content)
                                reloadCards(target.id)
                            }
                        }
                    }
                    onClose = ::exitAi
                }
            } else if (showAllResults && query.isNotBlank()) {
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
                            CardTile {
                                key = key(card.id)
                                strings = t
                                this.card = card
                                isDraggable = false
                                // A card found by a search is still a card of its collection: if that
                                // one is read-only, the result carries no rename or delete either.
                                readOnly = card.collectionId in readOnlyCollections
                                isDragging = false
                                acceptsDrop = false
                                onOpen = onCardOpen
                                // A result edited from here is edited in two places at once: the
                                // results on screen, and the collection the card actually lives in.
                                onRename = onResultRename
                                onDelete = onResultDelete
                                onStartDrag = onCardStartDrag
                                onEndDrag = onCardEndDrag
                                onDropHere = onDropOnTile
                            }
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
                                    hint(t.readOnlyHint)
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
                                hint(t.sortLinks)
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
                                    hint(t.addCardSectionHint)
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
                            // This window's tabs only: the sidebar lists every window, and each has its
                            // own ⤓, but this button is about the one in front.
                            if (editable && tabCapture != null) {
                                val wid = currentWindowId
                                val thisWindowTabs = openTabs.filter { wid == null || it.windowId == wid }
                                button {
                                    className = ClassName("btn")
                                    hint(t.saveTabsHint(thisWindowTabs.size, closeSavedTabs))
                                    onClick = { saveTabs(thisWindowTabs, current) }
                                    +t.saveOpenTabs
                                }
                            }
                            // "Add link" is the primary action; hovering reveals a menu to add a
                            // markdown note or upload a file instead.
                            if (editable) div {
                                className = ClassName("add-menu")
                                button {
                                    className = ClassName("btn add-card")
                                    hint(t.addCardHint)
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

                    val ungrouped = cardsByGroup[null] ?: emptyList()

                    run {
                        // Cards, tabs and history entries can all be dropped into a group; collections
                        // cannot, and a read-only collection takes none of the three.
                        val groupAccepts = editable &&
                            (draggingCardId != null || draggingTab != null || draggingHistory != null)

                        // The ungrouped area, always drawn and always named: it is where a card lands
                        // when it belongs to no section — including a brand new collection's first one
                        // — and dropping a card on it is how a card is taken back out of a section.
                        // Drawing it only once a section existed left a collection's cards sitting
                        // under no heading at all, in a group with no name to drop anything onto.
                        cardGroup(
                            accepts = groupAccepts,
                            active = dropGroup == DropGroup(null),
                            onOver = {
                                if (dropGroup != DropGroup(null)) dropGroup = DropGroup(null)
                                leaveTabsSidebar()
                            },
                            onDropHere = { dropOnGroup(current.id, null, Int.MAX_VALUE) },
                        ) {
                            div {
                                className = ClassName("card-section-head")
                                span {
                                    className = ClassName("card-section-title")
                                    +t.ungrouped
                                    span { className = ClassName("count"); +" ${ungrouped.size}" }
                                }
                            }
                            if (ungrouped.isNotEmpty()) {
                                cardGrid(
                                    strings = t,
                                    cards = ungrouped,
                                    draggingCardId = draggingCardId,
                                    readOnly = !editable,
                                    onOpen = onCardOpen,
                                    onRename = onCardRename,
                                    onDelete = onCardDelete,
                                    onStartDrag = onCardStartDrag,
                                    onEndDrag = onCardEndDrag,
                                    onDropOnTile = onDropOnTile,
                                )
                            } else {
                                // An empty collection says so; an empty area in a collection that has
                                // cards elsewhere only invites a card into it.
                                val hint = if (cards.isEmpty()) t.noLinksYet else t.dragLinksHere
                                div { className = ClassName("empty small"); +hint }
                            }
                        }

                        orderedCardSections.forEach { cs ->
                            val groupCards = cardsByGroup[cs.id] ?: emptyList()
                            cardGroup(
                                groupKey = key(cs.id),
                                accepts = groupAccepts,
                                active = dropGroup == DropGroup(cs.id),
                                onOver = {
                                    if (dropGroup != DropGroup(cs.id)) dropGroup = DropGroup(cs.id)
                                    leaveTabsSidebar()
                                },
                                onDropHere = { dropOnGroup(current.id, cs.id, Int.MAX_VALUE) },
                            ) {
                                div {
                                    className = ClassName("card-section-head")
                                    span {
                                        className = ClassName("card-section-title")
                                        // Collapsing is not editing — it stays. Renaming does not.
                                        hint(if (editable) t.renameHint else "")
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
                                            hint(t.editDescription)
                                            onClick = { e ->
                                                e.stopPropagation()
                                                descModal = DescModal(cs.id, cs.title, cs.description ?: "")
                                            }
                                            +"✎"
                                        }
                                        button {
                                            className = ClassName("icon del")
                                            hint(t.deleteCardSectionHint)
                                            onClick = { e ->
                                                e.stopPropagation()
                                                deleteCardSection(cs, current.id)
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
                                            onOpen = onCardOpen,
                                            onRename = onCardRename,
                                            onDelete = onCardDelete,
                                            onStartDrag = onCardStartDrag,
                                            onEndDrag = onCardEndDrag,
                                            onDropOnTile = onDropOnTile,
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
                        hint(t.showTabs)
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
                            hint(t.hideTabs)
                            onClick = { rightCollapsed = true; prefSet("rightCollapsed", "1") }
                            +"»"
                        }
                    }

                    if (pane == RightPane.TABS) {
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
                                    val windowTabs = tabsByWindow[windowId] ?: emptyList()
                                    // A window whose tabs the search filtered out drops out of the list.
                                    if (windowTabs.isEmpty()) return@forEachIndexed
                                    // Every tab of the window, not just the ones a search has left on
                                    // screen — as with the sort, the window is the window. Which is
                                    // also what the ⤓ says it will save, and then asks about.
                                    val allWindowTabs = openTabs.filter { it.windowId == windowId }
                                    tabWindow(
                                        strings = t,
                                        groupKey = windowId.toString().unsafeCast<Key>(),
                                        label = if (windowId == currentWindowId) t.thisWindow else t.windowLabel(i + 1),
                                        count = windowTabs.size,
                                        // Only a dragged tab can be dropped here; cards, collections
                                        // and visited pages have no place in the browser's tab strip.
                                        accepts = draggingTab != null,
                                        active = dropTabWindowId == windowId && dropTabId == null,
                                        // Nowhere to save them to (no collection selected, a read-only
                                        // one, one behind a PIN): no ⤓ on the window either.
                                        saveHint = targetCollection?.let {
                                            t.saveTabsHint(allWindowTabs.size, closeSavedTabs)
                                        },
                                        onOver = { hoverTabs(windowId, null) },
                                        // Dropped on the window but on none of its tabs: append (-1).
                                        onDropHere = { moveTabTo(windowId, -1) },
                                        onSave = {
                                            targetCollection?.let { target -> saveTabs(allWindowTabs, target) }
                                        },
                                        onSort = { by -> sortTabs(windowId, by) },
                                    ) {
                                        ul {
                                            className = ClassName("tab-list")
                                            windowTabs.forEach { tab ->
                                                TabRow {
                                                    key = tab.id.toString().unsafeCast<Key>()
                                                    strings = t
                                                    this.tab = tab
                                                    isDragging = draggingTab?.id == tab.id
                                                    acceptsDrop = draggingTab != null && draggingTab?.id != tab.id
                                                    isDropTarget = dropTabId == tab.id
                                                    onGoTo = onTabGoTo
                                                    onClose = onTabClose
                                                    onStartDrag = onTabStartDrag
                                                    onEndDrag = onTabEndDrag
                                                    onOver = onTabOver
                                                    onDropHere = onTabDropHere
                                                }
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
                                onOpen = { entry -> openPage(entry.url, entry.title) },
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
                // `this.` — plain `cards` would find this component's own card list, not the prop.
                this.cards = store?.cards
                onClose = { fileModal = null }
                onSave = { name, mimeType, data ->
                    val s = store
                    if (s != null) scope.launch {
                        // The card keeps a small preview; the bytes themselves go to the blob store
                        // and are not read again until the file is opened.
                        s.cards.addFile(m.collectionId, name, data, mimeType, makeThumb(data, mimeType), m.cardSectionId)
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
                // Only where there are tabs at all: the web app has no ⤓ for this to settle.
                hasTabs = tabCapture != null
                this.closeSavedTabs = closeSavedTabs
                onCloseSavedTabsChange = { close ->
                    closeSavedTabs = close
                    prefSet("closeSavedTabs", if (close) "1" else "0")
                }
                // Which model is answering, if any — and if none, why not.
                aiName = ai?.name
                this.aiState = aiState
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

        // Every tooltip in the app, drawn here rather than inside the controls they belong to: the
        // panels all scroll, and a scroll box clips what leaves it. See [HintLayer].
        HintLayer()

        // What was just deleted, and the way back from it — for [UNDO_MS], then it fades of its own
        // accord. It stands over the page rather than in it: a deletion is undone from wherever the
        // user now is, and nothing they do in the meantime takes the offer away.
        undo?.let { u ->
            div {
                className = ClassName("undo-toast")
                span { className = ClassName("undo-msg"); +u.message }
                button {
                    className = ClassName("btn undo-btn")
                    onClick = { undoDelete() }
                    +t.undo
                }
                button {
                    className = ClassName("icon del undo-dismiss")
                    hint(t.close)
                    onClick = { undo = null }
                    +"×"
                }
            }
        }
    }
}
