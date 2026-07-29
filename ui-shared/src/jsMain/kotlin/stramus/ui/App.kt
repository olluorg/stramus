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
import react.dom.html.ReactHTML.img
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
import stramus.core.ai.TriageAssignment
import stramus.core.db.StramusStore
import stramus.core.db.openStramusStore
import stramus.core.platform.GoogleSignIn
import stramus.core.sync.StramusApi
import stramus.core.sync.SyncEngine
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
import stramus.core.url.hostOf
import web.cssom.ClassName
import web.data.DropEffect
import web.data.copy
import web.data.move
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

private val scope = MainScope()

/**
 * How often the app checks in with the server.
 *
 * A minute: often enough that a card saved on the laptop is on the phone by the time the user has picked
 * it up, and quiet enough that a browser left open all day costs the server 1440 requests, most of which
 * answer "nothing new" in a few hundred bytes. A push channel would be tidier; it would also be a socket
 * to keep alive, and it is not what stands between this and being useful.
 */
private const val SYNC_INTERVAL_MS = 60_000

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

/**
 * How long a card must hover a sidebar collection before it opens on its own — long enough that a
 * drag merely passing over it on the way somewhere else does not fling it open, short enough that
 * dropping into a collapsed collection does not mean stopping there first to open it by hand.
 */
private const val HOVER_OPEN_MS = 600

internal fun key(id: Uuid): Key = id.toString().unsafeCast<Key>()

/**
 * A deletion the user can still take back: what to tell them, and what to run if they do. Every
 * deletion gets one — a card, a card section, a collection or a section — offered on the toast and,
 * for the most recent one, on Ctrl/Cmd+Z as well.
 */
private data class Undo(val message: String, val restore: suspend () -> Unit)

/**
 * The collection the page opens on: the one the user last had open, where they asked for that
 * ([StartView.LAST], the default) and it is still there; the first one in the sidebar otherwise.
 *
 * A collection behind a PIN is never it. Every reload locks its section back up, so opening there
 * would be to open on the PIN screen — every single time — and the choice of which collection was
 * last looked at would itself be a thing the lock was meant not to say. Only when *every* collection
 * is locked away is one of those opened, since then there is nothing else to show.
 */
private fun startCollection(collections: List<Collection>, sections: List<Section>, view: StartView): Uuid? {
    val locked = sections.filter { it.locked }.map { it.id }.toSet()
    val open = collections.filter { it.sectionId !in locked }
    val remembered = if (view == StartView.LAST) prefGet(LAST_COLLECTION_PREF) else null
    return open.firstOrNull { it.id.toString() == remembered }?.id
        ?: open.firstOrNull()?.id
        ?: collections.firstOrNull()?.id
}

/** Which modal is open. [existing] non-null = editing/viewing that card; null = creating a new one. */
private data class NoteModal(val collectionId: Uuid, val cardSectionId: Uuid?, val existing: Card?)
private data class FileModal(val collectionId: Uuid, val cardSectionId: Uuid?, val existing: Card?)
/**
 * Renaming a card. [fromSearch] = the tile was one of the search results, and the rename has to be
 * written back to two places at once: the results on screen, and the collection the card lives in.
 */
private data class RenameModal(val card: Card, val fromSearch: Boolean)

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
 *
 * [acceptsFiles] is the other kind of drag entirely: files from the desktop, which are copied in
 * ([onDropFiles]) rather than moved from somewhere else in the app. Which of the two a drag is can
 * only be told from the event ([draggingFiles]) — the app knows nothing of a file until it lands —
 * so both are wired and each event settles it for itself.
 */
private fun ChildrenBuilder.cardGroup(
    accepts: Boolean,
    acceptsFiles: Boolean,
    active: Boolean,
    onOver: () -> Unit,
    onDropHere: () -> Unit,
    onDropFiles: (List<PickedFile>) -> Unit,
    groupKey: Key? = null,
    /** This group is the one being carried somewhere else — it fades where it stands. */
    dragging: Boolean = false,
    content: ChildrenBuilder.() -> Unit,
) {
    div {
        key = groupKey
        className = ClassName(
            buildString {
                append("card-group")
                if (active) append(" drop-active")
                if (dragging) append(" dragging")
            },
        )
        if (accepts || acceptsFiles) {
            onDragOver = { e ->
                val files = acceptsFiles && draggingFiles(e.dataTransfer)
                if (accepts || files) {
                    e.preventDefault()
                    e.stopPropagation()
                    e.dataTransfer.dropEffect = if (files) DropEffect.copy else DropEffect.move
                    onOver()
                }
            }
            onDrop = { e ->
                val files = acceptsFiles && draggingFiles(e.dataTransfer)
                if (accepts || files) {
                    e.preventDefault()
                    e.stopPropagation()
                    if (files) onDropFiles(droppedFiles(e.dataTransfer)) else onDropHere()
                }
            }
        }
        content()
    }
}

/**
 * The "add a card" menu, one per card group: a link on click, a note or a file from the menu the
 * button reveals. It lives in the group's header rather than in the collection's toolbar, so what it
 * adds lands in the group it was clicked in — no dragging the new card into place afterwards.
 *
 * Every click is stopped here: the header is the handle its section is dragged by, and its title
 * collapses it. Neither should hear a click meant for this menu, and neither should turn a press on
 * the button into a drag ([draggable] = false takes the subtree out of the header's handle).
 */
private fun ChildrenBuilder.addMenu(
    strings: Strings,
    onLink: () -> Unit,
    onNote: () -> Unit,
    onFile: () -> Unit,
) {
    // A bare "+", like the ✎ and × beside it: the header is a strip, not a toolbar, and it carries one
    // of these per section. What the click does is left to the tooltip.
    hoverMenu(glyph = "+", tooltip = strings.addCardHint, onGlyphClick = onLink) {
        menuItem(strings.addLinkItem, onLink)
        menuItem(strings.addNoteItem, onNote)
        menuItem(strings.addFileItem, onFile)
    }
}

/**
 * A glyph in a card-section header that reveals a list of actions under it — the "+" that adds a card,
 * the ⇅ that sorts the section. [onGlyphClick] is the one action the glyph itself performs, for the
 * menus that have an obvious default (the "+" adds a link); a menu whose actions are all equals leaves
 * it out, and then the glyph only opens the list.
 *
 * Built out of buttons rather than a `select`, which cannot be styled into anything like the rest of
 * the app once its list is open. That leaves the two things a `select` gave for free to be done here:
 * every click is stopped, because the header is the handle its section is dragged by and its title
 * collapses it, and neither should hear a click meant for this menu; and [draggable] = false takes the
 * whole subtree out of that handle, so a press on an item cannot start a drag of the section.
 */
private fun ChildrenBuilder.hoverMenu(
    glyph: String,
    tooltip: String,
    onGlyphClick: (() -> Unit)? = null,
    items: ChildrenBuilder.() -> Unit,
) {
    div {
        className = ClassName("menu")
        draggable = false
        onClick = { e -> e.stopPropagation() }
        button {
            className = ClassName("icon menu-btn")
            hint(tooltip)
            onClick = { e -> e.stopPropagation(); onGlyphClick?.invoke() }
            +glyph
        }
        div {
            className = ClassName("menu-dropdown")
            items()
        }
    }
}

/** One action in a [hoverMenu]. */
private fun ChildrenBuilder.menuItem(label: String, onSelect: () -> Unit) {
    button {
        className = ClassName("menu-item")
        onClick = { e -> e.stopPropagation(); onSelect() }
        +label
    }
}

/** A heading over a run of [menuItem]s, saying what the actions under it have in common. */
private fun ChildrenBuilder.menuTitle(label: String) {
    div {
        className = ClassName("menu-title")
        +label
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
    showUrls: Boolean,
    onOpen: (Card) -> Unit,
    onRename: (Card) -> Unit,
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
                this.showUrl = showUrls
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
 * One window's open tabs, drawn as a grid of [TabCard]s instead of [TabRow]'s `<ul>` — what the tabs
 * sidebar shows in place of the list under the `tabsCardView` setting. Same drag/drop and "current tab"
 * semantics as the list; only the layout differs. See [cardGrid], which this mirrors for saved cards.
 */
private fun ChildrenBuilder.tabCardGrid(
    strings: Strings,
    tabs: List<CapturedTab>,
    showUrls: Boolean,
    draggingTabId: Int?,
    dropTabId: Int?,
    onGoTo: (CapturedTab) -> Unit,
    onClose: (CapturedTab) -> Unit,
    onStartDrag: (CapturedTab) -> Unit,
    onEndDrag: () -> Unit,
    onOver: (CapturedTab) -> Unit,
    onDropHere: (CapturedTab) -> Unit,
) {
    div {
        className = ClassName("grid")
        tabs.forEach { tab ->
            TabCard {
                key = tab.id.toString().unsafeCast<Key>()
                this.strings = strings
                this.tab = tab
                this.showUrl = showUrls
                isDragging = draggingTabId == tab.id
                acceptsDrop = draggingTabId != null && draggingTabId != tab.id
                isDropTarget = dropTabId == tab.id
                this.onGoTo = onGoTo
                this.onClose = onClose
                this.onStartDrag = onStartDrag
                this.onEndDrag = onEndDrag
                this.onOver = onOver
                this.onDropHere = onDropHere
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
    triageHint: String?,
    onOver: () -> Unit,
    onDropHere: () -> Unit,
    onSave: () -> Unit,
    onTriage: () -> Unit,
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
                // Beside the ⤓ rather than in place of it: this window's tabs are saved into the open
                // collection by one and read by the model into several by the other, and which of those
                // the user wants is not something to decide for them.
                if (triageHint != null) {
                    button {
                        className = ClassName("tab-triage")
                        hint(triageHint)
                        onClick = { onTriage() }
                        +"✨"
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
            // The row shows the title cut to the panel's width; the tooltip is the whole of it. A
            // tab that has no title at all has only its URL to be known by.
            hint(tab.title.ifBlank { tab.url })
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
    /**
     * How this host reaches Google, if it can: `chrome.identity` in the extension, a popup in the web app.
     * Null when no Google client id has been configured, and then that door is simply not offered.
     */
    var google: GoogleSignIn?

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

    /**
     * Where site icons are read from. The extension passes the browser's own favicon store, which is
     * on this machine; null in the web app, which has nothing to read but the public icon services.
     */
    var iconSources: IconSources?
}

val App = FC<AppProps> { props ->
    val tabCapture = props.tabCapture
    val historyAccess = props.historyAccess
    val ai = props.ai
    val webSearch = props.webSearch

    // Before the first icon is drawn, and idempotent: whoever renders the app decides, once, where its
    // icons may be read from. Left alone, they come from the icon services (see [NetworkIcons]).
    props.iconSources?.let { installIconSources(it) }

    var store by useState<StramusStore?>(null)

    // The server, and this database's side of the conversation with it. Made once, and made whether or
    // not anyone is signed in: the badge has to be able to say "not signed in", and the account dialog
    // has to have something to sign in *with*.
    val api = useMemo { StramusApi(serverBaseUrl()) }
    var engine by useState<SyncEngine?>(null)
    var syncUi by useState(SyncUi())
    var accountOpen by useState(false)

    // Off unless the user says otherwise. The collections are things they chose to keep; this is a record
    // of where they have been, and that is not the same thing to put on a server.
    var syncUsage by useState(prefGet("syncUsage") == "1")
    val syncUsageRef = useRef(syncUsage)
    syncUsageRef.current = syncUsage

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
    // The conversation with the model, in a window over the page. [aiQuestion] is what the search box
    // was carrying when it opened — the first question; the rest are asked in the window itself.
    var aiOpen by useState(false)
    var aiQuestion by useState("")
    // What the browser's model can do for us — shown in the settings, and what decides whether the box
    // offers to ask it at all. Null until the question has been put to the browser (or there is no
    // model to put it to).
    var aiState by useState<AiAvailability?>(null)
    // The window whose tabs the model is sorting, if the ✨ has been pressed: everything the triage
    // window needs is derived from it, so closing it is forgetting one number.
    var triageWindowId by useState<Int?>(null)
    var draggingCardId by useState<Uuid?>(null)
    var draggingCollectionId by useState<Uuid?>(null)
    // The section being dragged by its header, to be dropped on another section and take its place.
    var draggingSectionId by useState<Uuid?>(null)
    // The same, for a card section (a divider) being dragged up or down the open collection's grid.
    var draggingCardSectionId by useState<Uuid?>(null)
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
    var renameModal by useState<RenameModal?>(null)
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
    // What the last import did, said in the settings page under the button that started it. Cleared
    // when the page is closed: it is the answer to something the user did there, not a standing notice.
    var importStatus by useState<String?>(null)
    // The section, collection or card section — their ids share one space — being renamed in place.
    var renamingId by useState<Uuid?>(null)
    // The last deletion, for as long as it can still be taken back. See [UNDO_MS].
    var undo by useState<Undo?>(null)
    // The pending collapse of a section whose title was just clicked once. See [onTitleClick].
    val clickTimer = useRef<Int>(null)
    // The pending auto-open of a sidebar collection a dragged card is hovering. See [scheduleHoverOpen].
    val hoverOpenTimer = useRef<Int>(null)

    // Persisted UI preferences (localStorage): theme, language, and sidebar collapse state. Card order
    // is not among them — a sort writes the cards' own order (see [CardSort]), it does not remember one.
    var theme by useState(prefGet("theme") ?: "auto")
    var lang by useState(Lang.from(prefGet("lang")))
    // A card is its title, not its address: the URL under it says the same thing twice for most links
    // and pushes the ones it does not to a second line of noise. Hidden unless asked for.
    var showCardUrls by useState(prefGet("showCardUrls") == "1")
    var leftCollapsed by useState(prefGet("leftCollapsed") == "1")
    var rightCollapsed by useState(prefGet("rightCollapsed") == "1")
    // Whether the sections sidebar and the tabs/history sidebar have traded sides. Purely a layout
    // flip — everything each one shows and does stays exactly where it is otherwise.
    var swapSidebars by useState(prefGet("swapSidebars") == "1")
    // Whether the tabs sidebar shows its rows as a grid of [TabCard]s — the same shape as the middle
    // pane's saved cards, and the same width as that pane too — instead of [TabRow]'s list.
    var tabsCardView by useState(prefGet("tabsCardView") == "1")
    var rightPane by useState(RightPane.from(prefGet("rightPane")))
    var autoLockMinutes by useState(prefGet("autoLock")?.toIntOrNull() ?: DEFAULT_AUTO_LOCK_MINUTES)
    // What the page opens on: where the user left off, or the first collection. Read once, on the
    // render that also loads the store — changing it later is for the *next* open, not for this one.
    var startView by useState(StartView.from(prefGet("startView")))
    // Saving a window's tabs into a collection closes them, as dragging one there does: the tab has
    // been put away, and leaving it open would be to have it in two places. Unset means the default.
    var closeSavedTabs by useState(prefGet("closeSavedTabs") != "0")
    // The ✨ that sorts a window with the built-in model: off until asked for, and `!= "1"` rather than
    // `== "0"` above precisely because it is off by default — an install that has never heard of it has
    // no preference stored, and no preference means no.
    var aiTriage by useState(prefGet(AI_TRIAGE_PREF) == "1")
    // Who the user asked to be answered by: the browser's own model, in a window over the page, or one
    // of the web chats — which cannot answer here, so the question opens there instead. What actually
    // answers is [aiProvider] below: a browser with no model to run cannot honour a choice of the local
    // one, and the question has to go somewhere.
    var aiChoice by useState(AiProvider.from(prefGet(AI_PROVIDER_PREF)))

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

    // The selected collection's cards, split into the groups the page draws — done once per change to
    // the cards, rather than once per group per render. Each group keeps the order it is stored in,
    // which is the order the user put it in, by dragging or by sorting. The ungrouped ones are under
    // the null key.
    val cardsByGroup = useMemo(cards) { cards.groupBy { it.cardSectionId } }
    val orderedCardSections = useMemo(cardSections) { cardSections.sortedBy { it.orderKey } }

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
            // The other half of the ranking: not which pages they open, but what they come to the box
            // to do — see [HitAction].
            initActionIndex(s.actions)
            usageVersion += 1
            val secs = s.sections.all()
            val cols = s.collections.all()
            store = s
            // The engine asks the ref, not the value it was built with: the user can turn the statistics off
            // between two runs, and when they do it has to stop at the next one, not at the next reload.
            engine = SyncEngine(s.db, api, api) { syncUsageRef.current == true }
            sections = secs
            collections = cols
            selectedId = startCollection(cols, secs, startView)

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

    // Where the user is, kept for the next open — whether or not they have asked to come back to it:
    // the setting decides what the *next* start does with this, so turning it on is not a thing that
    // has to be turned on first to work. Only a collection that is really on screen is written down,
    // so a locked-away or deleted one does not take the place of the last real one.
    useEffect(selectedId, hiddenCollectionIds) {
        val sel = selectedId ?: return@useEffect
        if (sel in hiddenCollectionIds) return@useEffect
        prefSet(LAST_COLLECTION_PREF, sel.toString())
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

    // Whether the browser's own model can answer here: no, where the host gives the page no model at
    // all; not yet known, while the browser is still being asked (the answer is a moment away, and a
    // model wrongly written off in that moment would be a setting silently overridden); otherwise
    // whatever it said. A model that has to be downloaded first still counts — the first question
    // starts the download, and the window shows it happening rather than pretending to think.
    val aiLocalAvailable: Boolean? = when {
        ai == null -> false
        aiState == null -> null
        else -> aiState != AiAvailability.UNAVAILABLE
    }

    // Who actually answers. A browser that cannot run the local model is most browsers, and the user
    // who never had it cannot have chosen anything else — so rather than offer nothing, the question
    // goes to a web chat, and the settings show the local one struck out with the reason next to it.
    val aiProvider = if (aiChoice == AiProvider.LOCAL && aiLocalAvailable == false) {
        AiProvider.WEB_DEFAULT
    } else {
        aiChoice
    }

    // Whether there is anything to offer the question to at all: a web chat always is (it is a page,
    // and this machine has nothing to do with it), the local model only once it has said that it is.
    val aiAvailable = aiProvider != AiProvider.LOCAL || aiLocalAvailable == true

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

    /** Redraw everything a sync may have changed — which is anything at all, since it came from elsewhere. */
    fun reloadAfterSync() {
        val s = store ?: return
        scope.launch {
            sections = s.sections.all()
            collections = s.collections.all()
            val sel = selectedId
            if (sel != null && sel !in hiddenCollectionIds) {
                cards = s.cards.byCollection(sel)
                cardSections = s.cardSections.byCollection(sel)
            }
        }
    }

    /**
     * One run of the sync engine, in the background, on nobody's critical path.
     *
     * A failure is not an error the user has to deal with: their work is on this machine either way, and
     * the next run will take it up. So it goes to the badge as "waiting for the network" and nowhere else
     * — no dialog, no toast, nothing that interrupts.
     */
    fun runSync() {
        val e = engine ?: return
        if (!api.hasSession()) return
        scope.launch {
            syncUi = syncUi.copy(status = SyncStatus.RUNNING)
            runCatching { e.syncNow() }
                .onSuccess { result ->
                    syncUi = syncUi.copy(
                        status = SyncStatus.IDLE,
                        syncedAt = nowLocalTime(),
                        error = null,
                        conflictCopies = result?.conflictCopies ?: 0,
                    )
                    // Only redraw when something actually arrived: a quiet sync every minute must not
                    // rebuild the grid under the user's hands.
                    if ((result?.applied ?: 0) > 0 || (result?.conflictCopies ?: 0) > 0) reloadAfterSync()
                }
                .onFailure { syncUi = syncUi.copy(status = SyncStatus.OFFLINE, error = it.message) }
        }
    }

    // Picking a session back up, and then keeping it in step. A minute is often enough for two of the
    // user's own browsers and quiet enough to be free; the run on regaining focus is what makes the app
    // feel like it knew all along.
    useEffect(engine) {
        val e = engine ?: return@useEffect

        val me = runCatching { api.resume() }.getOrNull()
        if (me != null && e.signedIn()) {
            syncUi = SyncUi(SyncStatus.IDLE, me.email)
            runSync()
        } else if (e.signedIn()) {
            // The database belongs to an account, but this browser holds no session for it any more — the
            // token expired, or the device was cut off. Nothing is lost; the user signs in again, and the
            // rows that were waiting to go up are still waiting.
            syncUi = SyncUi(SyncStatus.SIGNED_OUT)
        }

        val ticking = repeatEvery(SYNC_INTERVAL_MS) { runSync() }
        val stopWatchingFocus = onWindowFocus { runSync() }
        try {
            awaitCancellation()
        } finally {
            cancelRepeat(ticking)
            stopWatchingFocus()
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
            navigateTo(url)
        }
    }

    /**
     * Open every link card of a group at once, each as its own tab — a note or a file has no page to
     * open, so those are skipped. This is what "restore the session" means for a group that was saved
     * from one: [openPage] replaces the current tab, which is right for a single link but would just
     * leave the last card up here, so a real new tab is opened per card instead — in the background
     * when the extension can do that, or foreground (the browser's own call) when it can't.
     */
    fun openAllCards(cardsInGroup: List<Card>) {
        val links = cardsInGroup.filter { it.kind == CardKind.LINK }
        if (links.isEmpty()) return
        links.forEach { recordUse(it.url, it.title) }
        usageVersion += 1

        val tc = tabCapture
        if (tc != null) {
            scope.launch { links.forEach { tc.createTab(it.url) } }
        } else {
            links.forEach { openUrl(it.url) }
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

    /**
     * Carry out a plan the user has read: what [TabTriageModal] proposed, corrected as they saw fit.
     *
     * There is no confirmation here, unlike [saveTabs] — the preview *was* the question, and asking
     * again after the user has gone through the plan row by row would only teach them to click past it.
     * The tabs are closed on the same standing setting (`closeSavedTabs`) that the ⤓ obeys: what the
     * plan changes is where a tab lands, not what saving one means.
     *
     * A collection the plan invents is created in the sidebar section the plan names for it — what
     * the preview drew it under, and what the user could overrule there. [sectionId] is the fallback
     * for when the plan names none. A card section the plan invents is created inside its collection — the model names them, it cannot make them, and the
     * names have already survived `cleanName` and the user's eye. Each is created once however many
     * tabs were sent to it: `madeCollections` is what keeps the second tab of a new "Kotlin" out of a
     * second collection called "Kotlin", and `madeSections` does the same for the dividers. A section
     * is keyed by its collection as well as its name, because a section belongs to its collection —
     * two collections may both have a "Docs", and they are not the same divider.
     */
    fun applyTriage(plan: List<TriageAssignment>, sectionId: Uuid) {
        val s = store ?: return
        val tc = tabCapture ?: return
        if (plan.isEmpty()) return
        val byId = openTabs.associateBy { it.id }
        scope.launch {
            val madeCollections = mutableMapOf<String, Uuid>()
            val madeSections = mutableMapOf<Pair<Uuid, String>, Uuid>()
            plan.forEach { assignment ->
                // A tab the user closed in the browser while reading the plan is simply not saved: the
                // plan is a proposal about tabs, and this one is not there any more.
                val tab = byId[assignment.tabId] ?: return@forEach
                val collectionId = assignment.collectionId
                    ?: madeCollections[assignment.collectionTitle]
                    // Created in the section the plan says, which is the one the user saw it drawn
                    // under and could change. [sectionId] is only the fallback now — it used to be
                    // the rule, and that is how a new "Электроника" ended up under "Работа" merely
                    // because a work collection happened to be open.
                    ?: s.collections.create(
                        assignment.collectionTitle,
                        sections.firstOrNull { it.title == assignment.groupTitle }?.id ?: sectionId,
                    ).id.also { madeCollections[assignment.collectionTitle] = it }
                val cardSectionId = assignment.sectionId
                    ?: assignment.sectionTitle?.let { title ->
                        madeSections.getOrPut(collectionId to title) {
                            s.cardSections.create(collectionId, title, null).id
                        }
                    }
                s.cards.add(
                    collectionId,
                    tab.title.ifBlank { hostOf(tab.url) },
                    tab.url,
                    tab.favicon ?: faviconFor(tab.url),
                    cardSectionId,
                )
            }
            if (closeSavedTabs) {
                // Closed by URL, not by the ids in the plan: the plan holds one row per *page*, the
                // duplicates having been collapsed into it (see `preGroup`), and the second tab of a
                // page that has just been saved is as saved as the first. Closing only the plan's own
                // ids would leave it open — the one thing "keep the first tab" must not get wrong.
                val saved = plan.mapNotNull { byId[it.tabId]?.url }.toSet()
                openTabs.filter { it.url in saved }.forEach { tc.closeTab(it.id) }
            }
            if (madeCollections.isNotEmpty()) collections = s.collections.all()
            // Only the open collection is redrawn — the plan will have filled several, and the others
            // are read when the user goes to them.
            selectedId?.let { reloadCards(it) }
            openTabs = tc.currentTabs()
            triageWindowId = null
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

    /**
     * Files dragged in from the desktop, saved as file cards in [collectionId] / [cardSectionId]
     * (null = ungrouped) — what a dragged tab is to a page, this is to a file, and it is the same
     * card the ＋ menu's "File" makes.
     *
     * They are read one at a time and in the order they were dropped, so that is the order the cards
     * land in — and so that ten files are ten files being read, not ten whole files held in memory at
     * once. One too big for the database is named to the user and passed over ([MAX_FILE_MB]); one
     * that cannot be read at all is passed over silently, which is what a dropped *folder* is: the
     * browser offers it as a file and then declines to open it.
     */
    fun saveFiles(files: List<PickedFile>, collectionId: Uuid, cardSectionId: Uuid? = null) {
        val s = store ?: return
        dropGroup = null
        contentDropActive = false
        dropCollectionId = null
        if (files.isEmpty()) return

        val (oversized, saveable) = files.partition { it.tooLarge }
        if (oversized.isNotEmpty()) browserAlert(t.filesTooLarge(oversized.map { it.name }, MAX_FILE_MB))
        if (saveable.isEmpty()) return
        scope.launch {
            saveable.forEach { file ->
                val data = file.read() ?: return@forEach
                // The card keeps a small preview; the bytes go to the blob store, as in the file modal.
                s.cards.addFile(collectionId, file.name, data, file.mime, makeThumb(data, file.mime), cardSectionId)
            }
            reloadCards(collectionId)
        }
    }

    // Every card drop lands here: the card joins [collectionId] / [cardSectionId] at [index] within
    // that group (Int.MAX_VALUE = append). Collection, section and order always move together.
    //
    // A move into another collection is offered back like a deletion: [card]'s old collection, group
    // and place in it are read before the move runs, and put back verbatim if the user asks for them.
    // A reorder within the same collection gets no such offer — a drag inside one grid is too frequent
    // an action for a toast to follow every time, unlike the occasional cross-collection transfer.
    fun moveCard(cardId: Uuid, collectionId: Uuid, cardSectionId: Uuid?, index: Int) {
        val s = store ?: return
        val card = cards.firstOrNull { it.id == cardId }
        val fromCollectionId = card?.collectionId
        val fromCardSectionId = card?.cardSectionId
        val fromIndex = card?.let { cards.filter { c -> c.cardSectionId == fromCardSectionId }.indexOfFirst { c -> c.id == cardId } }
        scope.launch {
            s.cards.move(cardId, collectionId, cardSectionId, index)
            reloadCards(selectedId)
            if (card != null && fromCollectionId != null && fromCollectionId != collectionId) {
                undo = Undo(t.movedCard(card.title)) {
                    s.cards.move(cardId, fromCollectionId, fromCardSectionId, fromIndex ?: Int.MAX_VALUE)
                    reloadCards(fromCollectionId)
                }
            }
        }
        draggingCardId = null
        dropGroup = null
    }

    // A card dragged onto a collapsed sidebar collection opens it after [HOVER_OPEN_MS] of hovering,
    // the way a desktop file manager opens a folder under a drag — so the card can be dropped into the
    // collection's own grid rather than always landing ungrouped at the end. Only one hover is ever
    // pending: entering a new row (or leaving the one that was timed) cancels whatever came before.
    fun cancelHoverOpen() {
        hoverOpenTimer.current?.let { cancelDelay(it) }
        hoverOpenTimer.current = null
    }

    fun scheduleHoverOpen(id: Uuid) {
        cancelHoverOpen()
        hoverOpenTimer.current = delay(HOVER_OPEN_MS) {
            hoverOpenTimer.current = null
            selectedId = id
        }
    }

    // Put one card section's cards in order — where a drag moves one card, this lays out the whole
    // group at once, and it writes the same order a drag writes rather than a way of looking at it.
    // Which is why it can be taken back: a sort overwrites an arrangement the user may have built by
    // hand over a long time, and the cards' old order — the one thing that is lost — is right here to
    // put back. Cards hidden by a search are sorted with the rest: the group is the user's, and half of
    // it quietly reshuffled to fit a query would be no kind of sort at all.
    fun sortCards(collectionId: Uuid, cardSectionId: Uuid?, by: CardSort) {
        val s = store ?: return
        val group = cards.filter { it.cardSectionId == cardSectionId }
        val before = group.map { it.id }
        val after = by.apply(group).map { it.id }
        if (after == before) return
        scope.launch {
            s.cards.reorder(collectionId, cardSectionId, after)
            reloadCards(collectionId)
            undo = Undo(t.sortedCards) {
                s.cards.reorder(collectionId, cardSectionId, before)
                reloadCards(collectionId)
            }
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

    // The dragged section is dropped on [targetId] and takes its place. Dragging one down the sidebar
    // puts it *after* the section it was dropped on, dragging one up puts it *before* — otherwise the
    // last place in the sidebar would be the one place a section could never be dropped into.
    fun moveSection(targetId: Uuid) {
        val dragged = draggingSectionId
        val s = store
        if (dragged != null && dragged != targetId && s != null) {
            val order = sections.map { it.id }
            val movingDown = order.indexOf(dragged) < order.indexOf(targetId)
            val index = order.filter { it != dragged }.indexOf(targetId)
            if (index >= 0) {
                scope.launch {
                    s.sections.move(dragged, if (movingDown) index + 1 else index)
                    sections = s.sections.all()
                }
            }
        }
        draggingSectionId = null
        dropSectionId = null
    }

    // The same for a card section, dropped on another one of the open collection: it takes that
    // section's place, after it when dragged down the grid and before it when dragged up. The
    // ungrouped area is not part of this — it is not a section, and it is always the first thing.
    fun moveCardSection(targetId: Uuid) {
        val dragged = draggingCardSectionId
        val s = store
        val collectionId = cardSections.firstOrNull { it.id == dragged }?.collectionId
        if (dragged != null && dragged != targetId && s != null && collectionId != null) {
            val order = orderedCardSections.map { it.id }
            val movingDown = order.indexOf(dragged) < order.indexOf(targetId)
            val index = order.filter { it != dragged }.indexOf(targetId)
            if (index >= 0) {
                scope.launch {
                    s.cardSections.move(dragged, if (movingDown) index + 1 else index)
                    cardSections = s.cardSections.byCollection(collectionId)
                }
            }
        }
        draggingCardSectionId = null
        dropGroup = null
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

    // The ✎ on a tile asks for the box, it does not do the renaming: the box is where the title is
    // edited, and — for a link, on a machine whose browser has a model of its own — where the same title
    // with the rubbish taken out of it is offered. See [RenameCardModal].
    val onCardRenameRequest = useCallback { card: Card -> renameModal = RenameModal(card, fromSearch = false) }
    val onResultRenameRequest = useCallback { card: Card -> renameModal = RenameModal(card, fromSearch = true) }

    val onCardRename = useCallback(store, hiddenCollectionIds) { card: Card, title: String ->
        val s = store ?: return@useCallback
        scope.launch { s.cards.rename(card.id, title); reloadCards(card.collectionId) }
    }

    val onCardDelete = useCallback(store, hiddenCollectionIds) { card: Card ->
        val s = store ?: return@useCallback
        // The card goes, and with it any unsaved text kept for it — a draft outlives the editor, but
        // it must not outlive the note.
        if (card.kind == CardKind.NOTE) clearNoteDraft(cardDraftKey(card.id))
        scope.launch {
            val deleted = s.cards.delete(card.id) ?: return@launch
            reloadCards(card.collectionId)
            undo = Undo(t.deletedCard(card.title)) {
                s.cards.restore(deleted)
                reloadCards(card.collectionId)
            }
        }
    }

    val onCardStartDrag = useCallback { card: Card -> draggingCardId = card.id }

    val onCardEndDrag = useCallback {
        draggingCardId = null
        dropGroup = null
        cancelHoverOpen()
    }

    // Dropping a card on a tile puts it in that tile's group, right before it. What is on screen is the
    // stored order itself, so the slot the card is dropped into is the slot it takes.
    val onDropOnTile = useCallback(
        store,
        cards,
        selectedId,
        draggingCardId,
        draggingTab,
        draggingHistory,
        hiddenCollectionIds,
    ) { target: Card ->
        val collectionId = selectedId ?: return@useCallback
        val dragged = draggingCardId
        if (dragged == target.id) return@useCallback
        val index = if (dragged != null) {
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
            val deleted = s.cards.delete(card.id) ?: return@launch
            searchResults = s.cards.search(query.trim())
            reloadCards(selectedId)
            undo = Undo(t.deletedCard(card.title)) {
                s.cards.restore(deleted)
                searchResults = s.cards.search(query.trim())
                reloadCards(selectedId)
            }
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
        aiProvider,
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
            aiProvider = aiProvider,
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
     *
     * The row itself is counted too, under its kind: a user who always ends up asking the model, or
     * always searching the web, gets that row offered sooner next time (see [HitAction]). Only rows
     * taken *here* count — the box learns what the user comes to the box for, not what they do with
     * the rest of the app.
     */
    fun activateHit(hit: Hit) {
        recordAction(hit.action)
        usageVersion += 1
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
            // The built-in model answers here: the question opens the conversation window over the
            // page, the box goes back to being a search box, and the follow-ups are asked in the window.
            //
            // A web chat cannot answer here, so the question goes there — to a new conversation with the
            // message already sent, in this tab, exactly as a web search goes to the results page. Both
            // are the search box handing the query to something that answers it elsewhere.
            is AiHit -> {
                val chat = hit.provider.chatUrl(hit.query)
                if (chat == null) {
                    aiOpen = true
                    aiQuestion = hit.query
                } else {
                    navigateTo(chat)
                }
                clearSearch()
            }
        }
    }

    fun exitAi() {
        aiOpen = false
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

    // Ctrl/Cmd+Z takes back whatever was just deleted, the same as clicking the toast's Undo button —
    // the offer is on screen for anyone who looks, but reaching for the shortcut is not remembering to
    // look. The listener is registered once, so it reads the current undoDelete through a ref rather
    // than closing over the first render's (stale) one — see [Modals.modalShell] for the same idiom.
    val undoDeleteRef = useRef(::undoDelete)
    undoDeleteRef.current = ::undoDelete
    useEffectOnce {
        val stopWatching = onKeyStroke { event ->
            if ((event.ctrlKey || event.metaKey) && event.key.lowercase() == "z" && !isTyping(event)) {
                event.preventDefault()
                undoDeleteRef.current?.invoke()
            }
        }
        try {
            awaitCancellation()
        } finally {
            stopWatching()
        }
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
        className = ClassName(if (swapSidebars) "app swapped" else "app")

        // A file dropped anywhere but a drop zone — the toolbar, the tab list, the gap between two
        // sections — is a file the *browser* would open, navigating the page to it and taking the app
        // off the screen with it. So the page as a whole takes every file drag (the drop zones inside
        // it stop their own events before they reach here) and does nothing at all with it.
        onDragOver = { e -> if (draggingFiles(e.dataTransfer)) e.preventDefault() }
        onDrop = { e ->
            if (draggingFiles(e.dataTransfer)) {
                e.preventDefault()
                dropGroup = null
                contentDropActive = false
                dropCollectionId = null
            }
        }
        // A file drag carried back out of the window ends nowhere: no dragend of ours ever fires for
        // it, and the target it was over would stay lit. `relatedTarget == null` is what tells leaving
        // the window from merely crossing between two elements inside it.
        onDragLeave = { e ->
            if (e.asDynamic().relatedTarget == null) {
                dropGroup = null
                contentDropActive = false
                dropCollectionId = null
            }
        }

        // ---- Left sidebar (sections + collections), collapsible ----
        if (leftCollapsed) {
            aside {
                className = ClassName("sidebar collapsed")
                button {
                    className = ClassName("rail-toggle")
                    hint(t.expandSidebar)
                    onClick = { leftCollapsed = false; prefSet("leftCollapsed", "0") }
                    // Points away from the edge this sidebar is anchored to — right normally, left once
                    // it has traded places with the tabs sidebar and sits against the right edge instead.
                    +(if (swapSidebars) "«" else "»")
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
                    img {
                        className = ClassName("brand-logo")
                        src = "logo-128.png"
                        alt = ""
                        draggable = false
                    }
                    span {
                        className = ClassName("brand-name")
                        +"stramus"
                    }
                    button {
                        className = ClassName("icon collapse-btn")
                        hint(t.collapseSidebar)
                        onClick = { leftCollapsed = true; prefSet("leftCollapsed", "1") }
                        // Points back towards the edge this sidebar collapses into. See the expand
                        // button above for why the direction flips with [swapSidebars].
                        +(if (swapSidebars) "»" else "«")
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
                    // A section dragged onto another one is a reorder, and a lock has no say in it:
                    // where a section sits says nothing about what is behind its PIN.
                    val takesSection = draggingSectionId != null && draggingSectionId != section.id
                    div {
                        key = key(section.id)
                        className = ClassName(
                            buildString {
                                append("section")
                                if (isLocked) append(" locked")
                                if (section.id == draggingSectionId) append(" dragging")
                                if (section.id == dropSectionId) append(" drop-section")
                            },
                        )
                        // Dropping a collection anywhere on the section (not on a specific item) moves
                        // it to the end of this section; dropping a *section* reorders the sidebar.
                        onDragOver = { e ->
                            if (takesSection || (draggingCollectionId != null && !isLocked)) e.preventDefault()
                        }
                        onDragEnter = {
                            if (takesSection || (draggingCollectionId != null && !isLocked)) dropSectionId = section.id
                        }
                        onDrop = { e ->
                            if (takesSection) {
                                e.preventDefault()
                                moveSection(section.id)
                            } else if (draggingCollectionId != null && !isLocked) {
                                e.preventDefault()
                                moveCollection(section.id, collections.count { it.sectionId == section.id })
                            }
                        }
                        div {
                            className = ClassName("section-head")
                            // The header is the handle the section is dragged by — except while it is
                            // being renamed, when the pointer belongs to the text field in its title.
                            draggable = renamingId != section.id
                            onDragStart = { e ->
                                e.dataTransfer.setData("text/plain", section.id.toString())
                                draggingSectionId = section.id
                            }
                            onDragEnd = {
                                draggingSectionId = null
                                dropSectionId = null
                            }
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
                                    // The chevron turns as the section folds, so the two move together.
                                    // A locked section is not folded but shut, and its padlock stands still.
                                    className = ClassName(
                                        if (section.collapsed && !isLocked) "chevron closed" else "chevron",
                                    )
                                    +(if (isLocked) "🔒" else "▾")
                                }
                                if (renamingId == section.id && !isLocked) {
                                    InlineEdit {
                                        initial = section.title
                                        onCommit = { name -> renameSection(section, name) }
                                        onCancel = { renamingId = null }
                                    }
                                } else if (section.title.isBlank()) {
                                    // A section left without a name is still there to be found and clicked:
                                    // it says so, rather than showing an empty header that reads as nothing.
                                    span { className = ClassName("untitled"); +t.untitled }
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
                        Collapsible {
                            open = !section.collapsed && !isLocked
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
                                        // A dragged section is none of these things: it falls through to
                                        // the section behind this row, which is what it is dropped on.
                                        // A dragged card section belongs to the grid it came from and
                                        // has no business here at all, so this row does not light up.
                                        val takesDrag = draggingSectionId == null && draggingCardSectionId == null &&
                                            (draggingCollectionId != null || takesContent)
                                        // Files from the desktop land here too — saved into this
                                        // collection, ungrouped, without it having to be opened first.
                                        //
                                        // A dragged card additionally schedules this collection to open
                                        // on its own — see [scheduleHoverOpen] — so it can be dropped into
                                        // a group of the collection's own grid, not just left ungrouped.
                                        onDragEnter = { e ->
                                            if (takesDrag || (takesContent && draggingFiles(e.dataTransfer))) {
                                                e.preventDefault()
                                                if (dropCollectionId != c.id) dropCollectionId = c.id
                                                if (draggingCardId != null && c.id != selectedId) {
                                                    scheduleHoverOpen(c.id)
                                                }
                                            }
                                        }
                                        onDragOver = { e ->
                                            val files = takesContent && draggingFiles(e.dataTransfer)
                                            if (takesDrag || files) e.preventDefault()
                                            if (files) e.dataTransfer.dropEffect = DropEffect.copy
                                            // The drag left the content area; drop the group it was
                                            // last over, or two targets would light up at once.
                                            if (dropGroup != null) dropGroup = null
                                            leaveTabsSidebar()
                                        }
                                        onDragLeave = {
                                            if (dropCollectionId == c.id) {
                                                dropCollectionId = null
                                                cancelHoverOpen()
                                            }
                                        }
                                        onDrop = { e ->
                                            e.preventDefault()
                                            e.stopPropagation() // don't also fire the section's drop
                                            cancelHoverOpen()
                                            val tab = draggingTab
                                            val visit = draggingHistory
                                            val draggedCard = draggingCardId
                                            val draggedCol = draggingCollectionId
                                            val draggedSection = draggingSectionId
                                            val s = store
                                            when {
                                                // Files first: they are the one drag the app's own
                                                // state says nothing about, and a drop carrying them
                                                // is carrying nothing else.
                                                draggingFiles(e.dataTransfer) ->
                                                    if (takesContent) saveFiles(droppedFiles(e.dataTransfer), c.id)
                                                // The drop stops here, so a section dropped on a row of
                                                // this section is moved from here — it never reaches the
                                                // section's own handler behind it.
                                                draggedSection != null -> moveSection(c.sectionId)
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
                                            } else if (c.title.isBlank()) {
                                                span { className = ClassName("untitled"); +t.untitled }
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
            // The whole content area is a fallback drop zone for a dragged tab, a history entry or a
            // file from the desktop: saved ungrouped into the selected collection. Anything dropped on
            // a group is that group's. What is on the content area has to be a collection that takes
            // edits at all — not a PIN screen (locked section), not a read-only collection, not a page
            // of search results.
            val droppableCollection = targetCollection?.takeIf { !showAllResults }
            val takesPage = droppableCollection != null && (draggingTab != null || draggingHistory != null)
            // Whether a drag carries files is asked of the event, never of the app's own drag state:
            // until it lands, a dragged file is something the browser knows about and we do not.

            onDragEnter = { e ->
                if (takesPage || (droppableCollection != null && draggingFiles(e.dataTransfer))) e.preventDefault()
            }
            onDragOver = { e ->
                // A group that is under the pointer stops this event, so reaching here means the
                // drag is over the content area but over no group: no group is the drop target.
                if (dropGroup != null) dropGroup = null
                leaveTabsSidebar()
                val files = droppableCollection != null && draggingFiles(e.dataTransfer)
                if (takesPage || files) {
                    e.preventDefault()
                    if (files) e.dataTransfer.dropEffect = DropEffect.copy
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
                        draggingFiles(e.dataTransfer) -> saveFiles(droppedFiles(e.dataTransfer), droppableCollection.id)
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
                    // Searching is leaving the PIN screen a clicked-on section header put up; the
                    // section stays locked, its cards simply are not among the results.
                    onQueryChange = { value ->
                        query = value
                        pendingUnlockId = null
                        if (value.isBlank()) showAllResults = false
                    }
                    onActivate = ::activateHit
                    onShowAll = { if (query.isNotBlank()) showAllResults = true }
                    onForget = { hit ->
                        forgetUse(hit.stat.url)
                        usageVersion += 1
                    }
                }
                div {
                    className = ClassName("toolbar")
                    SyncBadge {
                        strings = t
                        state = syncUi
                        onOpen = { accountOpen = true }
                    }
                }
            }

            if (showAllResults && query.isNotBlank()) {
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
                        // In the order the search gives them back. There is no ⇅ over the results: a
                        // sort here would have to rewrite the order of every collection a match came
                        // from, and the results are a view of the cards, not a place they live in.
                        visibleResults.forEach { card ->
                            CardTile {
                                key = key(card.id)
                                strings = t
                                this.card = card
                                showUrl = showCardUrls
                                isDraggable = false
                                // A card found by a search is still a card of its collection: if that
                                // one is read-only, the result carries no rename or delete either.
                                readOnly = card.collectionId in readOnlyCollections
                                isDragging = false
                                acceptsDrop = false
                                onOpen = onCardOpen
                                // A result edited from here is edited in two places at once: the
                                // results on screen, and the collection the card actually lives in.
                                onRename = onResultRenameRequest
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
                            // The read-only switch closes the row: everything before it edits, and
                            // this is what takes those controls away. Locked, it is the only one left.
                            button {
                                className = ClassName("btn")
                                hint(if (editable) t.makeReadOnlyHint else t.allowEditingHint)
                                onClick = { toggleReadOnly(current) }
                                +(if (editable) t.makeReadOnly else t.allowEditing)
                            }
                        }
                    }

                    val ungrouped = cardsByGroup[null] ?: emptyList()

                    // Adding a card belongs to a group, not to the collection as a whole: the menu is
                    // drawn in every group's header, and what it adds joins that group. [sectionId]
                    // null is the ungrouped area, which is exactly where the old toolbar button put
                    // everything.
                    fun ChildrenBuilder.groupAddMenu(sectionId: Uuid?) = addMenu(
                        strings = t,
                        onLink = {
                            val url = browserPrompt(t.pasteUrl)
                            val s = store
                            if (s != null && !url.isNullOrBlank()) {
                                val clean = url.trim()
                                scope.launch {
                                    s.cards.add(current.id, hostOf(clean), clean, faviconFor(clean), sectionId)
                                    reloadCards(current.id)
                                }
                            }
                        },
                        onNote = { noteModal = NoteModal(current.id, sectionId, null) },
                        onFile = { fileModal = FileModal(current.id, sectionId, null) },
                    )

                    // The ⇅ beside it puts that same group's cards in order. Sorting belongs to a
                    // section for the same reason adding does: what a sort means is "these cards, in
                    // this order", and a collection-wide one would shuffle sections the user never
                    // looked at.
                    //
                    // A menu of actions, not a setting: nothing in it is ever ticked, because the sort
                    // is over the moment it is chosen — it moved the cards, and there is no "sorted by
                    // title" left for the group to be in (see [CardSort]). The glyph does nothing on
                    // its own click: no one of the five orders is the obvious one to mean by "sort".
                    // And it writes, so it is drawn only where the tools are — a read-only collection
                    // has none of it.
                    fun ChildrenBuilder.groupSortMenu(sectionId: Uuid?) {
                        hoverMenu(glyph = "⇅", tooltip = t.sortLinks) {
                            menuTitle(t.sortMenuTitle)
                            CardSort.entries.forEach { by ->
                                menuItem(by.label(t)) { sortCards(current.id, sectionId, by) }
                            }
                        }
                    }

                    // Unlike the tools above, this opens rather than writes — a read-only collection
                    // gets it too, which is the whole point for session recovery: the group that was
                    // saved read-only comes back exactly the same way a read-write one does.
                    fun ChildrenBuilder.groupOpenAllButton(groupCards: List<Card>) {
                        val linkCount = groupCards.count { it.kind == CardKind.LINK }
                        if (linkCount == 0) return
                        button {
                            className = ClassName("icon open-all")
                            hint(t.openAllHint(linkCount))
                            onClick = { e -> e.stopPropagation(); openAllCards(groupCards) }
                            +"↗"
                        }
                    }

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
                            acceptsFiles = editable,
                            active = dropGroup == DropGroup(null),
                            onOver = {
                                if (dropGroup != DropGroup(null)) dropGroup = DropGroup(null)
                                leaveTabsSidebar()
                            },
                            onDropHere = { dropOnGroup(current.id, null, Int.MAX_VALUE) },
                            onDropFiles = { files -> saveFiles(files, current.id) },
                        ) {
                            div {
                                className = ClassName("card-section-head")
                                span {
                                    className = ClassName("card-section-title")
                                    +t.ungrouped
                                    span { className = ClassName("count"); +" ${ungrouped.size}" }
                                }
                                if (editable || ungrouped.any { it.kind == CardKind.LINK }) div {
                                    className = ClassName("card-section-tools")
                                    groupOpenAllButton(ungrouped)
                                    if (editable) {
                                        groupAddMenu(null)
                                        groupSortMenu(null)
                                    }
                                }
                            }
                            if (ungrouped.isNotEmpty()) {
                                cardGrid(
                                    strings = t,
                                    cards = ungrouped,
                                    draggingCardId = draggingCardId,
                                    readOnly = !editable,
                                    showUrls = showCardUrls,
                                    onOpen = onCardOpen,
                                    onRename = onCardRenameRequest,
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
                            // A section dragged over another one is a reorder, and it is dropped on the
                            // whole group, header and cards alike — the header strip alone would be a
                            // needle to thread, exactly as it is for a card (see [cardGroup]).
                            val takesSection = editable &&
                                draggingCardSectionId != null && draggingCardSectionId != cs.id
                            cardGroup(
                                groupKey = key(cs.id),
                                accepts = groupAccepts || takesSection,
                                acceptsFiles = editable,
                                active = dropGroup == DropGroup(cs.id),
                                dragging = draggingCardSectionId == cs.id,
                                onOver = {
                                    if (dropGroup != DropGroup(cs.id)) dropGroup = DropGroup(cs.id)
                                    leaveTabsSidebar()
                                },
                                onDropHere = {
                                    if (draggingCardSectionId != null) moveCardSection(cs.id)
                                    else dropOnGroup(current.id, cs.id, Int.MAX_VALUE)
                                },
                                // A file dropped on a section joins that section, as a dragged tab does.
                                onDropFiles = { files -> saveFiles(files, current.id, cs.id) },
                            ) {
                                div {
                                    className = ClassName("card-section-head")
                                    // The header is the handle the section is dragged by — except while
                                    // it is being renamed, when the pointer belongs to the text field.
                                    draggable = editable && renamingId != cs.id
                                    onDragStart = { e ->
                                        e.dataTransfer.setData("text/plain", cs.id.toString())
                                        draggingCardSectionId = cs.id
                                    }
                                    onDragEnd = {
                                        draggingCardSectionId = null
                                        dropGroup = null
                                    }
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
                                        span {
                                            className = ClassName(if (cs.collapsed) "chevron closed" else "chevron")
                                            +"▾"
                                        }
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
                                    if (editable || groupCards.any { it.kind == CardKind.LINK }) div {
                                        className = ClassName("card-section-tools")
                                        groupOpenAllButton(groupCards)
                                        if (editable) {
                                            groupAddMenu(cs.id)
                                            groupSortMenu(cs.id)
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
                                }
                                // A collapsed section still takes drops — its header is inside the
                                // group — and the card joins it, out of sight.
                                Collapsible {
                                    open = !cs.collapsed
                                    if (!cs.description.isNullOrBlank()) {
                                        markdownBlock("card-section-desc", cs.description!!)
                                    }
                                    if (groupCards.isEmpty()) {
                                        div { className = ClassName("empty small"); +t.dragLinksHere }
                                    } else {
                                        cardGrid(
                                            strings = t,
                                            cards = groupCards,
                                            draggingCardId = draggingCardId,
                                            readOnly = !editable,
                                            showUrls = showCardUrls,
                                            onOpen = onCardOpen,
                                            onRename = onCardRenameRequest,
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
                        // See the sections sidebar's own expand button for why this flips with
                        // [swapSidebars]: it always points away from the edge this panel is anchored to.
                        +(if (swapSidebars) "»" else "«")
                    }
                }
            } else {
                // Cards only stand in for the list on the Tabs pane itself — History has no card shape
                // of its own — so the wider, grid layout follows the setting only while that pane is up.
                val showTabCards = tabsCardView && pane == RightPane.TABS
                aside {
                    className = ClassName(if (showTabCards) "tabs cards-view" else "tabs")
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
                        // A quick switch beside the collapse button — the same choice as the setting in
                        // Settings, for when reaching there is more than the moment is worth. Only where
                        // there is a Tabs pane to switch the shape of at all.
                        if (pane == RightPane.TABS) {
                            button {
                                className = ClassName("icon view-toggle")
                                hint(if (tabsCardView) t.tabsViewToggleToList else t.tabsViewToggleToCards)
                                onClick = {
                                    val next = !tabsCardView
                                    tabsCardView = next
                                    prefSet("tabsCardView", if (next) "1" else "0")
                                }
                                +(if (tabsCardView) "☰" else "⊞")
                            }
                        }
                        button {
                            className = ClassName("icon collapse-btn")
                            hint(t.hideTabs)
                            onClick = { rightCollapsed = true; prefSet("rightCollapsed", "1") }
                            +(if (swapSidebars) "«" else "»")
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
                                        // The ✨ needs no collection selected — it decides that itself,
                                        // and may make one — only a model on this machine and tabs to
                                        // read. It is offered even where the model is still to be
                                        // downloaded: the window shows the download rather than hanging.
                                        // Off unless switched on in the settings, and then only
                                        // where there is a model on this machine to do it and tabs
                                        // to do it to.
                                        triageHint = t.triageTabs.takeIf {
                                            aiTriage && aiLocalAvailable == true && allWindowTabs.isNotEmpty()
                                        },
                                        onOver = { hoverTabs(windowId, null) },
                                        // Dropped on the window but on none of its tabs: append (-1).
                                        onDropHere = { moveTabTo(windowId, -1) },
                                        onSave = {
                                            targetCollection?.let { target -> saveTabs(allWindowTabs, target) }
                                        },
                                        onTriage = { triageWindowId = windowId },
                                        onSort = { by -> sortTabs(windowId, by) },
                                    ) {
                                        if (showTabCards) {
                                            tabCardGrid(
                                                strings = t,
                                                tabs = windowTabs,
                                                showUrls = showCardUrls,
                                                draggingTabId = draggingTab?.id,
                                                dropTabId = dropTabId,
                                                onGoTo = onTabGoTo,
                                                onClose = onTabClose,
                                                onStartDrag = onTabStartDrag,
                                                onEndDrag = onTabEndDrag,
                                                onOver = onTabOver,
                                                onDropHere = onTabDropHere,
                                            )
                                        } else {
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
                viewHeading = t.viewNote
                // A note that exists opens to be read — clicking a card is how one *looks* at what was
                // saved. A note that does not yet exist has nothing to read, so it opens in writing.
                startInEdit = m.existing == null
                showTitle = true
                initialTitle = m.existing?.title ?: ""
                initialContent = m.existing?.content ?: ""
                // Unsaved text survives the modal, and the tab: an existing note keeps its draft under
                // its own id, a new one under the place it is being written for.
                draftKey = m.existing?.let { cardDraftKey(it.id) }
                    ?: newNoteDraftKey(m.collectionId, m.cardSectionId)
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
        val liveEngine = engine
        val liveStore = store
        if (accountOpen && liveEngine != null && liveStore != null) {
            AccountDialog {
                strings = t
                state = syncUi
                this.api = api
                engine = liveEngine
                store = liveStore
                google = props.google
                onSynced = { reloadAfterSync() }
                onState = { syncUi = it }
                onClose = { accountOpen = false }
            }
        }

        if (settingsOpen) {
            SettingsModal {
                strings = t
                this.theme = theme
                onThemeChange = { theme = it }
                this.lang = lang.id
                onLangChange = { lang = Lang.from(it) }
                this.showCardUrls = showCardUrls
                onShowCardUrlsChange = { show ->
                    showCardUrls = show
                    prefSet("showCardUrls", if (show) "1" else "0")
                }
                this.hasRightSidebar = hasRightSidebar
                this.swapSidebars = swapSidebars
                onSwapSidebarsChange = { swap ->
                    swapSidebars = swap
                    prefSet("swapSidebars", if (swap) "1" else "0")
                }
                this.tabsCardView = tabsCardView
                onTabsCardViewChange = { cards ->
                    tabsCardView = cards
                    prefSet("tabsCardView", if (cards) "1" else "0")
                }
                this.syncUsage = syncUsage
                onSyncUsageChange = { on ->
                    syncUsage = on
                    prefSet("syncUsage", if (on) "1" else "0")
                    // Turning it *on* has to go and fetch what was skipped while it was off: those rows came
                    // down in the delta, were dropped on the floor, and the cursor moved past them. Only a
                    // fresh read of the whole account brings them back.
                    if (on) scope.launch { engine?.refetchEverything(); runSync() }
                }
                this.startView = startView.id
                onStartViewChange = { id ->
                    startView = StartView.from(id)
                    prefSet("startView", id)
                }
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
                this.aiTriage = aiTriage
                onAiTriageChange = { on ->
                    aiTriage = on
                    prefSet(AI_TRIAGE_PREF, if (on) "1" else "0")
                }
                // Who is answering — the one actually answering, which on a browser without a model of
                // its own is not the one that was chosen. The local option is offered until the browser
                // has said it cannot run it; until then it is only unknown, not refused.
                this.aiProvider = aiProvider.id
                onAiProviderChange = { id ->
                    aiChoice = AiProvider.from(id)
                    prefSet(AI_PROVIDER_PREF, id)
                }
                this.aiLocalAvailable = aiLocalAvailable != false
                aiName = ai?.name
                this.aiState = aiState
                // An export is a file the user can open anywhere, so a section whose PIN has not been
                // entered stays out of it — otherwise it would be the way around the lock.
                onExportCsv = { store?.let { s -> scope.launch { exportCsv(s, lockedSectionIds) } } }
                onExportBookmarks = { store?.let { s -> scope.launch { exportBookmarks(s, lockedSectionIds) } } }
                // The other direction: a bookmarks file or a CSV read back in. It creates whatever the
                // file names — sections, collections, card sections — so the sidebar and the open
                // collection are both re-read from the database once it is done.
                onImport = { name, text ->
                    val s = store
                    if (s != null) {
                        importStatus = null
                        scope.launch {
                            val result = importFile(s, name, text, t.importedTitle)
                            sections = s.sections.all()
                            collections = s.collections.all()
                            reloadCards(selectedId)
                            importStatus = if (result.added == 0 && result.skipped == 0) {
                                t.importNothing
                            } else {
                                t.importDone(result.added, result.skipped)
                            }
                        }
                    }
                }
                this.importStatus = importStatus
                onClose = {
                    settingsOpen = false
                    importStatus = null
                }
            }
        }
        descModal?.let { m ->
            NoteEditor {
                strings = t
                heading = t.sectionDescription
                viewHeading = t.sectionDescription
                // Unlike a note, this editor is not reached by looking at something — it is reached by
                // asking to change the description, so it opens on that.
                startInEdit = true
                showTitle = false
                initialTitle = m.title
                initialContent = m.description
                draftKey = descDraftKey(m.sectionId)
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

        renameModal?.let { m ->
            // The model is offered to the box only once it is *on this machine*: a title cleaned up
            // on-device costs the user nothing and leaves nothing, which is why this does not ask who
            // they chose to answer their questions — a web chat is never handed a title here. A model
            // that would first have to be downloaded is not offered at all: nobody asked for a few
            // hundred megabytes by pressing ✎.
            //
            // Read out here rather than in the builder below, where `ai` is the prop being set.
            val titleCleaner = ai.takeIf { aiState == AiAvailability.AVAILABLE }
            RenameCardModal {
                strings = t
                this.card = m.card
                this.ai = titleCleaner
                onClose = { renameModal = null }
                onSave = { renamed ->
                    if (renamed != m.card.title) {
                        if (m.fromSearch) onResultRename(m.card, renamed) else onCardRename(m.card, renamed)
                    }
                    renameModal = null
                }
            }
        }

        val aiAssistant = ai
        if (aiOpen && aiAssistant != null) {
            // The conversation stands over the collection rather than in place of it: what the model
            // was told about is still on screen behind the window, and closing it changes nothing.
            val target = targetCollection
            AiChat {
                strings = t
                assistant = aiAssistant
                initialQuestion = aiQuestion
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
        }

        val triageStore = store
        val triageTabs = triageWindowId?.let { id -> openTabs.filter { it.windowId == id } }.orEmpty()
        // Only the collections a card could actually be saved into: a read-only one, and one behind a
        // PIN, are not the model's to propose — nor even to be told about. Read out here rather than in
        // the builder below, where `collections` is the prop being set.
        val triageTargets = collections.filter { it.id !in hiddenCollectionIds && !it.readOnly }
        // The groups those collections live in, for the same reason: read out here, where `sections`
        // still means App's own state rather than the prop being set.
        val triageSidebarSections = sections.filter { it.id !in lockedSectionIds }
        // The model is offered this regardless of who the user chose to answer their *questions* (see
        // [AiProvider]): a window of tabs is read on this machine or not at all, and a web chat is never
        // handed one. So the gate is the local model's own availability, nothing else.
        if (aiTriage && aiAssistant != null && triageStore != null && triageTabs.isNotEmpty() && aiLocalAvailable == true) {
            // Where a collection the plan invents goes when the model names no section for it: the
            // default one, and never the section the user happens to be standing in.
            //
            // It was the standing-in one, and that was wrong twice over. It put a new "Электроника"
            // under "Работа" for no better reason than that a work collection was open — and, worse,
            // it reached the model: an invented collection is described to the next batch under this
            // section (see `triage`), so where the user was browsing quietly bent what the model
            // answered next. Sorting a window of tabs is not about the page the user is looking at,
            // and nothing about that page belongs in it.
            val triageSection = sections.firstOrNull { !it.deletable && it.id !in lockedSectionIds }
                ?: sections.firstOrNull { it.id !in lockedSectionIds }
            if (triageSection != null) {
                TabTriageModal {
                    strings = t
                    assistant = aiAssistant
                    tabs = triageTabs
                    intoCollections = triageTargets
                    savedCards = triageStore.cards
                    savedSections = triageStore.cardSections
                    sidebarSections = triageSidebarSections
                    newCollectionsIn = triageSection.title
                    closesTabs = closeSavedTabs
                    canSaveSummary = targetCollection != null
                    onSaveSummary = { title, content ->
                        targetCollection?.let { target ->
                            scope.launch {
                                triageStore.cards.addNote(target.id, title, content)
                                reloadCards(target.id)
                            }
                        }
                    }
                    onApply = { plan -> applyTriage(plan, triageSection.id) }
                    onClose = { triageWindowId = null }
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
