package stramus.ui

import stramus.core.db.StoreSeed

/** A UI language. [id] is what gets persisted in localStorage and stamped on `<html lang>`. */
enum class Lang(val id: String, val label: String) {
    EN("en", "English"),
    RU("ru", "Русский"),
    DE("de", "Deutsch"),
    FR("fr", "Français"),
    ES("es", "Español"),
    PT_BR("pt-BR", "Português (Brasil)"),
    JA("ja", "日本語"),
    KO("ko", "한국어"),
    ZH_CN("zh-CN", "简体中文"),
    IT("it", "Italiano"),
    TR("tr", "Türkçe"),
    ;

    // An exhaustive `when`, not an `if` — so a new entry here is a compile error until it is given a
    // branch, and the "adding a language is a compile error until translated" promise above actually
    // holds instead of silently falling back to English.
    val strings: Strings get() = when (this) {
        EN -> EnStrings
        RU -> RuStrings
        DE -> DeStrings
        FR -> FrStrings
        ES -> EsStrings
        PT_BR -> PtBrStrings
        JA -> JaStrings
        KO -> KoStrings
        ZH_CN -> ZhCnStrings
        IT -> ItStrings
        TR -> TrStrings
    }

    companion object {
        /** The persisted choice, or — on first run — the browser's language if we speak it. */
        fun from(id: String?): Lang =
            entries.firstOrNull { it.id == id } ?: entries.firstOrNull { browserLanguage().startsWith(it.id) } ?: EN
    }
}

/**
 * Every user-visible string in the UI. Implemented once per [Lang], so adding a language is a
 * compile error until it is fully translated. Strings carrying a glyph keep it here (rather than in
 * the markup) so a translation can move or drop it.
 *
 * `App` owns the selected [Lang] and passes its table down — as a prop to components, as a parameter
 * to the render helpers. Switching the language re-renders the tree, so all text updates at once.
 */
interface Strings {
    // Common
    val on: String
    val off: String

    /** The badge on a feature that is not finished, and is opted into knowing that. */
    val experimental: String

    val settings: String
    val close: String
    val cancel: String
    val save: String

    /** Opens the About modal, from the left sidebar footer. */
    val about: String

    /** The version line in the About modal, e.g. "Version 1.2.1". */
    fun aboutVersion(version: String): String

    /** The copyright line in the About modal, e.g. "© 2024–2026 Stramus". */
    fun aboutCopyright(year: String): String
    val aboutHomepage: String

    // Left sidebar
    val expandSidebar: String
    val collapseSidebar: String
    val newSection: String
    val sectionNamePrompt: String
    val sectionNameDefault: String
    val collectionNamePrompt: String
    val collectionNameDefault: String

    /**
     * Tooltip on a section title, which collapses on a click, renames on a double click, and is the
     * handle the section is dragged by to be reordered in the sidebar.
     */
    val renameHint: String

    /** Tooltip on a collection title, which is selected on a click and renamed on a double click. */
    val renameCollectionHint: String

    /** Stands in, in the sidebar, for a section or collection that has been left without a name. */
    val untitled: String

    /**
     * Tooltips on the buttons that add and remove. A `+` or a `×` says what will happen but not to
     * what — and the two `+`s (a collection, a card section) are the same glyph for different things.
     * The deletions say what goes with the thing deleted, since that is what the click is really about.
     */
    val newSectionHint: String
    val addCollectionHint: String
    val deleteSectionHint: String
    val deleteCollectionHint: String
    val addCardSectionHint: String
    val deleteCardSectionHint: String
    val addCardHint: String
    val deleteCardHint: String

    /** The group's "open all" button — [count] is how many of its cards are links, the rest skipped. */
    fun openAllHint(count: Int): String

    // Deleting, and the way back from it. Only what holds something is asked about — deleting an
    // empty section is nothing to stop the user over — but everything deleted can be undone.
    fun confirmDeleteSection(title: String, cards: Int): String
    fun confirmDeleteCollection(title: String, cards: Int): String
    fun confirmDeleteCardSection(title: String, cards: Int): String
    fun deletedSection(title: String): String
    fun deletedCollection(title: String): String
    fun deletedCardSection(title: String): String
    fun deletedCard(title: String): String

    /** A card dragged into another collection is offered back the same way a deletion is. */
    fun movedCard(title: String): String

    /**
     * A sort is offered back the same way a deletion is: it overwrites an order the user may have
     * arranged by hand, and that order is the one thing it destroys.
     */
    val sortedCards: String

    /** The button on the toast that puts the deleted thing — or the order a sort overwrote — back. */
    val undo: String

    // The search box: the dropdown's group headings, its rows, and the hint line under them
    val searchPlaceholder: String

    /** Group headings, in the order they can appear. */
    val hitsTopSites: String
    val hitsTabs: String
    val hitsCards: String
    val hitsHistory: String
    val hitsSites: String
    val hitsCollections: String

    /** The badge on a row: what Enter on it will do, where that is not obvious. */
    val hitSwitchToTab: String
    val hitOpenCollection: String
    val hitAskAi: String

    /** The action rows themselves. [hitAskAiRow] names the assistant — see [AiProvider]. */
    fun hitWebSearch(query: String): String
    fun hitOpenUrl(query: String): String
    fun hitAskAiRow(assistant: String, query: String): String

    /** The × on a top site: stop counting this page among the ones the user lives in. */
    val forgetSite: String

    /** The keys, spelled out under the list. */
    val searchHints: String

    // The built-in model (Chrome's on-device AI), in the conversation window
    val aiChip: String
    val aiHeading: String
    val aiEmpty: String
    val aiPlaceholder: String
    val aiSend: String
    val aiThinking: String
    val aiCopy: String
    val aiSaveNote: String
    val aiUnavailable: String
    val aiFailed: String

    /** The model itself is downloaded on first use — a few hundred megabytes, once. */
    fun aiDownloading(percent: Int): String

    /** What the model is told before the first question: where it is, and how to answer. */
    val aiSystemPrompt: String

    // Sorting a window of tabs into collections with the built-in model, and the preview that stands
    // between the model's plan and the store
    val aiTriageSetting: String
    val aiTriageSettingHint: String
    val triageTabs: String
    val triageHeading: String
    val triageSummaryHeading: String
    val triageSummaryTitle: String
    val triageNew: String
    val triageUnsorted: String
    val triageUnsortedHint: String
    val triageSkip: String
    val triageMoveHint: String
    val triageDuplicate: String
    val triageDuplicateHint: String

    /** Said on a collection the plan would create: where it would appear. */
    fun triageNewHint(section: String): String

    /** Said on a card section the plan would create inside a collection. */
    val triageNewSectionHint: String

    /** The picker on a collection the plan would create: which sidebar section to create it in. */
    val triageGroupHint: String

    /** The section picker on a row, and its "leave it ungrouped" option. */
    val triageSectionHint: String
    val triageNoSection: String

    /** The run works down the sites one at a time; this is how far it has got. */
    fun triageProgress(done: Int, total: Int): String

    /** What is already saved from this site — the reason a row may not be worth ticking. */
    fun triageRelated(site: String, count: Int): String

    /** The button that applies the plan. It says how many, and whether the tabs will be closed with it. */
    fun triageApply(count: Int, closesTabs: Boolean): String

    /** What the model is told before it is shown the sites: what it is sorting, and into what. */
    val aiTriageSystemPrompt: String

    // Settings: who answers, which model that is, and — when none does — why not
    val aiSection: String
    val aiAssistant: String
    val aiAssistantHint: String
    val aiProviderLocal: String

    /** Said where a web chat is chosen: the question opens there, and it leaves this machine. */
    fun aiWebChatHint(assistant: String): String

    val aiModel: String
    val aiModelReadyHint: String
    val aiModelDownloadableHint: String
    val aiModelDownloadingHint: String
    val aiModelNone: String
    val aiModelNoneHint: String
    fun aiModelUnsupported(name: String): String
    val aiModelUnsupportedHint: String

    // Content area
    fun resultsFor(query: String): String
    val noMatchingLinks: String
    val createCollectionToStart: String

    /**
     * The ⇅ menu in a card section's header, which rearranges that section's cards for good:
     * [sortLinks] is its tooltip, [sortMenuTitle] the heading over the orders it offers.
     */
    val sortLinks: String
    val sortMenuTitle: String
    val addCardSection: String
    val pasteUrl: String
    val addLinkItem: String
    val addNoteItem: String
    val addFileItem: String
    val noLinksYet: String
    val ungrouped: String
    val dragLinksHere: String
    val editDescription: String

    /** Files dragged in from the desktop: what was passed over, and why. */
    fun filesTooLarge(names: List<String>, maxMb: Int): String

    // The section PIN lock
    /** The action that puts an open section behind a PIN, and the heading of what a locked one offers. */
    val protectSection: String
    val sectionProtection: String
    val changePin: String
    val removeProtection: String
    /** Lock the section again without waiting for the idle timer or a reload. */
    val lockNow: String
    /** Tooltips on the sidebar's lock glyph: a locked section, and one opened for this session. */
    val lockedSection: String
    val unlockedSection: String
    val enterPinToView: String
    val pinPlaceholder: String
    val unlock: String
    val wrongPin: String
    val setPinHeading: String
    val changePinHeading: String
    val newPinLabel: String
    val repeatPinLabel: String
    val pinMismatch: String
    fun pinTooShort(min: Int): String
    /** What the lock is worth, said plainly where the PIN is chosen. */
    val pinNote: String

    // Read-only collections
    /**
     * The two ends of the toggle in the collection's header — the locking one is a bare icon, so its
     * tooltip is the only place it says what it does — and the badge a guarded collection wears.
     */
    val makeReadOnlyHint: String
    val allowEditing: String
    val allowEditingHint: String
    val readOnlyBadge: String
    val readOnlyHint: String

    // Settings: auto-lock
    val security: String
    val autoLock: String
    val autoLockHint: String
    val autoLockNever: String
    fun autoLockMinutes(minutes: Int): String

    // Right sidebar (open tabs / history)
    val openTabs: String
    val showTabs: String
    val hideTabs: String
    val noOpenTabs: String
    val searchTabs: String
    val noMatchingTabs: String
    /** The window the app itself is open in, told apart from the user's other windows. */
    val thisWindow: String
    fun windowLabel(number: Int): String
    val closeTab: String
    /** The ⇅ menu in a window's header, which rearranges that window's tabs in the browser itself. */
    val sortTabs: String
    /**
     * The ⤓ in a window's header: how many tabs the click would save, where they land, and whether
     * the browser keeps them. [closing] is the "after saving tabs" setting, so the tooltip is the
     * answer the user chose.
     */
    fun saveTabsHint(count: Int, closing: Boolean): String

    /** ...and the same, asked before it happens: a whole window's worth of tabs is not one click's work. */
    fun confirmSaveTabs(count: Int, collection: String, closing: Boolean): String

    /** Settings: what saving a whole window's tabs does to the tabs themselves. */
    val tabsSection: String
    val closeSavedTabs: String
    val closeSavedTabsHint: String
    val closeSavedTabsClose: String
    val closeSavedTabsKeep: String

    /** The two panes of the right sidebar, as the switch above it labels them. */
    val paneTabs: String
    val paneHistory: String

    val searchHistory: String
    val noHistory: String
    val noMatchingHistory: String
    /** The day groups of the history; every older day is labelled with its date instead. */
    val today: String
    val yesterday: String
    val removeFromHistory: String

    // Cards
    val emptyNote: String
    val fileLabel: String
    val renameCard: String
    val cardNamePrompt: String
    val renameHeading: String

    /** The link's address, which the edit box keeps folded away until it is asked for. */
    val renameShowUrl: String
    val renameHideUrl: String
    val renameUrlPrompt: String

    // Note editor
    val newNote: String
    val editNote: String
    val viewNote: String

    /** The button that turns a note being read into a note being written. */
    val editNoteAction: String
    val sectionDescription: String
    val titlePlaceholder: String
    val noteDefaultTitle: String

    /** Shown, greyed out, in an empty note body — an `::empty::before` in CSS, so it lives here rather
     *  than in index.html's CSS, which cannot see [Lang]. */
    val notePlaceholder: String
    val toolBold: String
    val toolItalic: String
    val toolHighlight: String
    val toolCode: String
    val toolLink: String
    val toolHeading: String
    val toolList: String
    val toolListLabel: String
    val highlightPlaceholder: String
    val codePlaceholder: String
    val linkUrlPrompt: String
    val draftRestored: String
    val discardDraft: String

    // File modal
    val addFile: String
    val chooseFile: String
    val download: String
    val fileDefaultTitle: String
    fun noPreviewFor(mime: String): String

    // Settings
    val appearance: String
    val theme: String
    val themeHint: String
    val themeAuto: String
    val themeLight: String
    val themeDark: String
    val accentColor: String
    val accentColorHint: String
    val accentBlue: String
    val accentPurple: String
    val accentGreen: String
    val accentOrange: String
    val accentRose: String
    val language: String
    val languageHint: String
    val cardUrls: String
    val cardUrlsHint: String
    val cardUrlsShow: String
    val cardUrlsHide: String

    /** Settings: whether a collection's card sections are stacked open, or shown as folders to open. */
    val groupsView: String
    val groupsViewHint: String
    val groupsViewList: String
    val groupsViewFolders: String

    /** Folder view: the link back to the grid, shown above the one folder currently open. */
    val folderBack: String

    /** Settings: which side the sections/collections sidebar sits on, vs. the tabs/history one. */
    val swapSidebars: String
    val swapSidebarsHint: String
    val swapSidebarsLeft: String
    val swapSidebarsRight: String

    /** Settings: whether the tabs sidebar shows a list of rows or a grid of cards, the same shape as
     *  the saved cards in the middle pane — and, in the grid, the same width as that pane too. */
    val tabsCardView: String
    val tabsCardViewHint: String
    val tabsCardViewList: String
    val tabsCardViewCards: String

    /** Settings: which collection the page opens on. */
    val startupSection: String
    val startView: String
    val startViewHint: String
    val startViewLast: String
    val startViewFirst: String

    /** The settings sidebar's name for the pane that holds both export and import. */
    val dataSection: String

    val export: String
    val exportHint: String
    val exportCsv: String
    val exportBookmarks: String

    val import: String
    val importHint: String
    val importFile: String

    /** Where links whose file named no folder for them go — a section and a collection of this name. */
    val importedTitle: String

    /** What the import did: [added] links saved, [skipped] already there. */
    fun importDone(added: Int, skipped: Int): String

    /** A file with no link this app could take in — the wrong file, or one already fully imported. */
    val importNothing: String

    // Sort orders. A card section takes all of them; a window's tabs are sorted by title, domain or URL.
    val sortTitle: String
    val sortUrl: String
    val sortDomain: String
    val sortNewest: String
    val sortOldest: String

    /**
     * What a first install is given to open: the default section, a collection in it, and a note in
     * that collection about what the app does. Written here rather than in the database because it is
     * text like any other on screen, and because the language it is written in is the one the user
     * arrived with — the app's, not SQLite's. See `StoreSeed`.
     */
    // Account and synchronisation
    val account: String
    val accountSignedOutHint: String
    /** Opens the account dialog's sign-in door, from the settings page's Account pane. */
    val signInAccount: String
    val signOut: String
    val syncNow: String
    fun syncedAt(time: String): String
    fun conflictCopies(count: Int): String
    val joinAccountTitle: String
    val joinAccountHint: String
    val joinAccountKeep: String
    val joinAccountDiscard: String
    val exportAccountData: String
    val exportAccountDataHint: String
    val exportAccountDataFailed: String
    val deleteAccount: String
    val deleteAccountHint: String
    val deleteAccountConfirm: String
    val syncUsage: String
    val syncUsageHint: String
    val optionOn: String
    val optionOff: String
    val signInWithGoogle: String

    /** Google is the only door at the moment, so a build with no client id has none to offer. */
    val signInUnavailable: String

    /** Why a button that talks to the server is greyed out: the last check found nobody there. */
    val serverUnavailable: String

    // Onboarding — the walkthrough shown once, on the very first open. See `Onboarding.kt`.
    val onboardingSignInTitle: String
    val onboardingSignInBody: String
    val onboardingSkip: String
    val onboardingInstallTitle: String
    val onboardingInstallBody: String
    val onboardingInstallCta: String
    val onboardingContinueInBrowser: String
    val onboardingOrganizeTitle: String
    val onboardingOrganizeBody: String
    val onboardingSearchTitle: String
    val onboardingSearchBody: String
    val onboardingBack: String
    val onboardingNext: String
    val onboardingGetStarted: String

    val seed: StoreSeed
}

private object EnStrings : Strings {
    override val on = "On"
    override val off = "Off"
    override val experimental = "experimental"
    override val settings = "Settings"
    override val close = "Close"
    override val cancel = "Cancel"
    override val save = "Save"
    override val about = "About"
    override fun aboutVersion(version: String) = "Version $version"
    override fun aboutCopyright(year: String) = "© $year Stramus"
    override val aboutHomepage = "stramus.space"

    override val expandSidebar = "Expand sidebar"
    override val collapseSidebar = "Collapse sidebar"
    override val newSection = "+ New section"
    override val sectionNamePrompt = "Section name"
    override val sectionNameDefault = "New section"
    override val collectionNamePrompt = "Collection name"
    override val collectionNameDefault = "New collection"
    override val renameHint = "Click to collapse, double-click to rename, drag to reorder"
    override val renameCollectionHint = "Double-click to rename"
    override val untitled = "Untitled"

    override val newSectionHint = "Add a section to the sidebar"
    override val addCollectionHint = "Add a collection to this section"
    override val deleteSectionHint = "Delete this section and the collections in it"
    override val deleteCollectionHint = "Delete this collection and its cards"
    override val addCardSectionHint = "Add a group to this collection"
    override val deleteCardSectionHint = "Delete this group — its cards stay in the collection, ungrouped"
    override val addCardHint = "Add a link — or, from the menu, a note or a file"
    override val deleteCardHint = "Delete this card"
    override fun openAllHint(count: Int) = "Open all $count cards as new tabs"

    override fun confirmDeleteSection(title: String, cards: Int) =
        "“$title” and its collections hold $cards saved items. Delete the section?"
    override fun confirmDeleteCollection(title: String, cards: Int) =
        "“$title” holds $cards saved items. Delete the collection?"
    override fun confirmDeleteCardSection(title: String, cards: Int) =
        "“$title” holds $cards cards. Delete the group? The cards stay, ungrouped."
    override fun deletedSection(title: String) = "Section “$title” deleted"
    override fun deletedCollection(title: String) = "Collection “$title” deleted"
    override fun deletedCardSection(title: String) = "Group “$title” deleted"
    override fun deletedCard(title: String) = "“$title” deleted"
    override fun movedCard(title: String) = "“$title” moved"
    override val sortedCards = "Cards sorted"
    override val undo = "Undo"

    override val searchPlaceholder = "Search, enter an address, or ask…"

    override val hitsTopSites = "Frequently opened"
    override val hitsTabs = "Open tabs"
    override val hitsCards = "Saved"
    override val hitsHistory = "History"
    override val hitsSites = "Sites"
    override val hitsCollections = "Collections"

    override val hitSwitchToTab = "Switch"
    override val hitOpenCollection = "Open"
    override val hitAskAi = "Ask"

    // The engine is the browser's own — whichever the user set — so it is not named here.
    override fun hitWebSearch(query: String) = "Search the web for “$query”"
    override fun hitOpenUrl(query: String) = "Open $query"
    override fun hitAskAiRow(assistant: String, query: String) = "Ask $assistant: “$query”"

    override val forgetSite = "Stop suggesting this page"
    override val searchHints = "↑↓ choose · Enter open · Alt+Enter search the web · ⌘/Ctrl+Enter all results · Esc close"

    override val aiChip = "AI"
    override val aiHeading = "Assistant"
    override val aiEmpty = "Ask about the collection you have open, or about anything else."
    override val aiPlaceholder = "Ask a follow-up…"
    override val aiSend = "Ask"
    override val aiThinking = "Thinking…"
    override val aiCopy = "Copy"
    override val aiSaveNote = "Save as note"
    override val aiUnavailable = "This browser has no built-in model available."
    override val aiFailed = "The model could not answer."
    override fun aiDownloading(percent: Int) = "Downloading the model — $percent%. This happens once."
    override val aiSystemPrompt = "You are the assistant inside stramus, a bookmark and tab manager. " +
        "Answer briefly and to the point, in the language the question is asked in. Markdown is welcome."

    override val aiTriageSetting = "Sort tabs with the built-in model"
    override val aiTriageSettingHint = "Adds a button to a window of tabs: the model reads them and proposes " +
        "a collection for each, for you to check before anything is saved. Everything stays on this " +
        "machine. It takes a minute or two on a large window, and it leaves out whatever it cannot place."
    override val triageTabs = "Sort into collections"
    override val triageHeading = "Sort tabs into collections"
    override val triageSummaryHeading = "What this session was about"
    override val triageSummaryTitle = "Session summary"
    override val triageNew = "new"
    override fun triageNewHint(section: String) = "There is no such collection yet — it will be created in \"$section\"."
    override val triageNewSectionHint = "There is no such group in this collection yet — it will be created."
    override val triageGroupHint = "Which sidebar section this new collection will be created in"
    override val triageSectionHint = "Which group inside the collection this tab goes under"
    override val triageNoSection = "No group"
    override fun triageProgress(done: Int, total: Int) = "Sorting sites — $done of $total…"
    override val triageUnsorted = "Not sorted"
    override val triageUnsortedHint = "The model had nothing to say about these. Pick a collection, or leave them open."
    override val triageSkip = "Don't save"
    override val triageMoveHint = "Which collection this tab goes into"
    override val triageDuplicate = "saved already"
    override val triageDuplicateHint = "This page is already in a collection. Tick it to save it again."
    override fun triageRelated(site: String, count: Int) = "Already saved from $site ($count):"
    override fun triageApply(count: Int, closesTabs: Boolean) =
        if (closesTabs) "Save ($count) and close" else "Save ($count)"
    override val aiTriageSystemPrompt = "You sort a user's open browser tabs into their collections. " +
        "You are given tabs and the collections that exist. For every tab, answer with the one " +
        "collection it belongs in — reuse an existing name wherever the tab fits it, and only invent " +
        "a short name (one or two words) when it fits none. Within a collection you may name a " +
        "section, reusing the existing ones too. Tabs of one site may belong to different " +
        "collections. Answer with nothing but the required JSON."

    override val aiSection = "AI"
    override val aiAssistant = "Assistant"
    override val aiAssistantHint = "Who answers a question asked from the search box."
    override val aiProviderLocal = "On-device"
    override fun aiWebChatHint(assistant: String) =
        "The question opens $assistant in this tab, already asked. It is sent to $assistant's servers — " +
            "unlike the on-device model, which answers here and keeps everything on this machine."

    override val aiModel = "Model"
    override val aiModelReadyHint = "The browser's built-in model. Runs on this machine — no key, and nothing leaves it."
    override val aiModelDownloadableHint = "The browser will download it on the first question — a few hundred megabytes, once."
    override val aiModelDownloadingHint = "The browser is downloading it right now."
    override val aiModelNone = "Not available"
    override val aiModelNoneHint =
        "This browser gives the page no built-in model, so the search box does not offer to ask one. " +
            "In Chrome it is available to the extension; a plain web page needs the flags for it."
    override fun aiModelUnsupported(name: String) = "$name — unavailable"
    override val aiModelUnsupportedHint =
        "The browser has the model but cannot run it here: it needs ~22 GB free on the drive holding " +
            "the Chrome profile, and a GPU with more than 4 GB of memory."

    override fun resultsFor(query: String) = "Results for “$query”"
    override val noMatchingLinks = "No matching links."
    override val createCollectionToStart = "Create a collection to start saving links."
    override val sortLinks = "Sort this group's cards"
    override val sortMenuTitle = "Sort by"
    override val addCardSection = "Group"
    override val pasteUrl = "Paste a URL"
    override val addLinkItem = "Link"
    override val addNoteItem = "Note"
    override val addFileItem = "File"
    override val noLinksYet = "No links yet — add one, or drag one here."
    override val ungrouped = "Ungrouped"
    override val dragLinksHere = "Drag links or files here."
    override val editDescription = "Edit description"

    override fun filesTooLarge(names: List<String>, maxMb: Int) =
        "Not saved — over $maxMb MB: ${names.joinToString(", ")}"

    override val protectSection = "Protect with a PIN"
    override val sectionProtection = "Section protection"
    override val changePin = "Change PIN"
    override val removeProtection = "Remove protection"
    override val lockNow = "Lock now"
    override val lockedSection = "Protected with a PIN"
    override val unlockedSection = "Unlocked — click to lock again"
    override val enterPinToView = "Enter the PIN to see this section's collections."
    override val pinPlaceholder = "PIN"
    override val unlock = "Unlock"
    override val wrongPin = "Wrong PIN."
    override val setPinHeading = "Protect section"
    override val changePinHeading = "Change PIN"
    override val newPinLabel = "New PIN"
    override val repeatPinLabel = "Repeat the PIN"
    override val pinMismatch = "The two PINs do not match."
    override fun pinTooShort(min: Int) = "The PIN must be at least $min digits."
    override val pinNote = "The PIN hides the whole section: its collections are not even named until " +
        "it is entered, and their cards stay out of search and export. There is no way to reset a " +
        "forgotten PIN."

    override val makeReadOnlyHint = "Make read-only: nothing can then be added, changed or deleted here."
    override val allowEditing = "Allow editing"
    override val allowEditingHint = "Allow editing again."
    override val readOnlyBadge = "read-only"
    override val readOnlyHint = "Read-only: nothing here can be added, changed or deleted."

    override val security = "Security"
    override val autoLock = "Auto-lock"
    override val autoLockHint = "Lock unlocked sections again after this long without any activity."
    override val autoLockNever = "Never"
    override fun autoLockMinutes(minutes: Int) = "$minutes min"

    override val openTabs = "Open tabs"
    override val showTabs = "Show open tabs"
    override val hideTabs = "Hide open tabs"
    override val noOpenTabs = "No open tabs to save."
    override val searchTabs = "Search tabs…"
    override val noMatchingTabs = "No matching tabs."
    override val thisWindow = "This window"
    override fun windowLabel(number: Int) = "Window $number"
    override val closeTab = "Close tab"
    override val sortTabs = "Sort this window's tabs"
    override fun saveTabsHint(count: Int, closing: Boolean) =
        "Save this window's tabs ($count) into the open collection, ungrouped — " +
            if (closing) "and close them" else "and leave them open"

    override fun confirmSaveTabs(count: Int, collection: String, closing: Boolean) =
        if (closing) "Save this window's tabs ($count) into “$collection” and close them?"
        else "Save this window's tabs ($count) into “$collection”?"

    override val tabsSection = "Tabs"
    override val closeSavedTabs = "After saving tabs"
    override val closeSavedTabsHint =
        "What happens to a window's tabs once they are saved into a collection."
    override val closeSavedTabsClose = "Close them"
    override val closeSavedTabsKeep = "Keep them open"

    override val paneTabs = "Tabs"
    override val paneHistory = "History"
    override val searchHistory = "Search history…"
    override val noHistory = "Nothing in history yet."
    override val noMatchingHistory = "Nothing in history matches."
    override val today = "Today"
    override val yesterday = "Yesterday"
    override val removeFromHistory = "Remove from history"

    override val emptyNote = "Empty note"
    override val fileLabel = "file"
    override val renameCard = "Edit"
    override val cardNamePrompt = "Card title"
    override val renameHeading = "Edit card"
    override val renameShowUrl = "Show address"
    override val renameHideUrl = "Hide address"
    override val renameUrlPrompt = "Address"

    override val newNote = "New note"
    override val editNote = "Edit note"
    override val viewNote = "Note"
    override val editNoteAction = "Edit"
    override val sectionDescription = "Group description"
    override val titlePlaceholder = "Title"
    override val noteDefaultTitle = "Note"
    override val notePlaceholder = "Start writing…"
    override val toolBold = "Bold"
    override val toolItalic = "Italic"
    override val toolHighlight = "Highlight"
    override val toolCode = "Code"
    override val toolLink = "Link"
    override val toolHeading = "Heading"
    override val toolList = "Bulleted list"
    override val toolListLabel = "List"
    override val highlightPlaceholder = "highlight"
    override val codePlaceholder = "code"
    override val linkUrlPrompt = "Link URL"
    override val draftRestored = "Unsaved draft restored"
    override val discardDraft = "Reset"

    override val addFile = "Add file"
    override val chooseFile = "Choose a file…"
    override val download = "Download"
    override val fileDefaultTitle = "File"
    override fun noPreviewFor(mime: String) = "No inline preview for $mime — use Download."

    override val appearance = "Appearance"
    override val theme = "Theme"
    override val themeHint = "Follow the system, or force day/night."
    override val themeAuto = "Auto"
    override val themeLight = "Light"
    override val themeDark = "Dark"
    override val accentColor = "Accent color"
    override val accentColorHint = "The brand color behind buttons, selection, and highlights."
    override val accentBlue = "Blue"
    override val accentPurple = "Purple"
    override val accentGreen = "Green"
    override val accentOrange = "Orange"
    override val accentRose = "Rose"
    override val language = "Language"
    override val languageHint = "The language of the interface."
    override val cardUrls = "Card addresses"
    override val cardUrlsHint = "Whether a link card shows its address under the title."
    override val cardUrlsShow = "Show"
    override val cardUrlsHide = "Hide"
    override val groupsView = "Sections view"
    override val groupsViewHint =
        "Show a collection's sections one under another, or as folders that open where they stand."
    override val groupsViewList = "List"
    override val groupsViewFolders = "Folders"
    override val folderBack = "Back to folders"
    override val swapSidebars = "Sidebar order"
    override val swapSidebarsHint = "Which side the sections sidebar sits on, vs. the tabs/history one."
    override val swapSidebarsLeft = "Sections left"
    override val swapSidebarsRight = "Sections right"
    override val tabsCardView = "Tabs view"
    override val tabsCardViewHint =
        "Show open tabs as a list, or as a grid of cards the same width as the middle pane."
    override val tabsCardViewList = "List"
    override val tabsCardViewCards = "Cards"

    override val startupSection = "Startup"
    override val startView = "On open"
    override val startViewHint = "Which collection is shown when stramus opens. A collection behind a " +
        "PIN is never it — every reload locks its section back up."
    override val startViewLast = "Last opened"
    override val startViewFirst = "First collection"

    override val dataSection = "Data"

    override val export = "Export"
    override val exportHint = "Download every saved link across all collections. A section still behind " +
        "its PIN is left out."
    override val exportCsv = "Export CSV"
    override val exportBookmarks = "Export bookmarks"

    override val import = "Import"
    override val importHint = "Bring in a bookmarks file from any browser, or a CSV exported here. " +
        "Folders become sections, collections and groups; a link already saved where it would " +
        "land is left alone."
    override val importFile = "Choose a file"
    override val importedTitle = "Imported"
    override fun importDone(added: Int, skipped: Int) = when (skipped) {
        0 -> "Imported $added links."
        else -> "Imported $added links; $skipped were already saved."
    }
    override val importNothing = "No links to import in that file."

    override val sortTitle = "Title A–Z"
    override val sortUrl = "URL"
    override val sortDomain = "Domain"
    override val sortNewest = "Newest first"
    override val sortOldest = "Oldest first"

    override val account = "Account"
    override val accountSignedOutHint = "Sign in to keep your collections on every browser you use. Everything works without an account — it just stays on this machine."
    override val signInAccount = "Sign in"
    override val signOut = "Sign out"
    override val syncNow = "Sync now"
    override fun syncedAt(time: String) = "Synced at $time"
    override fun conflictCopies(count: Int) =
        if (count == 1) "A note was edited on two devices at once. Both versions were kept."
        else "$count notes were edited on two devices at once. Both versions of each were kept."
    override val joinAccountTitle = "This browser already has collections"
    override val joinAccountHint = "You can add them to the account, or leave them behind and take what the account already holds."
    override val joinAccountKeep = "Add them to the account"
    override val joinAccountDiscard = "Use the account's collections"
    override val exportAccountData = "Download my data"
    override val exportAccountDataHint = "Every row the server holds about this account, as JSON."
    override val exportAccountDataFailed = "Could not download the export."
    override val deleteAccount = "Delete account"
    override val deleteAccountHint = "Erases everything the server holds. What is on this machine stays."
    override val deleteAccountConfirm = "Delete the account and everything the server holds? This cannot be undone."
    override val syncUsage = "Sync browsing statistics"
    override val syncUsageHint = "Which pages you open, and how often — what the search ranks by. Off means it stays on this machine."
    override val optionOn = "On"
    override val optionOff = "Off"
    override val signInWithGoogle = "Continue with Google"
    override val signInUnavailable = "Signing in is not set up in this build. The app works without an account, as it always has."
    override val serverUnavailable = "The server is not answering right now. This needs it — try again once it's back."

    override val onboardingSignInTitle = "Sign in to sync everywhere"
    override val onboardingSignInBody = "Your collections stay on this device until you sign in — then they follow you to every browser you use, automatically."
    override val onboardingSkip = "Skip for now"
    override val onboardingInstallTitle = "Install the extension"
    override val onboardingInstallBody = "stramus works right here in this tab, but the extension adds its own new-tab page, one-click tab saving, and a search box over your open tabs and history — the full experience."
    override val onboardingInstallCta = "Install from Chrome Web Store"
    override val onboardingContinueInBrowser = "Continue in the browser"
    override val onboardingOrganizeTitle = "Collections, grouped into sections"
    override val onboardingOrganizeBody = "The sidebar holds your sections; each one opens onto its collections. Drag a tab, a link or a file into any collection to save it there — nothing leaves your device unless you ask it to."
    override val onboardingSearchTitle = "One search for everything"
    override val onboardingSearchBody = "The search box up top finds saved cards, open tabs and browsing history all at once — start typing, and stramus looks everywhere so you don't have to."
    override val onboardingBack = "Back"
    override val onboardingNext = "Next"
    override val onboardingGetStarted = "Get started"

    override val seed = StoreSeed(
        sectionTitle = "Main",
        collectionTitle = "Getting started",
        noteTitle = "How to use stramus",
        // Each bullet is one line: the markdown here is the one `Markdown.kt` reads, and a wrapped
        // line there ends the list rather than continuing it.
        noteBody = """
            # Welcome to stramus

            The sidebar on the left holds **sections**, a section holds **collections**, and a collection holds cards — links, files, and notes like this one.

            ## Saving a page
            - Drag a tab from the right sidebar onto a collection, or use **⤓ Save open tabs** for a whole window at once.
            - Hover a section's header and press its **+** to add a pasted address, a note, or a file straight into that section.
            - **+ Group** splits a large collection into groups — drop a card on one to move it in.

            ## Finding a page
            - The search box at the top looks through all of it at once: what you saved, the tabs you have open, and where you have been.
            - Type an address to open it, or a question to ask the browser's built-in model.
            - ↑↓ to choose, Enter to open, Esc to close.

            ## Keeping it in order
            - **Protect with a PIN**: a locked section does not even name its collections, and locks itself again when you step away.
            - **🔒 Read-only** guards a collection you are done with against a slip of the hand.
            - Settings hold the theme, the language, and an export of everything to CSV or bookmarks.

            Rename this collection, or delete this note — all of it is yours now.
        """.trimIndent(),
    )
}

private object RuStrings : Strings {
    override val on = "Вкл"
    override val off = "Выкл"
    override val experimental = "эксперимент"
    override val settings = "Настройки"
    override val close = "Закрыть"
    override val cancel = "Отмена"
    override val save = "Сохранить"
    override val about = "О приложении"
    override fun aboutVersion(version: String) = "Версия $version"
    override fun aboutCopyright(year: String) = "© $year Stramus"
    override val aboutHomepage = "stramus.space"

    override val expandSidebar = "Развернуть панель"
    override val collapseSidebar = "Свернуть панель"
    override val newSection = "+ Новый раздел"
    override val sectionNamePrompt = "Название раздела"
    override val sectionNameDefault = "Новый раздел"
    override val collectionNamePrompt = "Название коллекции"
    override val collectionNameDefault = "Новая коллекция"
    override val renameHint = "Клик — свернуть, двойной клик — переименовать, перетащить — поменять порядок"
    override val renameCollectionHint = "Двойной клик — переименовать"
    override val untitled = "Без названия"

    override val newSectionHint = "Создать раздел в боковой панели"
    override val addCollectionHint = "Добавить коллекцию в этот раздел"
    override val deleteSectionHint = "Удалить раздел вместе с его коллекциями"
    override val deleteCollectionHint = "Удалить коллекцию вместе с её карточками"
    override val addCardSectionHint = "Добавить секцию в эту коллекцию"
    override val deleteCardSectionHint = "Удалить секцию — её карточки останутся в коллекции, без секции"
    override val addCardHint = "Добавить ссылку — или, из меню, заметку либо файл"
    override val deleteCardHint = "Удалить карточку"
    override fun openAllHint(count: Int) = "Открыть все карточки ($count) в новых вкладках"

    override fun confirmDeleteSection(title: String, cards: Int) =
        "В разделе «$title» и его коллекциях сохранено элементов: $cards. Удалить раздел?"
    override fun confirmDeleteCollection(title: String, cards: Int) =
        "В коллекции «$title» сохранено элементов: $cards. Удалить коллекцию?"
    override fun confirmDeleteCardSection(title: String, cards: Int) =
        "В секции «$title» карточек: $cards. Удалить секцию? Карточки останутся — без секции."
    override fun deletedSection(title: String) = "Раздел «$title» удалён"
    override fun deletedCollection(title: String) = "Коллекция «$title» удалена"
    override fun deletedCardSection(title: String) = "Секция «$title» удалена"
    override fun deletedCard(title: String) = "«$title» удалена"
    override fun movedCard(title: String) = "«$title» перенесена"
    override val sortedCards = "Карточки отсортированы"
    override val undo = "Вернуть"

    override val searchPlaceholder = "Поиск, адрес или вопрос…"

    override val hitsTopSites = "Часто открываемые"
    override val hitsTabs = "Открытые вкладки"
    override val hitsCards = "Сохранённое"
    override val hitsHistory = "История"
    override val hitsSites = "Сайты"
    override val hitsCollections = "Коллекции"

    override val hitSwitchToTab = "Перейти"
    override val hitOpenCollection = "Открыть"
    override val hitAskAi = "Спросить"

    // Поисковик — тот, что выбран в браузере, поэтому здесь он не назван.
    override fun hitWebSearch(query: String) = "Искать в вебе: «$query»"
    override fun hitOpenUrl(query: String) = "Открыть $query"
    override fun hitAskAiRow(assistant: String, query: String) = "Спросить $assistant: «$query»"

    override val forgetSite = "Больше не предлагать эту страницу"
    override val searchHints = "↑↓ выбрать · Enter открыть · Alt+Enter — поиск в вебе · ⌘/Ctrl+Enter — все результаты · Esc закрыть"

    override val aiChip = "ИИ"
    override val aiHeading = "Помощник"
    override val aiEmpty = "Спросите про открытую коллекцию — или про что угодно ещё."
    override val aiPlaceholder = "Спросить ещё…"
    override val aiSend = "Спросить"
    override val aiThinking = "Думает…"
    override val aiCopy = "Скопировать"
    override val aiSaveNote = "Сохранить заметкой"
    override val aiUnavailable = "В этом браузере встроенная модель недоступна."
    override val aiFailed = "Модель не смогла ответить."
    override fun aiDownloading(percent: Int) = "Модель скачивается — $percent%. Это происходит один раз."
    override val aiSystemPrompt = "Ты — помощник внутри stramus, менеджера закладок и вкладок. " +
        "Отвечай кратко и по делу, на языке вопроса. Markdown приветствуется."

    override val aiTriageSetting = "Разбирать вкладки встроенной моделью"
    override val aiTriageSettingHint = "Добавляет кнопку к окну вкладок: модель читает их и предлагает " +
        "коллекцию для каждой — вы проверяете до того, как что-либо сохранится. Всё остаётся на этой " +
        "машине. На большом окне занимает минуту-другую, а то, что не смогла определить, оставляет вам."
    override val triageTabs = "Разобрать по коллекциям"
    override val triageHeading = "Разобрать вкладки по коллекциям"
    override val triageSummaryHeading = "О чём была эта сессия"
    override val triageSummaryTitle = "Итог сессии"
    override val triageNew = "новая"
    override fun triageNewHint(section: String) = "Такой коллекции ещё нет — она будет создана в разделе «$section»."
    override val triageNewSectionHint = "Такой секции в этой коллекции ещё нет — она будет создана."
    override val triageGroupHint = "В каком разделе сайдбара будет создана новая коллекция"
    override val triageSectionHint = "В какую секцию коллекции попадёт вкладка"
    override val triageNoSection = "Без секции"
    override fun triageProgress(done: Int, total: Int) = "Разбирает сайты — $done из $total…"
    override val triageUnsorted = "Не разобрано"
    override val triageUnsortedHint =
        "Про эти вкладки модель ничего не сказала. Выберите коллекцию или оставьте их открытыми."
    override val triageSkip = "Не сохранять"
    override val triageMoveHint = "В какую коллекцию попадёт вкладка"
    override val triageDuplicate = "уже сохранено"
    override val triageDuplicateHint = "Эта страница уже есть в коллекции. Отметьте, чтобы сохранить ещё раз."
    override fun triageRelated(site: String, count: Int) = "С $site уже сохранено ($count):"
    override fun triageApply(count: Int, closesTabs: Boolean) =
        if (closesTabs) "Сохранить ($count) и закрыть" else "Сохранить ($count)"
    override val aiTriageSystemPrompt = "Ты раскладываешь открытые вкладки браузера по коллекциям " +
        "пользователя. Тебе дают вкладки и список существующих коллекций. Для каждой вкладки назови одну " +
        "коллекцию, которой он принадлежит: переиспользуй существующее название везде, где сайт в него " +
        "укладывается, и придумывай новое короткое название (одно-два слова) только если не подходит " +
        "ни одно. Внутри коллекции можешь указать секцию — тоже переиспользуя существующие. Вкладки " +
        "одного сайта могут относиться к разным коллекциям. В ответе — только требуемый JSON."

    override val aiSection = "ИИ"
    override val aiAssistant = "Помощник"
    override val aiAssistantHint = "Кто отвечает на вопрос, заданный из строки поиска."
    override val aiProviderLocal = "Локальный"
    override fun aiWebChatHint(assistant: String) =
        "Вопрос откроет $assistant в этой вкладке — уже отправленным сообщением. Он уходит на серверы " +
            "сервиса, в отличие от локальной модели, которая отвечает здесь и ничего наружу не отправляет."

    override val aiModel = "Модель"
    override val aiModelReadyHint = "Встроенная модель браузера. Работает на этой машине — без ключей, ничего наружу не уходит."
    override val aiModelDownloadableHint = "Браузер скачает её при первом вопросе — несколько сотен мегабайт, один раз."
    override val aiModelDownloadingHint = "Браузер скачивает её прямо сейчас."
    override val aiModelNone = "Недоступна"
    override val aiModelNoneHint =
        "Этот браузер не даёт странице встроенную модель, поэтому поиск и не предлагает её спросить. " +
            "В Chrome она есть у расширения; обычной странице нужны флаги."
    override fun aiModelUnsupported(name: String) = "$name — недоступна"
    override val aiModelUnsupportedHint =
        "Модель у браузера есть, но запустить её здесь он не может: нужно ~22 ГБ свободного места на " +
            "диске с профилем Chrome и видеопамять больше 4 ГБ."

    override fun resultsFor(query: String) = "Результаты по запросу «$query»"
    override val noMatchingLinks = "Ничего не найдено."
    override val createCollectionToStart = "Создайте коллекцию, чтобы сохранять ссылки."
    override val sortLinks = "Отсортировать карточки этой секции"
    override val sortMenuTitle = "Сортировать"
    override val addCardSection = "Секция"
    override val pasteUrl = "Вставьте ссылку"
    override val addLinkItem = "Ссылка"
    override val addNoteItem = "Заметка"
    override val addFileItem = "Файл"
    override val noLinksYet = "Пока нет ссылок — добавьте одну или перетащите сюда."
    override val ungrouped = "Без секции"
    override val dragLinksHere = "Перетащите сюда ссылки или файлы."
    override val editDescription = "Изменить описание"

    override fun filesTooLarge(names: List<String>, maxMb: Int) =
        "Не сохранено — больше $maxMb МБ: ${names.joinToString(", ")}"

    override val protectSection = "Защитить PIN-кодом"
    override val sectionProtection = "Защита раздела"
    override val changePin = "Изменить PIN-код"
    override val removeProtection = "Снять защиту"
    override val lockNow = "Запереть сейчас"
    override val lockedSection = "Защищён PIN-кодом"
    override val unlockedSection = "Открыт — нажмите, чтобы запереть"
    override val enterPinToView = "Введите PIN-код, чтобы увидеть коллекции раздела."
    override val pinPlaceholder = "PIN-код"
    override val unlock = "Разблокировать"
    override val wrongPin = "Неверный PIN-код."
    override val setPinHeading = "Защитить раздел"
    override val changePinHeading = "Изменить PIN-код"
    override val newPinLabel = "Новый PIN-код"
    override val repeatPinLabel = "Повторите PIN-код"
    override val pinMismatch = "PIN-коды не совпадают."
    override fun pinTooShort(min: Int) = "PIN-код должен быть не короче $min цифр."
    override val pinNote = "PIN-код скрывает раздел целиком: пока он не введён, не видно даже названий " +
        "коллекций, а их карточки не попадают в поиск и экспорт. Забытый PIN-код восстановить нельзя."

    override val makeReadOnlyHint = "Сделать только для чтения: ничего нельзя будет добавить, изменить " +
        "или удалить."
    override val allowEditing = "Разрешить правку"
    override val allowEditingHint = "Снова разрешить правку."
    override val readOnlyBadge = "только чтение"
    override val readOnlyHint = "Только чтение: ничего нельзя добавить, изменить или удалить."

    override val security = "Безопасность"
    override val autoLock = "Авто-блокировка"
    override val autoLockHint = "Запирать открытые разделы снова после этого времени без активности."
    override val autoLockNever = "Никогда"
    override fun autoLockMinutes(minutes: Int) = "$minutes мин"

    override val openTabs = "Открытые вкладки"
    override val showTabs = "Показать открытые вкладки"
    override val hideTabs = "Скрыть открытые вкладки"
    override val noOpenTabs = "Нет открытых вкладок."
    override val searchTabs = "Поиск по вкладкам…"
    override val noMatchingTabs = "Вкладки не найдены."
    override val thisWindow = "Это окно"
    override fun windowLabel(number: Int) = "Окно $number"
    override val closeTab = "Закрыть вкладку"
    override val sortTabs = "Отсортировать вкладки этого окна"
    override fun saveTabsHint(count: Int, closing: Boolean) =
        "Сохранить вкладки этого окна ($count) в открытую коллекцию, без секции — " +
            if (closing) "и закрыть их" else "и оставить их открытыми"

    override fun confirmSaveTabs(count: Int, collection: String, closing: Boolean) =
        if (closing) "Сохранить вкладки этого окна ($count) в «$collection» и закрыть их?"
        else "Сохранить вкладки этого окна ($count) в «$collection»?"

    override val tabsSection = "Вкладки"
    override val closeSavedTabs = "После сохранения вкладок"
    override val closeSavedTabsHint =
        "Что делать с вкладками окна после того, как они сохранены в коллекцию."
    override val closeSavedTabsClose = "Закрывать"
    override val closeSavedTabsKeep = "Оставлять открытыми"

    override val paneTabs = "Вкладки"
    override val paneHistory = "История"
    override val searchHistory = "Поиск по истории…"
    override val noHistory = "История пуста."
    override val noMatchingHistory = "В истории ничего не найдено."
    override val today = "Сегодня"
    override val yesterday = "Вчера"
    override val removeFromHistory = "Удалить из истории"

    override val emptyNote = "Пустая заметка"
    override val fileLabel = "файл"
    override val renameCard = "Изменить"
    override val cardNamePrompt = "Название карточки"
    override val renameHeading = "Изменить карточку"
    override val renameShowUrl = "Показать адрес"
    override val renameHideUrl = "Скрыть адрес"
    override val renameUrlPrompt = "Адрес"

    override val newNote = "Новая заметка"
    override val editNote = "Изменить заметку"
    override val viewNote = "Заметка"
    override val editNoteAction = "Изменить"
    override val sectionDescription = "Описание секции"
    override val titlePlaceholder = "Заголовок"
    override val noteDefaultTitle = "Заметка"
    override val notePlaceholder = "Начните писать…"
    override val toolBold = "Жирный"
    override val toolItalic = "Курсив"
    override val toolHighlight = "Выделение"
    override val toolCode = "Код"
    override val toolLink = "Ссылка"
    override val toolHeading = "Заголовок"
    override val toolList = "Маркированный список"
    override val toolListLabel = "Список"
    override val highlightPlaceholder = "выделение"
    override val codePlaceholder = "код"
    override val linkUrlPrompt = "Адрес ссылки"
    override val draftRestored = "Восстановлен несохранённый черновик"
    override val discardDraft = "Сбросить"

    override val addFile = "Добавить файл"
    override val chooseFile = "Выберите файл…"
    override val download = "Скачать"
    override val fileDefaultTitle = "Файл"
    override fun noPreviewFor(mime: String) = "Нет предпросмотра для $mime — используйте «Скачать»."

    override val appearance = "Оформление"
    override val theme = "Тема"
    override val themeHint = "Следовать системе или выбрать день/ночь."
    override val themeAuto = "Авто"
    override val themeLight = "День"
    override val themeDark = "Ночь"
    override val accentColor = "Цвет акцента"
    override val accentColorHint = "Основной цвет кнопок, выделения и подсветки."
    override val accentBlue = "Синий"
    override val accentPurple = "Фиолетовый"
    override val accentGreen = "Зелёный"
    override val accentOrange = "Оранжевый"
    override val accentRose = "Розовый"
    override val language = "Язык"
    override val languageHint = "Язык интерфейса."
    override val cardUrls = "Адреса на карточках"
    override val cardUrlsHint = "Показывать ли под заголовком карточки-ссылки её адрес."
    override val cardUrlsShow = "Показывать"
    override val cardUrlsHide = "Скрывать"
    override val groupsView = "Вид секций"
    override val groupsViewHint =
        "Показывать секции коллекции одну под другой или папками, которые раскрываются на месте."
    override val groupsViewList = "Списком"
    override val groupsViewFolders = "Папками"
    override val folderBack = "Назад к папкам"
    override val swapSidebars = "Порядок боковых панелей"
    override val swapSidebarsHint = "С какой стороны сайдбар секций, а с какой — сайдбар вкладок/истории."
    override val swapSidebarsLeft = "Секции слева"
    override val swapSidebarsRight = "Секции справа"
    override val tabsCardView = "Вид вкладок"
    override val tabsCardViewHint =
        "Показывать открытые вкладки списком или сеткой карточек той же ширины, что и средняя колонка."
    override val tabsCardViewList = "Список"
    override val tabsCardViewCards = "Карточки"

    override val startupSection = "Запуск"
    override val startView = "При открытии"
    override val startViewHint = "Какая коллекция показывается при открытии stramus. Коллекция под " +
        "PIN-кодом не открывается никогда — при каждой перезагрузке её раздел снова запирается."
    override val startViewLast = "Последняя открытая"
    override val startViewFirst = "Главная"

    override val dataSection = "Данные"

    override val export = "Экспорт"
    override val exportHint = "Скачайте все сохранённые ссылки из всех коллекций. Разделы, PIN-код " +
        "которых не введён, в экспорт не попадают."
    override val exportCsv = "Экспорт CSV"
    override val exportBookmarks = "Экспорт закладок"

    override val import = "Импорт"
    override val importHint = "Загрузите файл закладок из любого браузера или CSV, экспортированный " +
        "здесь. Папки станут разделами, коллекциями и секциями; ссылка, которая уже сохранена там, " +
        "куда попала бы, останется одна."
    override val importFile = "Выбрать файл"
    override val importedTitle = "Импорт"
    override fun importDone(added: Int, skipped: Int) = when (skipped) {
        0 -> "Импортировано ссылок: $added."
        else -> "Импортировано ссылок: $added. Уже было сохранено: $skipped."
    }
    override val importNothing = "В этом файле нет ссылок для импорта."

    override val sortTitle = "По названию"
    override val sortUrl = "По адресу"
    override val sortDomain = "По домену"
    override val sortNewest = "Сначала новые"
    override val sortOldest = "Сначала старые"

    override val account = "Аккаунт"
    override val accountSignedOutHint = "Войдите, чтобы коллекции были во всех браузерах, которыми вы пользуетесь. Без аккаунта всё работает точно так же — просто остаётся на этой машине."
    override val signInAccount = "Войти"
    override val signOut = "Выйти"
    override val syncNow = "Синхронизировать"
    override fun syncedAt(time: String) = "Синхронизировано в $time"
    override fun conflictCopies(count: Int) =
        if (count == 1) "Заметку правили на двух устройствах сразу. Обе версии сохранены."
        else "Заметок, которые правили на двух устройствах сразу: $count. Обе версии каждой сохранены."
    override val joinAccountTitle = "В этом браузере уже есть коллекции"
    override val joinAccountHint = "Их можно добавить в аккаунт — или оставить здесь и взять то, что в аккаунте уже есть."
    override val joinAccountKeep = "Добавить в аккаунт"
    override val joinAccountDiscard = "Взять коллекции из аккаунта"
    override val exportAccountData = "Скачать мои данные"
    override val exportAccountDataHint = "Всё, что сервер хранит об этом аккаунте, в формате JSON."
    override val exportAccountDataFailed = "Не удалось скачать экспорт."
    override val deleteAccount = "Удалить аккаунт"
    override val deleteAccountHint = "Стирает всё, что хранит сервер. То, что на этой машине, остаётся."
    override val deleteAccountConfirm = "Удалить аккаунт и всё, что хранит сервер? Это не отменить."
    override val syncUsage = "Синхронизировать статистику посещений"
    override val syncUsageHint = "Какие страницы вы открываете и как часто — то, по чему ранжируется поиск. Выключено — остаётся на этой машине."
    override val optionOn = "Вкл"
    override val optionOff = "Выкл"
    override val signInWithGoogle = "Продолжить с Google"
    override val signInUnavailable = "В этой сборке вход не настроен. Приложение работает и без аккаунта — ровно как раньше."
    override val serverUnavailable = "Сервер сейчас не отвечает. Для этого он нужен — попробуйте, когда он снова будет доступен."

    override val onboardingSignInTitle = "Синхронизируйте коллекции везде"
    override val onboardingSignInBody = "Пока вы не вошли, коллекции остаются только на этом устройстве. Войдите — и они автоматически появятся в любом браузере, которым вы пользуетесь."
    override val onboardingSkip = "Пропустить"
    override val onboardingInstallTitle = "Установите расширение"
    override val onboardingInstallBody = "stramus работает прямо в этой вкладке, но расширение добавляет собственную страницу новой вкладки, сохранение вкладок в один клик и поиск по открытым вкладкам и истории — весь опыт целиком."
    override val onboardingInstallCta = "Установить из Chrome Web Store"
    override val onboardingContinueInBrowser = "Продолжить в браузере"
    override val onboardingOrganizeTitle = "Коллекции, сгруппированные в разделы"
    override val onboardingOrganizeBody = "Боковая панель содержит ваши разделы; каждый открывается в свои коллекции. Перетащите вкладку, ссылку или файл в любую коллекцию, чтобы сохранить их — ничего не покидает ваше устройство, если вы сами этого не попросите."
    override val onboardingSearchTitle = "Один поиск для всего"
    override val onboardingSearchBody = "Строка поиска сверху находит сохранённые карточки, открытые вкладки и историю браузера одновременно — начните печатать, и stramus проверит всё за вас."
    override val onboardingBack = "Назад"
    override val onboardingNext = "Далее"
    override val onboardingGetStarted = "Начать"

    override val seed = StoreSeed(
        sectionTitle = "Главный",
        collectionTitle = "Начало работы",
        noteTitle = "Как пользоваться stramus",
        // Каждый пункт списка — одна строка: этот markdown читает `Markdown.kt`, и перенос строки
        // внутри пункта не продолжает список, а заканчивает его.
        noteBody = """
            # Добро пожаловать в stramus

            Слева — **разделы**, в разделе — **коллекции**, в коллекции — карточки: ссылки, файлы и заметки вроде этой.

            ## Как сохранить страницу
            - Перетащите вкладку из правой панели в коллекцию или нажмите **⤓ Сохранить вкладки**, чтобы убрать целое окно разом.
            - Наведите курсор на заголовок раздела и нажмите **+** — вставленный адрес, заметка или файл попадут прямо в этот раздел.
            - **+ Секция** делит большую коллекцию на группы: перетащите карточку на секцию, и она окажется в ней.

            ## Как найти страницу
            - Поиск сверху ищет сразу везде: в сохранённом, в открытых вкладках и в истории.
            - Введите адрес, чтобы открыть его, или вопрос — чтобы спросить встроенную модель браузера.
            - ↑↓ — выбрать, Enter — открыть, Esc — закрыть.

            ## Как навести порядок
            - **Защитить PIN-кодом**: закрытый раздел не показывает даже названия коллекций и запирается снова, когда вы отходите.
            - **🔒 Только чтение** бережёт законченную коллекцию от случайного движения руки.
            - В настройках — тема, язык и экспорт всего сохранённого в CSV или закладки.

            Переименуйте коллекцию или удалите эту заметку — теперь здесь всё ваше.
        """.trimIndent(),
    )
}

private object FrStrings : Strings {
    override val on = "Activé"
    override val off = "Désactivé"
    override val experimental = "expérimental"
    override val settings = "Paramètres"
    override val close = "Fermer"
    override val cancel = "Annuler"
    override val save = "Enregistrer"
    override val about = "À propos"
    override fun aboutVersion(version: String) = "Version $version"
    override fun aboutCopyright(year: String) = "© $year Stramus"
    override val aboutHomepage = "stramus.space"

    override val expandSidebar = "Déployer le panneau"
    override val collapseSidebar = "Réduire le panneau"
    override val newSection = "+ Nouvelle section"
    override val sectionNamePrompt = "Nom de la section"
    override val sectionNameDefault = "Nouvelle section"
    override val collectionNamePrompt = "Nom de la collection"
    override val collectionNameDefault = "Nouvelle collection"
    override val renameHint = "Cliquer pour replier, double-cliquer pour renommer, glisser pour réordonner"
    override val renameCollectionHint = "Double-cliquer pour renommer"
    override val untitled = "Sans titre"

    override val newSectionHint = "Ajouter une section au panneau"
    override val addCollectionHint = "Ajouter une collection à cette section"
    override val deleteSectionHint = "Supprimer cette section et les collections qu'elle contient"
    override val deleteCollectionHint = "Supprimer cette collection et ses cartes"
    override val addCardSectionHint = "Ajouter un groupe à cette collection"
    override val deleteCardSectionHint = "Supprimer ce groupe — ses cartes restent dans la collection, sans groupe"
    override val addCardHint = "Ajouter un lien — ou, depuis le menu, une note ou un fichier"
    override val deleteCardHint = "Supprimer cette carte"
    override fun openAllHint(count: Int) = "Ouvrir les $count cartes dans de nouveaux onglets"

    override fun confirmDeleteSection(title: String, cards: Int) =
        "« $title » et ses collections contiennent $cards éléments enregistrés. Supprimer la section ?"
    override fun confirmDeleteCollection(title: String, cards: Int) =
        "« $title » contient $cards éléments enregistrés. Supprimer la collection ?"
    override fun confirmDeleteCardSection(title: String, cards: Int) =
        "« $title » contient $cards cartes. Supprimer le groupe ? Les cartes restent, sans groupe."
    override fun deletedSection(title: String) = "Section « $title » supprimée"
    override fun deletedCollection(title: String) = "Collection « $title » supprimée"
    override fun deletedCardSection(title: String) = "Groupe « $title » supprimé"
    override fun deletedCard(title: String) = "« $title » supprimée"
    override fun movedCard(title: String) = "« $title » déplacée"
    override val sortedCards = "Cartes triées"
    override val undo = "Annuler"

    override val searchPlaceholder = "Rechercher, saisir une adresse, ou poser une question…"

    override val hitsTopSites = "Souvent ouverts"
    override val hitsTabs = "Onglets ouverts"
    override val hitsCards = "Enregistré"
    override val hitsHistory = "Historique"
    override val hitsSites = "Sites"
    override val hitsCollections = "Collections"

    override val hitSwitchToTab = "Aller à"
    override val hitOpenCollection = "Ouvrir"
    override val hitAskAi = "Demander"

    // Le moteur est celui du navigateur — quel qu'il soit — il n'est donc pas nommé ici.
    override fun hitWebSearch(query: String) = "Rechercher « $query » sur le web"
    override fun hitOpenUrl(query: String) = "Ouvrir $query"
    override fun hitAskAiRow(assistant: String, query: String) = "Demander à $assistant : « $query »"

    override val forgetSite = "Ne plus suggérer cette page"
    override val searchHints = "↑↓ choisir · Entrée ouvrir · Alt+Entrée rechercher sur le web · ⌘/Ctrl+Entrée tous les résultats · Échap fermer"

    override val aiChip = "IA"
    override val aiHeading = "Assistant"
    override val aiEmpty = "Posez une question sur la collection ouverte, ou sur n'importe quoi d'autre."
    override val aiPlaceholder = "Poser une autre question…"
    override val aiSend = "Demander"
    override val aiThinking = "Réflexion…"
    override val aiCopy = "Copier"
    override val aiSaveNote = "Enregistrer comme note"
    override val aiUnavailable = "Ce navigateur n'a pas de modèle intégré disponible."
    override val aiFailed = "Le modèle n'a pas pu répondre."
    override fun aiDownloading(percent: Int) = "Téléchargement du modèle — $percent %. Cela n'arrive qu'une fois."
    override val aiSystemPrompt = "Tu es l'assistant intégré à stramus, un gestionnaire de favoris et d'onglets. " +
        "Réponds brièvement et précisément, dans la langue de la question. Le Markdown est bienvenu."

    override val aiTriageSetting = "Trier les onglets avec le modèle intégré"
    override val aiTriageSettingHint = "Ajoute un bouton à une fenêtre d'onglets : le modèle les lit et propose " +
        "une collection pour chacun, à vérifier avant que quoi que ce soit ne soit enregistré. Tout reste sur " +
        "cet ordinateur. Cela prend une minute ou deux sur une grande fenêtre, et laisse de côté ce qu'il ne peut pas classer."
    override val triageTabs = "Trier en collections"
    override val triageHeading = "Trier les onglets en collections"
    override val triageSummaryHeading = "De quoi parlait cette session"
    override val triageSummaryTitle = "Résumé de la session"
    override val triageNew = "nouvelle"
    override fun triageNewHint(section: String) = "Cette collection n'existe pas encore — elle sera créée dans « $section »."
    override val triageNewSectionHint = "Ce groupe n'existe pas encore dans cette collection — il sera créé."
    override val triageGroupHint = "Dans quelle section du panneau cette nouvelle collection sera créée"
    override val triageSectionHint = "Dans quel groupe de la collection cet onglet ira"
    override val triageNoSection = "Aucun groupe"
    override fun triageProgress(done: Int, total: Int) = "Tri des sites — $done sur $total…"
    override val triageUnsorted = "Non triés"
    override val triageUnsortedHint = "Le modèle n'a rien proposé pour ceux-ci. Choisissez une collection, ou laissez-les ouverts."
    override val triageSkip = "Ne pas enregistrer"
    override val triageMoveHint = "Dans quelle collection cet onglet ira"
    override val triageDuplicate = "déjà enregistré"
    override val triageDuplicateHint = "Cette page est déjà dans une collection. Cochez-la pour l'enregistrer à nouveau."
    override fun triageRelated(site: String, count: Int) = "Déjà enregistré depuis $site ($count) :"
    override fun triageApply(count: Int, closesTabs: Boolean) =
        if (closesTabs) "Enregistrer ($count) et fermer" else "Enregistrer ($count)"
    override val aiTriageSystemPrompt = "Tu tries les onglets ouverts d'un utilisateur dans ses collections. " +
        "On te donne les onglets et les collections existantes. Pour chaque onglet, réponds avec l'unique " +
        "collection à laquelle il appartient — réutilise un nom existant partout où l'onglet lui correspond, " +
        "et n'invente un nom court (un ou deux mots) que s'il n'en trouve aucun. Dans une collection, tu peux " +
        "nommer un groupe, en réutilisant aussi les groupes existants. Des onglets d'un même site peuvent " +
        "appartenir à des collections différentes. Réponds uniquement avec le JSON demandé."

    override val aiSection = "IA"
    override val aiAssistant = "Assistant"
    override val aiAssistantHint = "Qui répond à une question posée depuis la barre de recherche."
    override val aiProviderLocal = "Sur l'appareil"
    override fun aiWebChatHint(assistant: String) =
        "La question ouvre $assistant dans cet onglet, déjà posée. Elle est envoyée aux serveurs de $assistant — " +
            "contrairement au modèle intégré, qui répond ici et garde tout sur cet ordinateur."

    override val aiModel = "Modèle"
    override val aiModelReadyHint = "Le modèle intégré du navigateur. Fonctionne sur cet ordinateur — sans clé, rien n'en sort."
    override val aiModelDownloadableHint = "Le navigateur le téléchargera à la première question — quelques centaines de mégaoctets, une seule fois."
    override val aiModelDownloadingHint = "Le navigateur est en train de le télécharger."
    override val aiModelNone = "Non disponible"
    override val aiModelNoneHint =
        "Ce navigateur ne fournit pas de modèle intégré à la page, donc la recherche ne propose pas de le questionner. " +
            "Dans Chrome, il est disponible pour l'extension ; une simple page web a besoin des drapeaux pour cela."
    override fun aiModelUnsupported(name: String) = "$name — indisponible"
    override val aiModelUnsupportedHint =
        "Le navigateur dispose du modèle mais ne peut pas l'exécuter ici : il faut ~22 Go libres sur le disque " +
            "contenant le profil Chrome, et un GPU avec plus de 4 Go de mémoire."

    override fun resultsFor(query: String) = "Résultats pour « $query »"
    override val noMatchingLinks = "Aucun lien correspondant."
    override val createCollectionToStart = "Créez une collection pour commencer à enregistrer des liens."
    override val sortLinks = "Trier les cartes de ce groupe"
    override val sortMenuTitle = "Trier par"
    override val addCardSection = "Groupe"
    override val pasteUrl = "Coller une URL"
    override val addLinkItem = "Lien"
    override val addNoteItem = "Note"
    override val addFileItem = "Fichier"
    override val noLinksYet = "Pas encore de liens — ajoutez-en un, ou glissez-en un ici."
    override val ungrouped = "Sans groupe"
    override val dragLinksHere = "Glissez des liens ou des fichiers ici."
    override val editDescription = "Modifier la description"

    override fun filesTooLarge(names: List<String>, maxMb: Int) =
        "Non enregistré — plus de $maxMb Mo : ${names.joinToString(", ")}"

    override val protectSection = "Protéger avec un code PIN"
    override val sectionProtection = "Protection de la section"
    override val changePin = "Changer le code PIN"
    override val removeProtection = "Retirer la protection"
    override val lockNow = "Verrouiller maintenant"
    override val lockedSection = "Protégée par un code PIN"
    override val unlockedSection = "Déverrouillée — cliquer pour reverrouiller"
    override val enterPinToView = "Entrez le code PIN pour voir les collections de cette section."
    override val pinPlaceholder = "Code PIN"
    override val unlock = "Déverrouiller"
    override val wrongPin = "Code PIN incorrect."
    override val setPinHeading = "Protéger la section"
    override val changePinHeading = "Changer le code PIN"
    override val newPinLabel = "Nouveau code PIN"
    override val repeatPinLabel = "Répétez le code PIN"
    override val pinMismatch = "Les deux codes PIN ne correspondent pas."
    override fun pinTooShort(min: Int) = "Le code PIN doit comporter au moins $min chiffres."
    override val pinNote = "Le code PIN masque toute la section : ses collections ne sont même pas nommées " +
        "tant qu'il n'est pas entré, et leurs cartes restent hors de la recherche et de l'export. Il n'y a " +
        "aucun moyen de réinitialiser un code PIN oublié."

    override val makeReadOnlyHint = "Rendre en lecture seule : plus rien ne pourra être ajouté, modifié ou supprimé ici."
    override val allowEditing = "Autoriser la modification"
    override val allowEditingHint = "Autoriser à nouveau la modification."
    override val readOnlyBadge = "lecture seule"
    override val readOnlyHint = "Lecture seule : rien ici ne peut être ajouté, modifié ou supprimé."

    override val security = "Sécurité"
    override val autoLock = "Verrouillage automatique"
    override val autoLockHint = "Reverrouille les sections déverrouillées après cette durée d'inactivité."
    override val autoLockNever = "Jamais"
    override fun autoLockMinutes(minutes: Int) = "$minutes min"

    override val openTabs = "Onglets ouverts"
    override val showTabs = "Afficher les onglets ouverts"
    override val hideTabs = "Masquer les onglets ouverts"
    override val noOpenTabs = "Aucun onglet ouvert à enregistrer."
    override val searchTabs = "Rechercher dans les onglets…"
    override val noMatchingTabs = "Aucun onglet correspondant."
    override val thisWindow = "Cette fenêtre"
    override fun windowLabel(number: Int) = "Fenêtre $number"
    override val closeTab = "Fermer l'onglet"
    override val sortTabs = "Trier les onglets de cette fenêtre"
    override fun saveTabsHint(count: Int, closing: Boolean) =
        "Enregistrer les onglets de cette fenêtre ($count) dans la collection ouverte, sans groupe — " +
            if (closing) "et les fermer" else "et les laisser ouverts"

    override fun confirmSaveTabs(count: Int, collection: String, closing: Boolean) =
        if (closing) "Enregistrer les onglets de cette fenêtre ($count) dans « $collection » et les fermer ?"
        else "Enregistrer les onglets de cette fenêtre ($count) dans « $collection » ?"

    override val tabsSection = "Onglets"
    override val closeSavedTabs = "Après avoir enregistré des onglets"
    override val closeSavedTabsHint =
        "Ce qui arrive aux onglets d'une fenêtre une fois enregistrés dans une collection."
    override val closeSavedTabsClose = "Les fermer"
    override val closeSavedTabsKeep = "Les laisser ouverts"

    override val paneTabs = "Onglets"
    override val paneHistory = "Historique"
    override val searchHistory = "Rechercher dans l'historique…"
    override val noHistory = "Rien dans l'historique pour l'instant."
    override val noMatchingHistory = "Rien ne correspond dans l'historique."
    override val today = "Aujourd'hui"
    override val yesterday = "Hier"
    override val removeFromHistory = "Retirer de l'historique"

    override val emptyNote = "Note vide"
    override val fileLabel = "fichier"
    override val renameCard = "Modifier"
    override val cardNamePrompt = "Titre de la carte"
    override val renameHeading = "Modifier la carte"
    override val renameShowUrl = "Afficher l'adresse"
    override val renameHideUrl = "Masquer l'adresse"
    override val renameUrlPrompt = "Adresse"

    override val newNote = "Nouvelle note"
    override val editNote = "Modifier la note"
    override val viewNote = "Note"
    override val editNoteAction = "Modifier"
    override val sectionDescription = "Description du groupe"
    override val titlePlaceholder = "Titre"
    override val noteDefaultTitle = "Note"
    override val notePlaceholder = "Commencez à écrire…"
    override val toolBold = "Gras"
    override val toolItalic = "Italique"
    override val toolHighlight = "Surligner"
    override val toolCode = "Code"
    override val toolLink = "Lien"
    override val toolHeading = "Titre"
    override val toolList = "Liste à puces"
    override val toolListLabel = "Liste"
    override val highlightPlaceholder = "surlignage"
    override val codePlaceholder = "code"
    override val linkUrlPrompt = "URL du lien"
    override val draftRestored = "Brouillon non enregistré restauré"
    override val discardDraft = "Réinitialiser"

    override val addFile = "Ajouter un fichier"
    override val chooseFile = "Choisir un fichier…"
    override val download = "Télécharger"
    override val fileDefaultTitle = "Fichier"
    override fun noPreviewFor(mime: String) = "Pas d'aperçu intégré pour $mime — utilisez Télécharger."

    override val appearance = "Apparence"
    override val theme = "Thème"
    override val themeHint = "Suivre le système, ou forcer jour/nuit."
    override val themeAuto = "Auto"
    override val themeLight = "Clair"
    override val themeDark = "Sombre"
    override val accentColor = "Couleur d'accent"
    override val accentColorHint = "La couleur de marque derrière les boutons, la sélection et les surlignages."
    override val accentBlue = "Bleu"
    override val accentPurple = "Violet"
    override val accentGreen = "Vert"
    override val accentOrange = "Orange"
    override val accentRose = "Rose"
    override val language = "Langue"
    override val languageHint = "La langue de l'interface."
    override val cardUrls = "Adresses des cartes"
    override val cardUrlsHint = "Si une carte-lien affiche son adresse sous le titre."
    override val cardUrlsShow = "Afficher"
    override val cardUrlsHide = "Masquer"
    override val groupsView = "Vue des sections"
    override val groupsViewHint =
        "Afficher les sections d'une collection les unes sous les autres, ou comme des dossiers qui s'ouvrent sur place."
    override val groupsViewList = "Liste"
    override val groupsViewFolders = "Dossiers"
    override val folderBack = "Retour aux dossiers"
    override val swapSidebars = "Ordre des panneaux"
    override val swapSidebarsHint = "De quel côté se trouve le panneau des sections, par rapport à celui des onglets/historique."
    override val swapSidebarsLeft = "Sections à gauche"
    override val swapSidebarsRight = "Sections à droite"
    override val tabsCardView = "Vue des onglets"
    override val tabsCardViewHint =
        "Afficher les onglets ouverts en liste, ou en grille de cartes de la même largeur que le panneau central."
    override val tabsCardViewList = "Liste"
    override val tabsCardViewCards = "Cartes"

    override val startupSection = "Démarrage"
    override val startView = "À l'ouverture"
    override val startViewHint = "Quelle collection s'affiche à l'ouverture de stramus. Une collection protégée " +
        "par un code PIN ne l'est jamais — chaque rechargement reverrouille sa section."
    override val startViewLast = "Dernière ouverte"
    override val startViewFirst = "Première collection"

    override val dataSection = "Données"

    override val export = "Exporter"
    override val exportHint = "Téléchargez tous les liens enregistrés dans toutes les collections. Une section " +
        "encore protégée par son code PIN est exclue."
    override val exportCsv = "Exporter en CSV"
    override val exportBookmarks = "Exporter les favoris"

    override val import = "Importer"
    override val importHint = "Importez un fichier de favoris depuis n'importe quel navigateur, ou un CSV exporté " +
        "ici. Les dossiers deviennent des sections, des collections et des groupes ; un lien déjà enregistré " +
        "là où il atterrirait est laissé tel quel."
    override val importFile = "Choisir un fichier"
    override val importedTitle = "Importé"
    override fun importDone(added: Int, skipped: Int) = when (skipped) {
        0 -> "$added liens importés."
        else -> "$added liens importés ; $skipped étaient déjà enregistrés."
    }
    override val importNothing = "Aucun lien à importer dans ce fichier."

    override val sortTitle = "Titre A–Z"
    override val sortUrl = "URL"
    override val sortDomain = "Domaine"
    override val sortNewest = "Plus récents d'abord"
    override val sortOldest = "Plus anciens d'abord"

    override val account = "Compte"
    override val accountSignedOutHint = "Connectez-vous pour retrouver vos collections sur chaque navigateur que vous utilisez. Tout fonctionne sans compte — cela reste alors simplement sur cet ordinateur."
    override val signInAccount = "Se connecter"
    override val signOut = "Se déconnecter"
    override val syncNow = "Synchroniser maintenant"
    override fun syncedAt(time: String) = "Synchronisé à $time"
    override fun conflictCopies(count: Int) =
        if (count == 1) "Une note a été modifiée sur deux appareils à la fois. Les deux versions ont été conservées."
        else "$count notes ont été modifiées sur deux appareils à la fois. Les deux versions de chacune ont été conservées."
    override val joinAccountTitle = "Ce navigateur a déjà des collections"
    override val joinAccountHint = "Vous pouvez les ajouter au compte, ou les laisser ici et reprendre ce que le compte contient déjà."
    override val joinAccountKeep = "Les ajouter au compte"
    override val joinAccountDiscard = "Utiliser les collections du compte"
    override val exportAccountData = "Télécharger mes données"
    override val exportAccountDataHint = "Toutes les données que le serveur détient sur ce compte, au format JSON."
    override val exportAccountDataFailed = "Impossible de télécharger l'export."
    override val deleteAccount = "Supprimer le compte"
    override val deleteAccountHint = "Efface tout ce que le serveur détient. Ce qui est sur cet ordinateur reste."
    override val deleteAccountConfirm = "Supprimer le compte et tout ce que le serveur détient ? Cette action est irréversible."
    override val syncUsage = "Synchroniser les statistiques de navigation"
    override val syncUsageHint = "Quelles pages vous ouvrez et à quelle fréquence — ce sur quoi la recherche se base pour classer. Désactivé signifie que cela reste sur cet ordinateur."
    override val optionOn = "Activé"
    override val optionOff = "Désactivé"
    override val signInWithGoogle = "Continuer avec Google"
    override val signInUnavailable = "La connexion n'est pas configurée dans cette version. L'application fonctionne sans compte, comme toujours."
    override val serverUnavailable = "Le serveur ne répond pas pour le moment. Ceci en a besoin — réessayez une fois qu'il sera de retour."

    override val onboardingSignInTitle = "Connectez-vous pour synchroniser partout"
    override val onboardingSignInBody = "Vos collections restent sur cet appareil tant que vous n'êtes pas connecté — connectez-vous, et elles vous suivent automatiquement sur chaque navigateur que vous utilisez."
    override val onboardingSkip = "Plus tard"
    override val onboardingInstallTitle = "Installez l'extension"
    override val onboardingInstallBody = "stramus fonctionne directement dans cet onglet, mais l'extension ajoute une page de nouvel onglet dédiée, l'enregistrement des onglets en un clic et une recherche sur vos onglets ouverts et votre historique — l'expérience complète."
    override val onboardingInstallCta = "Installer depuis le Chrome Web Store"
    override val onboardingContinueInBrowser = "Continuer dans le navigateur"
    override val onboardingOrganizeTitle = "Des collections, regroupées en sections"
    override val onboardingOrganizeBody = "La barre latérale contient vos sections ; chacune s'ouvre sur ses collections. Glissez un onglet, un lien ou un fichier dans une collection pour l'y enregistrer — rien ne quitte votre appareil sans que vous le demandiez."
    override val onboardingSearchTitle = "Une seule recherche pour tout"
    override val onboardingSearchBody = "La barre de recherche en haut trouve à la fois les cartes enregistrées, les onglets ouverts et l'historique de navigation — commencez à taper, stramus cherche partout à votre place."
    override val onboardingBack = "Précédent"
    override val onboardingNext = "Suivant"
    override val onboardingGetStarted = "Commencer"

    override val seed = StoreSeed(
        sectionTitle = "Principal",
        collectionTitle = "Prise en main",
        noteTitle = "Comment utiliser stramus",
        // Chaque puce est une seule ligne : c'est ce markdown que lit `Markdown.kt`, et une ligne
        // qui reviendrait à la ligne y terminerait la liste au lieu de la continuer.
        noteBody = """
            # Bienvenue dans stramus

            Le panneau à gauche contient des **sections**, une section contient des **collections**, et une collection contient des cartes — liens, fichiers et notes comme celle-ci.

            ## Enregistrer une page
            - Glissez un onglet depuis le panneau de droite vers une collection, ou utilisez **⤓ Enregistrer les onglets ouverts** pour toute une fenêtre à la fois.
            - Survolez l'en-tête d'une section et appuyez sur son **+** pour ajouter une adresse collée, une note ou un fichier directement dans cette section.
            - **+ Groupe** divise une grande collection en groupes — déposez une carte sur l'un d'eux pour l'y déplacer.

            ## Trouver une page
            - La barre de recherche en haut cherche partout à la fois : ce que vous avez enregistré, les onglets que vous avez ouverts, et où vous êtes allé.
            - Tapez une adresse pour l'ouvrir, ou une question pour interroger le modèle intégré du navigateur.
            - ↑↓ pour choisir, Entrée pour ouvrir, Échap pour fermer.

            ## Garder de l'ordre
            - **Protéger avec un code PIN** : une section verrouillée ne nomme même pas ses collections, et se reverrouille dès que vous vous éloignez.
            - **🔒 Lecture seule** protège une collection terminée contre un geste malheureux.
            - Les paramètres contiennent le thème, la langue, et un export de tout vers CSV ou favoris.

            Renommez cette collection, ou supprimez cette note — tout ceci est désormais à vous.
        """.trimIndent(),
    )
}

private object EsStrings : Strings {
    override val on = "Activado"
    override val off = "Desactivado"
    override val experimental = "experimental"
    override val settings = "Ajustes"
    override val close = "Cerrar"
    override val cancel = "Cancelar"
    override val save = "Guardar"
    override val about = "Acerca de"
    override fun aboutVersion(version: String) = "Versión $version"
    override fun aboutCopyright(year: String) = "© $year Stramus"
    override val aboutHomepage = "stramus.space"

    override val expandSidebar = "Expandir panel"
    override val collapseSidebar = "Contraer panel"
    override val newSection = "+ Nueva sección"
    override val sectionNamePrompt = "Nombre de la sección"
    override val sectionNameDefault = "Nueva sección"
    override val collectionNamePrompt = "Nombre de la colección"
    override val collectionNameDefault = "Nueva colección"
    override val renameHint = "Clic para colapsar, doble clic para renombrar, arrastrar para reordenar"
    override val renameCollectionHint = "Doble clic para renombrar"
    override val untitled = "Sin título"

    override val newSectionHint = "Añadir una sección al panel"
    override val addCollectionHint = "Añadir una colección a esta sección"
    override val deleteSectionHint = "Eliminar esta sección y las colecciones que contiene"
    override val deleteCollectionHint = "Eliminar esta colección y sus tarjetas"
    override val addCardSectionHint = "Añadir un grupo a esta colección"
    override val deleteCardSectionHint = "Eliminar este grupo — sus tarjetas se quedan en la colección, sin grupo"
    override val addCardHint = "Añadir un enlace — o, desde el menú, una nota o un archivo"
    override val deleteCardHint = "Eliminar esta tarjeta"
    override fun openAllHint(count: Int) = "Abrir las $count tarjetas en pestañas nuevas"

    override fun confirmDeleteSection(title: String, cards: Int) =
        "«$title» y sus colecciones contienen $cards elementos guardados. ¿Eliminar la sección?"
    override fun confirmDeleteCollection(title: String, cards: Int) =
        "«$title» contiene $cards elementos guardados. ¿Eliminar la colección?"
    override fun confirmDeleteCardSection(title: String, cards: Int) =
        "«$title» contiene $cards tarjetas. ¿Eliminar el grupo? Las tarjetas se quedan, sin grupo."
    override fun deletedSection(title: String) = "Sección «$title» eliminada"
    override fun deletedCollection(title: String) = "Colección «$title» eliminada"
    override fun deletedCardSection(title: String) = "Grupo «$title» eliminado"
    override fun deletedCard(title: String) = "«$title» eliminada"
    override fun movedCard(title: String) = "«$title» movida"
    override val sortedCards = "Tarjetas ordenadas"
    override val undo = "Deshacer"

    override val searchPlaceholder = "Buscar, escribir una dirección, o preguntar…"

    override val hitsTopSites = "Abiertos con frecuencia"
    override val hitsTabs = "Pestañas abiertas"
    override val hitsCards = "Guardado"
    override val hitsHistory = "Historial"
    override val hitsSites = "Sitios"
    override val hitsCollections = "Colecciones"

    override val hitSwitchToTab = "Ir a"
    override val hitOpenCollection = "Abrir"
    override val hitAskAi = "Preguntar"

    // El buscador es el del propio navegador — el que esté configurado — así que no se nombra aquí.
    override fun hitWebSearch(query: String) = "Buscar «$query» en la web"
    override fun hitOpenUrl(query: String) = "Abrir $query"
    override fun hitAskAiRow(assistant: String, query: String) = "Preguntar a $assistant: «$query»"

    override val forgetSite = "Dejar de sugerir esta página"
    override val searchHints = "↑↓ elegir · Intro abrir · Alt+Intro buscar en la web · ⌘/Ctrl+Intro todos los resultados · Esc cerrar"

    override val aiChip = "IA"
    override val aiHeading = "Asistente"
    override val aiEmpty = "Pregunta sobre la colección que tienes abierta, o sobre cualquier otra cosa."
    override val aiPlaceholder = "Haz otra pregunta…"
    override val aiSend = "Preguntar"
    override val aiThinking = "Pensando…"
    override val aiCopy = "Copiar"
    override val aiSaveNote = "Guardar como nota"
    override val aiUnavailable = "Este navegador no tiene ningún modelo integrado disponible."
    override val aiFailed = "El modelo no pudo responder."
    override fun aiDownloading(percent: Int) = "Descargando el modelo — $percent %. Esto ocurre una sola vez."
    override val aiSystemPrompt = "Eres el asistente dentro de stramus, un gestor de marcadores y pestañas. " +
        "Responde de forma breve y precisa, en el idioma en que se hizo la pregunta. El Markdown es bienvenido."

    override val aiTriageSetting = "Ordenar pestañas con el modelo integrado"
    override val aiTriageSettingHint = "Añade un botón a una ventana de pestañas: el modelo las lee y propone " +
        "una colección para cada una, para que la revises antes de que se guarde nada. Todo permanece en " +
        "este equipo. Tarda uno o dos minutos en una ventana grande, y deja fuera lo que no puede ubicar."
    override val triageTabs = "Ordenar en colecciones"
    override val triageHeading = "Ordenar pestañas en colecciones"
    override val triageSummaryHeading = "De qué trató esta sesión"
    override val triageSummaryTitle = "Resumen de la sesión"
    override val triageNew = "nueva"
    override fun triageNewHint(section: String) = "Todavía no existe esa colección — se creará en «$section»."
    override val triageNewSectionHint = "Todavía no existe ese grupo en esta colección — se creará."
    override val triageGroupHint = "En qué sección del panel se creará esta nueva colección"
    override val triageSectionHint = "En qué grupo de la colección irá esta pestaña"
    override val triageNoSection = "Sin grupo"
    override fun triageProgress(done: Int, total: Int) = "Ordenando sitios — $done de $total…"
    override val triageUnsorted = "Sin ordenar"
    override val triageUnsortedHint = "El modelo no tuvo nada que decir sobre estas. Elige una colección, o déjalas abiertas."
    override val triageSkip = "No guardar"
    override val triageMoveHint = "A qué colección irá esta pestaña"
    override val triageDuplicate = "ya guardado"
    override val triageDuplicateHint = "Esta página ya está en una colección. Márcala para guardarla de nuevo."
    override fun triageRelated(site: String, count: Int) = "Ya guardado de $site ($count):"
    override fun triageApply(count: Int, closesTabs: Boolean) =
        if (closesTabs) "Guardar ($count) y cerrar" else "Guardar ($count)"
    override val aiTriageSystemPrompt = "Ordenas las pestañas abiertas del navegador de un usuario en sus " +
        "colecciones. Se te dan las pestañas y las colecciones que existen. Para cada pestaña, responde con " +
        "la única colección a la que pertenece — reutiliza un nombre existente siempre que la pestaña encaje, " +
        "e inventa un nombre corto (una o dos palabras) solo si no encaja en ninguna. Dentro de una colección " +
        "puedes indicar un grupo, reutilizando también los existentes. Pestañas de un mismo sitio pueden " +
        "pertenecer a colecciones distintas. Responde únicamente con el JSON solicitado."

    override val aiSection = "IA"
    override val aiAssistant = "Asistente"
    override val aiAssistantHint = "Quién responde a una pregunta hecha desde la barra de búsqueda."
    override val aiProviderLocal = "En el dispositivo"
    override fun aiWebChatHint(assistant: String) =
        "La pregunta abre $assistant en esta pestaña, ya formulada. Se envía a los servidores de $assistant — " +
            "a diferencia del modelo integrado, que responde aquí y mantiene todo en este equipo."

    override val aiModel = "Modelo"
    override val aiModelReadyHint = "El modelo integrado del navegador. Funciona en este equipo — sin clave, y nada sale de él."
    override val aiModelDownloadableHint = "El navegador lo descargará en la primera pregunta — unos cientos de megabytes, una sola vez."
    override val aiModelDownloadingHint = "El navegador lo está descargando ahora mismo."
    override val aiModelNone = "No disponible"
    override val aiModelNoneHint =
        "Este navegador no le da a la página ningún modelo integrado, así que la búsqueda no ofrece preguntarle. " +
            "En Chrome está disponible para la extensión; una página web normal necesita los flags para ello."
    override fun aiModelUnsupported(name: String) = "$name — no disponible"
    override val aiModelUnsupportedHint =
        "El navegador tiene el modelo pero no puede ejecutarlo aquí: necesita ~22 GB libres en el disco que " +
            "contiene el perfil de Chrome, y una GPU con más de 4 GB de memoria."

    override fun resultsFor(query: String) = "Resultados de «$query»"
    override val noMatchingLinks = "No hay enlaces coincidentes."
    override val createCollectionToStart = "Crea una colección para empezar a guardar enlaces."
    override val sortLinks = "Ordenar las tarjetas de este grupo"
    override val sortMenuTitle = "Ordenar por"
    override val addCardSection = "Grupo"
    override val pasteUrl = "Pegar una URL"
    override val addLinkItem = "Enlace"
    override val addNoteItem = "Nota"
    override val addFileItem = "Archivo"
    override val noLinksYet = "Aún no hay enlaces — añade uno, o arrastra uno aquí."
    override val ungrouped = "Sin grupo"
    override val dragLinksHere = "Arrastra enlaces o archivos aquí."
    override val editDescription = "Editar descripción"

    override fun filesTooLarge(names: List<String>, maxMb: Int) =
        "No guardado — más de $maxMb MB: ${names.joinToString(", ")}"

    override val protectSection = "Proteger con un PIN"
    override val sectionProtection = "Protección de la sección"
    override val changePin = "Cambiar PIN"
    override val removeProtection = "Quitar protección"
    override val lockNow = "Bloquear ahora"
    override val lockedSection = "Protegida con un PIN"
    override val unlockedSection = "Desbloqueada — clic para volver a bloquear"
    override val enterPinToView = "Introduce el PIN para ver las colecciones de esta sección."
    override val pinPlaceholder = "PIN"
    override val unlock = "Desbloquear"
    override val wrongPin = "PIN incorrecto."
    override val setPinHeading = "Proteger sección"
    override val changePinHeading = "Cambiar PIN"
    override val newPinLabel = "Nuevo PIN"
    override val repeatPinLabel = "Repite el PIN"
    override val pinMismatch = "Los dos PIN no coinciden."
    override fun pinTooShort(min: Int) = "El PIN debe tener al menos $min dígitos."
    override val pinNote = "El PIN oculta toda la sección: sus colecciones ni siquiera se nombran hasta que " +
        "se introduce, y sus tarjetas quedan fuera de la búsqueda y la exportación. No hay forma de " +
        "restablecer un PIN olvidado."

    override val makeReadOnlyHint = "Hacer de solo lectura: nada podrá añadirse, cambiarse ni eliminarse aquí."
    override val allowEditing = "Permitir edición"
    override val allowEditingHint = "Permitir edición de nuevo."
    override val readOnlyBadge = "solo lectura"
    override val readOnlyHint = "Solo lectura: nada aquí puede añadirse, cambiarse ni eliminarse."

    override val security = "Seguridad"
    override val autoLock = "Bloqueo automático"
    override val autoLockHint = "Vuelve a bloquear las secciones desbloqueadas tras este tiempo sin actividad."
    override val autoLockNever = "Nunca"
    override fun autoLockMinutes(minutes: Int) = "$minutes min"

    override val openTabs = "Pestañas abiertas"
    override val showTabs = "Mostrar pestañas abiertas"
    override val hideTabs = "Ocultar pestañas abiertas"
    override val noOpenTabs = "No hay pestañas abiertas para guardar."
    override val searchTabs = "Buscar en pestañas…"
    override val noMatchingTabs = "No hay pestañas coincidentes."
    override val thisWindow = "Esta ventana"
    override fun windowLabel(number: Int) = "Ventana $number"
    override val closeTab = "Cerrar pestaña"
    override val sortTabs = "Ordenar las pestañas de esta ventana"
    override fun saveTabsHint(count: Int, closing: Boolean) =
        "Guardar las pestañas de esta ventana ($count) en la colección abierta, sin grupo — " +
            if (closing) "y cerrarlas" else "y dejarlas abiertas"

    override fun confirmSaveTabs(count: Int, collection: String, closing: Boolean) =
        if (closing) "¿Guardar las pestañas de esta ventana ($count) en «$collection» y cerrarlas?"
        else "¿Guardar las pestañas de esta ventana ($count) en «$collection»?"

    override val tabsSection = "Pestañas"
    override val closeSavedTabs = "Tras guardar pestañas"
    override val closeSavedTabsHint =
        "Qué ocurre con las pestañas de una ventana una vez guardadas en una colección."
    override val closeSavedTabsClose = "Cerrarlas"
    override val closeSavedTabsKeep = "Dejarlas abiertas"

    override val paneTabs = "Pestañas"
    override val paneHistory = "Historial"
    override val searchHistory = "Buscar en el historial…"
    override val noHistory = "Aún no hay nada en el historial."
    override val noMatchingHistory = "Nada coincide en el historial."
    override val today = "Hoy"
    override val yesterday = "Ayer"
    override val removeFromHistory = "Quitar del historial"

    override val emptyNote = "Nota vacía"
    override val fileLabel = "archivo"
    override val renameCard = "Editar"
    override val cardNamePrompt = "Título de la tarjeta"
    override val renameHeading = "Editar tarjeta"
    override val renameShowUrl = "Mostrar dirección"
    override val renameHideUrl = "Ocultar dirección"
    override val renameUrlPrompt = "Dirección"

    override val newNote = "Nueva nota"
    override val editNote = "Editar nota"
    override val viewNote = "Nota"
    override val editNoteAction = "Editar"
    override val sectionDescription = "Descripción del grupo"
    override val titlePlaceholder = "Título"
    override val noteDefaultTitle = "Nota"
    override val notePlaceholder = "Empieza a escribir…"
    override val toolBold = "Negrita"
    override val toolItalic = "Cursiva"
    override val toolHighlight = "Resaltar"
    override val toolCode = "Código"
    override val toolLink = "Enlace"
    override val toolHeading = "Encabezado"
    override val toolList = "Lista con viñetas"
    override val toolListLabel = "Lista"
    override val highlightPlaceholder = "resaltado"
    override val codePlaceholder = "código"
    override val linkUrlPrompt = "URL del enlace"
    override val draftRestored = "Borrador sin guardar restaurado"
    override val discardDraft = "Restablecer"

    override val addFile = "Añadir archivo"
    override val chooseFile = "Elegir un archivo…"
    override val download = "Descargar"
    override val fileDefaultTitle = "Archivo"
    override fun noPreviewFor(mime: String) = "Sin vista previa para $mime — usa Descargar."

    override val appearance = "Apariencia"
    override val theme = "Tema"
    override val themeHint = "Seguir el sistema, o forzar día/noche."
    override val themeAuto = "Auto"
    override val themeLight = "Claro"
    override val themeDark = "Oscuro"
    override val accentColor = "Color de acento"
    override val accentColorHint = "El color de marca detrás de los botones, la selección y los resaltados."
    override val accentBlue = "Azul"
    override val accentPurple = "Morado"
    override val accentGreen = "Verde"
    override val accentOrange = "Naranja"
    override val accentRose = "Rosa"
    override val language = "Idioma"
    override val languageHint = "El idioma de la interfaz."
    override val cardUrls = "Direcciones en las tarjetas"
    override val cardUrlsHint = "Si una tarjeta de enlace muestra su dirección bajo el título."
    override val cardUrlsShow = "Mostrar"
    override val cardUrlsHide = "Ocultar"
    override val groupsView = "Vista de secciones"
    override val groupsViewHint =
        "Mostrar las secciones de una colección una bajo otra, o como carpetas que se abren donde están."
    override val groupsViewList = "Lista"
    override val groupsViewFolders = "Carpetas"
    override val folderBack = "Volver a las carpetas"
    override val swapSidebars = "Orden de los paneles"
    override val swapSidebarsHint = "En qué lado está el panel de secciones, frente al de pestañas/historial."
    override val swapSidebarsLeft = "Secciones a la izquierda"
    override val swapSidebarsRight = "Secciones a la derecha"
    override val tabsCardView = "Vista de pestañas"
    override val tabsCardViewHint =
        "Mostrar las pestañas abiertas en lista, o en una cuadrícula de tarjetas del mismo ancho que el panel central."
    override val tabsCardViewList = "Lista"
    override val tabsCardViewCards = "Tarjetas"

    override val startupSection = "Inicio"
    override val startView = "Al abrir"
    override val startViewHint = "Qué colección se muestra al abrir stramus. Una colección tras un PIN nunca " +
        "lo es — cada recarga vuelve a bloquear su sección."
    override val startViewLast = "Última abierta"
    override val startViewFirst = "Primera colección"

    override val dataSection = "Datos"

    override val export = "Exportar"
    override val exportHint = "Descarga todos los enlaces guardados en todas las colecciones. Una sección " +
        "todavía tras su PIN queda fuera."
    override val exportCsv = "Exportar CSV"
    override val exportBookmarks = "Exportar marcadores"

    override val import = "Importar"
    override val importHint = "Trae un archivo de marcadores de cualquier navegador, o un CSV exportado aquí. " +
        "Las carpetas se convierten en secciones, colecciones y grupos; un enlace ya guardado donde " +
        "aterrizaría se deja tal cual."
    override val importFile = "Elegir un archivo"
    override val importedTitle = "Importado"
    override fun importDone(added: Int, skipped: Int) = when (skipped) {
        0 -> "Se importaron $added enlaces."
        else -> "Se importaron $added enlaces; $skipped ya estaban guardados."
    }
    override val importNothing = "No hay enlaces para importar en ese archivo."

    override val sortTitle = "Título A–Z"
    override val sortUrl = "URL"
    override val sortDomain = "Dominio"
    override val sortNewest = "Más recientes primero"
    override val sortOldest = "Más antiguos primero"

    override val account = "Cuenta"
    override val accountSignedOutHint = "Inicia sesión para mantener tus colecciones en cada navegador que uses. Todo funciona sin cuenta — simplemente se queda en este equipo."
    override val signInAccount = "Iniciar sesión"
    override val signOut = "Cerrar sesión"
    override val syncNow = "Sincronizar ahora"
    override fun syncedAt(time: String) = "Sincronizado a las $time"
    override fun conflictCopies(count: Int) =
        if (count == 1) "Una nota se editó en dos dispositivos a la vez. Se conservaron ambas versiones."
        else "$count notas se editaron en dos dispositivos a la vez. Se conservaron ambas versiones de cada una."
    override val joinAccountTitle = "Este navegador ya tiene colecciones"
    override val joinAccountHint = "Puedes añadirlas a la cuenta, o dejarlas aquí y quedarte con lo que la cuenta ya tiene."
    override val joinAccountKeep = "Añadirlas a la cuenta"
    override val joinAccountDiscard = "Usar las colecciones de la cuenta"
    override val exportAccountData = "Descargar mis datos"
    override val exportAccountDataHint = "Todo lo que el servidor guarda sobre esta cuenta, en formato JSON."
    override val exportAccountDataFailed = "No se pudo descargar la exportación."
    override val deleteAccount = "Eliminar cuenta"
    override val deleteAccountHint = "Borra todo lo que guarda el servidor. Lo que está en este equipo permanece."
    override val deleteAccountConfirm = "¿Eliminar la cuenta y todo lo que guarda el servidor? Esto no se puede deshacer."
    override val syncUsage = "Sincronizar estadísticas de navegación"
    override val syncUsageHint = "Qué páginas abres y con qué frecuencia — lo que usa la búsqueda para ordenar. Desactivado significa que se queda en este equipo."
    override val optionOn = "Activado"
    override val optionOff = "Desactivado"
    override val signInWithGoogle = "Continuar con Google"
    override val signInUnavailable = "El inicio de sesión no está configurado en esta versión. La aplicación funciona sin cuenta, como siempre."
    override val serverUnavailable = "El servidor no responde en este momento. Esto lo necesita — inténtalo de nuevo cuando vuelva."

    override val onboardingSignInTitle = "Inicia sesión para sincronizar en todas partes"
    override val onboardingSignInBody = "Tus colecciones permanecen en este dispositivo hasta que inicies sesión — después te siguen automáticamente a cada navegador que uses."
    override val onboardingSkip = "Más tarde"
    override val onboardingInstallTitle = "Instala la extensión"
    override val onboardingInstallBody = "stramus funciona aquí mismo, en esta pestaña, pero la extensión añade su propia página de pestaña nueva, guardar pestañas con un clic y una búsqueda sobre tus pestañas abiertas y tu historial — la experiencia completa."
    override val onboardingInstallCta = "Instalar desde Chrome Web Store"
    override val onboardingContinueInBrowser = "Continuar en el navegador"
    override val onboardingOrganizeTitle = "Colecciones, agrupadas en secciones"
    override val onboardingOrganizeBody = "La barra lateral contiene tus secciones; cada una se abre a sus colecciones. Arrastra una pestaña, un enlace o un archivo a cualquier colección para guardarlo ahí — nada sale de tu dispositivo a menos que tú lo pidas."
    override val onboardingSearchTitle = "Una sola búsqueda para todo"
    override val onboardingSearchBody = "El cuadro de búsqueda de arriba encuentra tarjetas guardadas, pestañas abiertas e historial de navegación a la vez — empieza a escribir y stramus busca en todas partes por ti."
    override val onboardingBack = "Atrás"
    override val onboardingNext = "Siguiente"
    override val onboardingGetStarted = "Empezar"

    override val seed = StoreSeed(
        sectionTitle = "Principal",
        collectionTitle = "Primeros pasos",
        noteTitle = "Cómo usar stramus",
        // Cada punto es una sola línea: este es el markdown que lee `Markdown.kt`, y un salto de línea
        // dentro de un punto termina la lista en vez de continuarla.
        noteBody = """
            # Bienvenido a stramus

            El panel de la izquierda contiene **secciones**, una sección contiene **colecciones**, y una colección contiene tarjetas — enlaces, archivos y notas como esta.

            ## Guardar una página
            - Arrastra una pestaña desde el panel derecho a una colección, o usa **⤓ Guardar pestañas abiertas** para toda una ventana de una vez.
            - Pasa el cursor sobre el encabezado de una sección y pulsa su **+** para añadir una dirección pegada, una nota o un archivo directamente en esa sección.
            - **+ Grupo** divide una colección grande en grupos — suelta una tarjeta sobre uno para moverla ahí.

            ## Encontrar una página
            - La barra de búsqueda de arriba busca en todo a la vez: lo que has guardado, las pestañas que tienes abiertas y por dónde has estado.
            - Escribe una dirección para abrirla, o una pregunta para consultar al modelo integrado del navegador.
            - ↑↓ para elegir, Intro para abrir, Esc para cerrar.

            ## Mantener el orden
            - **Proteger con un PIN**: una sección bloqueada ni siquiera muestra los nombres de sus colecciones, y vuelve a bloquearse cuando te alejas.
            - **🔒 Solo lectura** protege una colección terminada de un despiste.
            - Los ajustes contienen el tema, el idioma y una exportación de todo a CSV o marcadores.

            Renombra esta colección, o elimina esta nota — ahora todo esto es tuyo.
        """.trimIndent(),
    )
}

private object DeStrings : Strings {
    override val on = "An"
    override val off = "Aus"
    override val experimental = "experimentell"
    override val settings = "Einstellungen"
    override val close = "Schließen"
    override val cancel = "Abbrechen"
    override val save = "Speichern"
    override val about = "Über"
    override fun aboutVersion(version: String) = "Version $version"
    override fun aboutCopyright(year: String) = "© $year Stramus"
    override val aboutHomepage = "stramus.space"

    override val expandSidebar = "Leiste ausklappen"
    override val collapseSidebar = "Leiste einklappen"
    override val newSection = "+ Neuer Bereich"
    override val sectionNamePrompt = "Name des Bereichs"
    override val sectionNameDefault = "Neuer Bereich"
    override val collectionNamePrompt = "Name der Sammlung"
    override val collectionNameDefault = "Neue Sammlung"
    override val renameHint = "Klick zum Einklappen, Doppelklick zum Umbenennen, Ziehen zum Umsortieren"
    override val renameCollectionHint = "Doppelklick zum Umbenennen"
    override val untitled = "Unbenannt"

    override val newSectionHint = "Einen Bereich zur Leiste hinzufügen"
    override val addCollectionHint = "Eine Sammlung zu diesem Bereich hinzufügen"
    override val deleteSectionHint = "Diesen Bereich und seine Sammlungen löschen"
    override val deleteCollectionHint = "Diese Sammlung und ihre Karten löschen"
    override val addCardSectionHint = "Eine Gruppe zu dieser Sammlung hinzufügen"
    override val deleteCardSectionHint = "Diese Gruppe löschen — ihre Karten bleiben in der Sammlung, ohne Gruppe"
    override val addCardHint = "Einen Link hinzufügen — oder, über das Menü, eine Notiz oder eine Datei"
    override val deleteCardHint = "Diese Karte löschen"
    override fun openAllHint(count: Int) = "Alle $count Karten in neuen Tabs öffnen"

    override fun confirmDeleteSection(title: String, cards: Int) =
        "„$title“ und seine Sammlungen enthalten $cards gespeicherte Einträge. Bereich löschen?"
    override fun confirmDeleteCollection(title: String, cards: Int) =
        "„$title“ enthält $cards gespeicherte Einträge. Sammlung löschen?"
    override fun confirmDeleteCardSection(title: String, cards: Int) =
        "„$title“ enthält $cards Karten. Gruppe löschen? Die Karten bleiben — ohne Gruppe."
    override fun deletedSection(title: String) = "Bereich „$title“ gelöscht"
    override fun deletedCollection(title: String) = "Sammlung „$title“ gelöscht"
    override fun deletedCardSection(title: String) = "Gruppe „$title“ gelöscht"
    override fun deletedCard(title: String) = "„$title“ gelöscht"
    override fun movedCard(title: String) = "„$title“ verschoben"
    override val sortedCards = "Karten sortiert"
    override val undo = "Rückgängig"

    override val searchPlaceholder = "Suchen, eine Adresse eingeben, oder fragen…"

    override val hitsTopSites = "Häufig geöffnet"
    override val hitsTabs = "Offene Tabs"
    override val hitsCards = "Gespeichert"
    override val hitsHistory = "Verlauf"
    override val hitsSites = "Seiten"
    override val hitsCollections = "Sammlungen"

    override val hitSwitchToTab = "Wechseln"
    override val hitOpenCollection = "Öffnen"
    override val hitAskAi = "Fragen"

    // Die Suchmaschine ist die des Browsers selbst — welche auch immer eingestellt ist —, sie wird hier also nicht genannt.
    override fun hitWebSearch(query: String) = "„$query“ im Web suchen"
    override fun hitOpenUrl(query: String) = "$query öffnen"
    override fun hitAskAiRow(assistant: String, query: String) = "$assistant fragen: „$query“"

    override val forgetSite = "Diese Seite nicht mehr vorschlagen"
    override val searchHints = "↑↓ auswählen · Eingabe öffnen · Alt+Eingabe im Web suchen · ⌘/Strg+Eingabe alle Ergebnisse · Esc schließen"

    override val aiChip = "KI"
    override val aiHeading = "Assistent"
    override val aiEmpty = "Frag etwas zur geöffneten Sammlung — oder zu irgendetwas anderem."
    override val aiPlaceholder = "Noch etwas fragen…"
    override val aiSend = "Fragen"
    override val aiThinking = "Denkt nach…"
    override val aiCopy = "Kopieren"
    override val aiSaveNote = "Als Notiz speichern"
    override val aiUnavailable = "In diesem Browser ist kein integriertes Modell verfügbar."
    override val aiFailed = "Das Modell konnte nicht antworten."
    override fun aiDownloading(percent: Int) = "Modell wird heruntergeladen — $percent %. Das passiert nur einmal."
    override val aiSystemPrompt = "Du bist der Assistent in stramus, einem Lesezeichen- und Tab-Manager. " +
        "Antworte kurz und auf den Punkt, in der Sprache der Frage. Markdown ist willkommen."

    override val aiTriageSetting = "Tabs mit dem integrierten Modell sortieren"
    override val aiTriageSettingHint = "Fügt einem Tab-Fenster eine Schaltfläche hinzu: Das Modell liest die Tabs und schlägt " +
        "für jeden eine Sammlung vor, die du prüfst, bevor irgendetwas gespeichert wird. Alles bleibt auf " +
        "diesem Gerät. Bei einem großen Fenster dauert es ein bis zwei Minuten, und was es nicht einordnen kann, lässt es aus."
    override val triageTabs = "In Sammlungen sortieren"
    override val triageHeading = "Tabs in Sammlungen sortieren"
    override val triageSummaryHeading = "Worum es in dieser Sitzung ging"
    override val triageSummaryTitle = "Sitzungszusammenfassung"
    override val triageNew = "neu"
    override fun triageNewHint(section: String) = "Diese Sammlung gibt es noch nicht — sie wird in „$section“ angelegt."
    override val triageNewSectionHint = "Diese Gruppe gibt es in dieser Sammlung noch nicht — sie wird angelegt."
    override val triageGroupHint = "In welchem Bereich der Leiste diese neue Sammlung angelegt wird"
    override val triageSectionHint = "In welche Gruppe der Sammlung dieser Tab kommt"
    override val triageNoSection = "Keine Gruppe"
    override fun triageProgress(done: Int, total: Int) = "Seiten werden sortiert — $done von $total…"
    override val triageUnsorted = "Nicht sortiert"
    override val triageUnsortedHint = "Zu diesen hatte das Modell nichts zu sagen. Wähle eine Sammlung, oder lass sie offen."
    override val triageSkip = "Nicht speichern"
    override val triageMoveHint = "In welche Sammlung dieser Tab kommt"
    override val triageDuplicate = "schon gespeichert"
    override val triageDuplicateHint = "Diese Seite ist bereits in einer Sammlung. Häkchen setzen, um sie erneut zu speichern."
    override fun triageRelated(site: String, count: Int) = "Bereits gespeichert von $site ($count):"
    override fun triageApply(count: Int, closesTabs: Boolean) =
        if (closesTabs) "Speichern ($count) und schließen" else "Speichern ($count)"
    override val aiTriageSystemPrompt = "Du sortierst die offenen Browser-Tabs eines Nutzers in dessen Sammlungen. " +
        "Du bekommst die Tabs und die vorhandenen Sammlungen. Antworte für jeden Tab mit der einen Sammlung, " +
        "zu der er gehört — nutze einen vorhandenen Namen, wo immer der Tab dazu passt, und erfinde nur dann " +
        "einen kurzen Namen (ein oder zwei Wörter), wenn keiner passt. Innerhalb einer Sammlung kannst du " +
        "eine Gruppe benennen und dabei auch vorhandene wiederverwenden. Tabs derselben Seite können zu " +
        "unterschiedlichen Sammlungen gehören. Antworte nur mit dem geforderten JSON."

    override val aiSection = "KI"
    override val aiAssistant = "Assistent"
    override val aiAssistantHint = "Wer eine aus der Suchleiste gestellte Frage beantwortet."
    override val aiProviderLocal = "Auf dem Gerät"
    override fun aiWebChatHint(assistant: String) =
        "Die Frage öffnet $assistant in diesem Tab, bereits gestellt. Sie wird an die Server von $assistant gesendet — " +
            "anders als das integrierte Modell, das hier antwortet und alles auf diesem Gerät behält."

    override val aiModel = "Modell"
    override val aiModelReadyHint = "Das integrierte Modell des Browsers. Läuft auf diesem Gerät — kein Schlüssel, nichts verlässt es."
    override val aiModelDownloadableHint = "Der Browser lädt es bei der ersten Frage herunter — einige hundert Megabyte, einmalig."
    override val aiModelDownloadingHint = "Der Browser lädt es gerade herunter."
    override val aiModelNone = "Nicht verfügbar"
    override val aiModelNoneHint =
        "Dieser Browser stellt der Seite kein integriertes Modell zur Verfügung, daher bietet die Suche nicht an, es zu fragen. " +
            "In Chrome steht es der Erweiterung zur Verfügung; eine gewöhnliche Webseite braucht dafür die Flags."
    override fun aiModelUnsupported(name: String) = "$name — nicht verfügbar"
    override val aiModelUnsupportedHint =
        "Der Browser hat das Modell, kann es hier aber nicht ausführen: Es braucht ~22 GB frei auf dem Laufwerk mit " +
            "dem Chrome-Profil und eine GPU mit mehr als 4 GB Speicher."

    override fun resultsFor(query: String) = "Ergebnisse für „$query“"
    override val noMatchingLinks = "Keine passenden Links."
    override val createCollectionToStart = "Lege eine Sammlung an, um Links zu speichern."
    override val sortLinks = "Karten dieser Gruppe sortieren"
    override val sortMenuTitle = "Sortieren nach"
    override val addCardSection = "Gruppe"
    override val pasteUrl = "Eine URL einfügen"
    override val addLinkItem = "Link"
    override val addNoteItem = "Notiz"
    override val addFileItem = "Datei"
    override val noLinksYet = "Noch keine Links — füge einen hinzu, oder zieh einen hierher."
    override val ungrouped = "Ohne Gruppe"
    override val dragLinksHere = "Links oder Dateien hierher ziehen."
    override val editDescription = "Beschreibung bearbeiten"

    override fun filesTooLarge(names: List<String>, maxMb: Int) =
        "Nicht gespeichert — über $maxMb MB: ${names.joinToString(", ")}"

    override val protectSection = "Mit PIN schützen"
    override val sectionProtection = "Bereichsschutz"
    override val changePin = "PIN ändern"
    override val removeProtection = "Schutz entfernen"
    override val lockNow = "Jetzt sperren"
    override val lockedSection = "Mit PIN geschützt"
    override val unlockedSection = "Entsperrt — klicken, um erneut zu sperren"
    override val enterPinToView = "PIN eingeben, um die Sammlungen dieses Bereichs zu sehen."
    override val pinPlaceholder = "PIN"
    override val unlock = "Entsperren"
    override val wrongPin = "Falsche PIN."
    override val setPinHeading = "Bereich schützen"
    override val changePinHeading = "PIN ändern"
    override val newPinLabel = "Neue PIN"
    override val repeatPinLabel = "PIN wiederholen"
    override val pinMismatch = "Die beiden PINs stimmen nicht überein."
    override fun pinTooShort(min: Int) = "Die PIN muss mindestens $min Ziffern haben."
    override val pinNote = "Die PIN verbirgt den ganzen Bereich: Ihre Sammlungen werden nicht einmal benannt, " +
        "bevor sie eingegeben wird, und ihre Karten bleiben aus Suche und Export heraus. Eine vergessene " +
        "PIN kann nicht zurückgesetzt werden."

    override val makeReadOnlyHint = "Schreibgeschützt machen: Hier kann dann nichts mehr hinzugefügt, geändert oder gelöscht werden."
    override val allowEditing = "Bearbeitung erlauben"
    override val allowEditingHint = "Bearbeitung wieder erlauben."
    override val readOnlyBadge = "schreibgeschützt"
    override val readOnlyHint = "Schreibgeschützt: Hier kann nichts hinzugefügt, geändert oder gelöscht werden."

    override val security = "Sicherheit"
    override val autoLock = "Automatische Sperre"
    override val autoLockHint = "Entsperrte Bereiche nach dieser Zeit ohne Aktivität wieder sperren."
    override val autoLockNever = "Nie"
    override fun autoLockMinutes(minutes: Int) = "$minutes Min"

    override val openTabs = "Offene Tabs"
    override val showTabs = "Offene Tabs anzeigen"
    override val hideTabs = "Offene Tabs ausblenden"
    override val noOpenTabs = "Keine offenen Tabs zum Speichern."
    override val searchTabs = "Tabs durchsuchen…"
    override val noMatchingTabs = "Keine passenden Tabs."
    override val thisWindow = "Dieses Fenster"
    override fun windowLabel(number: Int) = "Fenster $number"
    override val closeTab = "Tab schließen"
    override val sortTabs = "Tabs dieses Fensters sortieren"
    override fun saveTabsHint(count: Int, closing: Boolean) =
        "Tabs dieses Fensters ($count) in die geöffnete Sammlung speichern, ohne Gruppe — " +
            if (closing) "und sie schließen" else "und sie offen lassen"

    override fun confirmSaveTabs(count: Int, collection: String, closing: Boolean) =
        if (closing) "Tabs dieses Fensters ($count) in „$collection“ speichern und schließen?"
        else "Tabs dieses Fensters ($count) in „$collection“ speichern?"

    override val tabsSection = "Tabs"
    override val closeSavedTabs = "Nach dem Speichern von Tabs"
    override val closeSavedTabsHint =
        "Was mit den Tabs eines Fensters passiert, sobald sie in einer Sammlung gespeichert sind."
    override val closeSavedTabsClose = "Schließen"
    override val closeSavedTabsKeep = "Offen lassen"

    override val paneTabs = "Tabs"
    override val paneHistory = "Verlauf"
    override val searchHistory = "Verlauf durchsuchen…"
    override val noHistory = "Noch nichts im Verlauf."
    override val noMatchingHistory = "Nichts im Verlauf passt dazu."
    override val today = "Heute"
    override val yesterday = "Gestern"
    override val removeFromHistory = "Aus dem Verlauf entfernen"

    override val emptyNote = "Leere Notiz"
    override val fileLabel = "Datei"
    override val renameCard = "Bearbeiten"
    override val cardNamePrompt = "Kartentitel"
    override val renameHeading = "Karte bearbeiten"
    override val renameShowUrl = "Adresse anzeigen"
    override val renameHideUrl = "Adresse ausblenden"
    override val renameUrlPrompt = "Adresse"

    override val newNote = "Neue Notiz"
    override val editNote = "Notiz bearbeiten"
    override val viewNote = "Notiz"
    override val editNoteAction = "Bearbeiten"
    override val sectionDescription = "Gruppenbeschreibung"
    override val titlePlaceholder = "Titel"
    override val noteDefaultTitle = "Notiz"
    override val notePlaceholder = "Fang an zu schreiben…"
    override val toolBold = "Fett"
    override val toolItalic = "Kursiv"
    override val toolHighlight = "Hervorheben"
    override val toolCode = "Code"
    override val toolLink = "Link"
    override val toolHeading = "Überschrift"
    override val toolList = "Aufzählungsliste"
    override val toolListLabel = "Liste"
    override val highlightPlaceholder = "Hervorhebung"
    override val codePlaceholder = "Code"
    override val linkUrlPrompt = "Link-URL"
    override val draftRestored = "Nicht gespeicherter Entwurf wiederhergestellt"
    override val discardDraft = "Zurücksetzen"

    override val addFile = "Datei hinzufügen"
    override val chooseFile = "Datei auswählen…"
    override val download = "Herunterladen"
    override val fileDefaultTitle = "Datei"
    override fun noPreviewFor(mime: String) = "Keine Vorschau für $mime — Herunterladen verwenden."

    override val appearance = "Erscheinungsbild"
    override val theme = "Design"
    override val themeHint = "Dem System folgen, oder Tag/Nacht erzwingen."
    override val themeAuto = "Automatisch"
    override val themeLight = "Hell"
    override val themeDark = "Dunkel"
    override val accentColor = "Akzentfarbe"
    override val accentColorHint = "Die Markenfarbe hinter Schaltflächen, Auswahl und Hervorhebungen."
    override val accentBlue = "Blau"
    override val accentPurple = "Violett"
    override val accentGreen = "Grün"
    override val accentOrange = "Orange"
    override val accentRose = "Rosé"
    override val language = "Sprache"
    override val languageHint = "Die Sprache der Oberfläche."
    override val cardUrls = "Kartenadressen"
    override val cardUrlsHint = "Ob eine Link-Karte ihre Adresse unter dem Titel zeigt."
    override val cardUrlsShow = "Anzeigen"
    override val cardUrlsHide = "Verbergen"
    override val groupsView = "Bereichsansicht"
    override val groupsViewHint =
        "Die Bereiche einer Sammlung untereinander anzeigen, oder als Ordner, die an ihrem Platz aufklappen."
    override val groupsViewList = "Liste"
    override val groupsViewFolders = "Ordner"
    override val folderBack = "Zurück zu den Ordnern"
    override val swapSidebars = "Reihenfolge der Leisten"
    override val swapSidebarsHint = "Auf welcher Seite die Bereichsleiste sitzt, im Vergleich zur Tabs-/Verlaufsleiste."
    override val swapSidebarsLeft = "Bereiche links"
    override val swapSidebarsRight = "Bereiche rechts"
    override val tabsCardView = "Tab-Ansicht"
    override val tabsCardViewHint =
        "Offene Tabs als Liste anzeigen, oder als Kartenraster in derselben Breite wie der mittlere Bereich."
    override val tabsCardViewList = "Liste"
    override val tabsCardViewCards = "Karten"

    override val startupSection = "Start"
    override val startView = "Beim Öffnen"
    override val startViewHint = "Welche Sammlung beim Öffnen von stramus angezeigt wird. Eine Sammlung hinter " +
        "einer PIN ist es nie — bei jedem Neuladen sperrt sich ihr Bereich wieder."
    override val startViewLast = "Zuletzt geöffnete"
    override val startViewFirst = "Erste Sammlung"

    override val dataSection = "Daten"

    override val export = "Exportieren"
    override val exportHint = "Alle gespeicherten Links aus allen Sammlungen herunterladen. Ein Bereich, dessen " +
        "PIN noch nicht eingegeben wurde, wird ausgelassen."
    override val exportCsv = "CSV exportieren"
    override val exportBookmarks = "Lesezeichen exportieren"

    override val import = "Importieren"
    override val importHint = "Eine Lesezeichendatei aus einem beliebigen Browser importieren, oder eine hier " +
        "exportierte CSV. Ordner werden zu Bereichen, Sammlungen und Gruppen; ein Link, der bereits dort " +
        "gespeichert ist, wo er landen würde, bleibt unangetastet."
    override val importFile = "Datei auswählen"
    override val importedTitle = "Importiert"
    override fun importDone(added: Int, skipped: Int) = when (skipped) {
        0 -> "$added Links importiert."
        else -> "$added Links importiert; $skipped waren bereits gespeichert."
    }
    override val importNothing = "Keine Links in dieser Datei zu importieren."

    override val sortTitle = "Titel A–Z"
    override val sortUrl = "URL"
    override val sortDomain = "Domain"
    override val sortNewest = "Neueste zuerst"
    override val sortOldest = "Älteste zuerst"

    override val account = "Konto"
    override val accountSignedOutHint = "Melde dich an, um deine Sammlungen auf jedem Browser zu behalten, den du benutzt. Alles funktioniert auch ohne Konto — es bleibt dann einfach auf diesem Gerät."
    override val signInAccount = "Anmelden"
    override val signOut = "Abmelden"
    override val syncNow = "Jetzt synchronisieren"
    override fun syncedAt(time: String) = "Synchronisiert um $time"
    override fun conflictCopies(count: Int) =
        if (count == 1) "Eine Notiz wurde auf zwei Geräten gleichzeitig bearbeitet. Beide Versionen wurden behalten."
        else "$count Notizen wurden auf zwei Geräten gleichzeitig bearbeitet. Von jeder wurden beide Versionen behalten."
    override val joinAccountTitle = "Dieser Browser hat bereits Sammlungen"
    override val joinAccountHint = "Du kannst sie zum Konto hinzufügen, oder sie hier lassen und übernehmen, was das Konto bereits hat."
    override val joinAccountKeep = "Zum Konto hinzufügen"
    override val joinAccountDiscard = "Sammlungen des Kontos verwenden"
    override val exportAccountData = "Meine Daten herunterladen"
    override val exportAccountDataHint = "Alles, was der Server zu diesem Konto speichert, als JSON."
    override val exportAccountDataFailed = "Der Export konnte nicht heruntergeladen werden."
    override val deleteAccount = "Konto löschen"
    override val deleteAccountHint = "Löscht alles, was der Server speichert. Was auf diesem Gerät ist, bleibt."
    override val deleteAccountConfirm = "Konto und alles, was der Server speichert, löschen? Das kann nicht rückgängig gemacht werden."
    override val syncUsage = "Nutzungsstatistiken synchronisieren"
    override val syncUsageHint = "Welche Seiten du öffnest und wie oft — wonach die Suche sortiert. Aus bedeutet, es bleibt auf diesem Gerät."
    override val optionOn = "An"
    override val optionOff = "Aus"
    override val signInWithGoogle = "Mit Google fortfahren"
    override val signInUnavailable = "Die Anmeldung ist in dieser Version nicht eingerichtet. Die App funktioniert auch ohne Konto, wie schon immer."
    override val serverUnavailable = "Der Server antwortet gerade nicht. Das wird dafür gebraucht — versuch es erneut, sobald er wieder da ist."

    override val onboardingSignInTitle = "Anmelden, um überall zu synchronisieren"
    override val onboardingSignInBody = "Deine Sammlungen bleiben auf diesem Gerät, bis du dich anmeldest — danach folgen sie dir automatisch in jeden Browser, den du benutzt."
    override val onboardingSkip = "Später"
    override val onboardingInstallTitle = "Erweiterung installieren"
    override val onboardingInstallBody = "stramus funktioniert direkt in diesem Tab, aber die Erweiterung bringt eine eigene Neuer-Tab-Seite, Tabs mit einem Klick speichern und eine Suche über deine offenen Tabs und deinen Verlauf — das volle Erlebnis."
    override val onboardingInstallCta = "Aus dem Chrome Web Store installieren"
    override val onboardingContinueInBrowser = "Im Browser fortfahren"
    override val onboardingOrganizeTitle = "Sammlungen, gruppiert in Bereiche"
    override val onboardingOrganizeBody = "Die Seitenleiste enthält deine Bereiche; jeder öffnet sich zu seinen Sammlungen. Ziehe einen Tab, einen Link oder eine Datei in eine Sammlung, um sie dort zu speichern — nichts verlässt dein Gerät, ohne dass du danach fragst."
    override val onboardingSearchTitle = "Eine Suche für alles"
    override val onboardingSearchBody = "Die Suchleiste oben findet gespeicherte Karten, offene Tabs und den Browserverlauf gleichzeitig — fang an zu tippen, und stramus sucht überall für dich."
    override val onboardingBack = "Zurück"
    override val onboardingNext = "Weiter"
    override val onboardingGetStarted = "Loslegen"

    override val seed = StoreSeed(
        sectionTitle = "Haupt",
        collectionTitle = "Erste Schritte",
        noteTitle = "Wie man stramus benutzt",
        // Jeder Punkt ist eine Zeile: Das hier ist das Markdown, das `Markdown.kt` liest, und ein
        // umgebrochener Zeilenumbruch darin beendet die Liste, statt sie fortzusetzen.
        noteBody = """
            # Willkommen bei stramus

            Die Leiste links enthält **Bereiche**, ein Bereich enthält **Sammlungen**, und eine Sammlung enthält Karten — Links, Dateien und Notizen wie diese.

            ## Eine Seite speichern
            - Zieh einen Tab aus der rechten Leiste auf eine Sammlung, oder nutze **⤓ Offene Tabs speichern** für ein ganzes Fenster auf einmal.
            - Fahr über den Titel eines Bereichs und drück dessen **+**, um eine eingefügte Adresse, eine Notiz oder eine Datei direkt in diesen Bereich zu legen.
            - **+ Gruppe** teilt eine große Sammlung in Gruppen — lass eine Karte auf einer davon fallen, um sie dorthin zu verschieben.

            ## Eine Seite finden
            - Die Suchleiste oben durchsucht alles auf einmal: was du gespeichert hast, die Tabs, die du offen hast, und wo du warst.
            - Gib eine Adresse ein, um sie zu öffnen, oder eine Frage, um das integrierte Modell des Browsers zu fragen.
            - ↑↓ zum Auswählen, Eingabe zum Öffnen, Esc zum Schließen.

            ## Ordnung halten
            - **Mit PIN schützen**: Ein gesperrter Bereich benennt nicht einmal seine Sammlungen, und sperrt sich wieder, sobald du dich entfernst.
            - **🔒 Schreibgeschützt** bewahrt eine fertige Sammlung vor einem versehentlichen Klick.
            - Die Einstellungen enthalten das Design, die Sprache und einen Export von allem nach CSV oder Lesezeichen.

            Benenne diese Sammlung um, oder lösche diese Notiz — all das gehört jetzt dir.
        """.trimIndent(),
    )
}

private object PtBrStrings : Strings {
    override val on = "Ativado"
    override val off = "Desativado"
    override val experimental = "experimental"
    override val settings = "Configurações"
    override val close = "Fechar"
    override val cancel = "Cancelar"
    override val save = "Salvar"
    override val about = "Sobre"
    override fun aboutVersion(version: String) = "Versão $version"
    override fun aboutCopyright(year: String) = "© $year Stramus"
    override val aboutHomepage = "stramus.space"

    override val expandSidebar = "Expandir painel"
    override val collapseSidebar = "Recolher painel"
    override val newSection = "+ Nova seção"
    override val sectionNamePrompt = "Nome da seção"
    override val sectionNameDefault = "Nova seção"
    override val collectionNamePrompt = "Nome da coleção"
    override val collectionNameDefault = "Nova coleção"
    override val renameHint = "Clique para recolher, clique duplo para renomear, arraste para reordenar"
    override val renameCollectionHint = "Clique duplo para renomear"
    override val untitled = "Sem título"

    override val newSectionHint = "Adicionar uma seção ao painel"
    override val addCollectionHint = "Adicionar uma coleção a esta seção"
    override val deleteSectionHint = "Excluir esta seção e as coleções nela contidas"
    override val deleteCollectionHint = "Excluir esta coleção e seus cartões"
    override val addCardSectionHint = "Adicionar um grupo a esta coleção"
    override val deleteCardSectionHint = "Excluir este grupo — seus cartões permanecem na coleção, sem grupo"
    override val addCardHint = "Adicionar um link — ou, pelo menu, uma nota ou um arquivo"
    override val deleteCardHint = "Excluir este cartão"
    override fun openAllHint(count: Int) = "Abrir os $count cartões em novas abas"

    override fun confirmDeleteSection(title: String, cards: Int) =
        "“$title” e suas coleções contêm $cards itens salvos. Excluir a seção?"
    override fun confirmDeleteCollection(title: String, cards: Int) =
        "“$title” contém $cards itens salvos. Excluir a coleção?"
    override fun confirmDeleteCardSection(title: String, cards: Int) =
        "“$title” contém $cards cartões. Excluir o grupo? Os cartões permanecem, sem grupo."
    override fun deletedSection(title: String) = "Seção “$title” excluída"
    override fun deletedCollection(title: String) = "Coleção “$title” excluída"
    override fun deletedCardSection(title: String) = "Grupo “$title” excluído"
    override fun deletedCard(title: String) = "“$title” excluído"
    override fun movedCard(title: String) = "“$title” movido"
    override val sortedCards = "Cartões ordenados"
    override val undo = "Desfazer"

    override val searchPlaceholder = "Buscar, digitar um endereço, ou perguntar…"

    override val hitsTopSites = "Abertos com frequência"
    override val hitsTabs = "Abas abertas"
    override val hitsCards = "Salvo"
    override val hitsHistory = "Histórico"
    override val hitsSites = "Sites"
    override val hitsCollections = "Coleções"

    override val hitSwitchToTab = "Ir para"
    override val hitOpenCollection = "Abrir"
    override val hitAskAi = "Perguntar"

    // O buscador é o do próprio navegador — o que estiver configurado —, então não é nomeado aqui.
    override fun hitWebSearch(query: String) = "Buscar “$query” na web"
    override fun hitOpenUrl(query: String) = "Abrir $query"
    override fun hitAskAiRow(assistant: String, query: String) = "Perguntar a $assistant: “$query”"

    override val forgetSite = "Parar de sugerir esta página"
    override val searchHints = "↑↓ escolher · Enter abrir · Alt+Enter buscar na web · ⌘/Ctrl+Enter todos os resultados · Esc fechar"

    override val aiChip = "IA"
    override val aiHeading = "Assistente"
    override val aiEmpty = "Pergunte sobre a coleção que você tem aberta, ou sobre qualquer outra coisa."
    override val aiPlaceholder = "Fazer outra pergunta…"
    override val aiSend = "Perguntar"
    override val aiThinking = "Pensando…"
    override val aiCopy = "Copiar"
    override val aiSaveNote = "Salvar como nota"
    override val aiUnavailable = "Este navegador não tem nenhum modelo embutido disponível."
    override val aiFailed = "O modelo não conseguiu responder."
    override fun aiDownloading(percent: Int) = "Baixando o modelo — $percent%. Isso acontece só uma vez."
    override val aiSystemPrompt = "Você é o assistente dentro do stramus, um gerenciador de favoritos e abas. " +
        "Responda de forma breve e direta, no idioma da pergunta. Markdown é bem-vindo."

    override val aiTriageSetting = "Organizar abas com o modelo embutido"
    override val aiTriageSettingHint = "Adiciona um botão a uma janela de abas: o modelo as lê e propõe " +
        "uma coleção para cada uma, para você conferir antes que algo seja salvo. Tudo permanece neste " +
        "computador. Leva um ou dois minutos numa janela grande, e deixa de fora o que não conseguir classificar."
    override val triageTabs = "Organizar em coleções"
    override val triageHeading = "Organizar abas em coleções"
    override val triageSummaryHeading = "Sobre o que foi esta sessão"
    override val triageSummaryTitle = "Resumo da sessão"
    override val triageNew = "nova"
    override fun triageNewHint(section: String) = "Essa coleção ainda não existe — ela será criada em “$section”."
    override val triageNewSectionHint = "Esse grupo ainda não existe nesta coleção — ele será criado."
    override val triageGroupHint = "Em qual seção do painel esta nova coleção será criada"
    override val triageSectionHint = "Em qual grupo da coleção esta aba vai entrar"
    override val triageNoSection = "Sem grupo"
    override fun triageProgress(done: Int, total: Int) = "Organizando sites — $done de $total…"
    override val triageUnsorted = "Não organizadas"
    override val triageUnsortedHint = "O modelo não teve nada a dizer sobre estas. Escolha uma coleção, ou deixe-as abertas."
    override val triageSkip = "Não salvar"
    override val triageMoveHint = "Em qual coleção esta aba vai entrar"
    override val triageDuplicate = "já salvo"
    override val triageDuplicateHint = "Esta página já está em uma coleção. Marque para salvá-la novamente."
    override fun triageRelated(site: String, count: Int) = "Já salvo de $site ($count):"
    override fun triageApply(count: Int, closesTabs: Boolean) =
        if (closesTabs) "Salvar ($count) e fechar" else "Salvar ($count)"
    override val aiTriageSystemPrompt = "Você organiza as abas abertas do navegador de um usuário em suas " +
        "coleções. Você recebe as abas e as coleções existentes. Para cada aba, responda com a única " +
        "coleção a que ela pertence — reutilize um nome existente sempre que a aba se encaixar nele, e só " +
        "invente um nome curto (uma ou duas palavras) quando não se encaixar em nenhum. Dentro de uma " +
        "coleção você pode nomear um grupo, reutilizando também os já existentes. Abas de um mesmo site " +
        "podem pertencer a coleções diferentes. Responda apenas com o JSON solicitado."

    override val aiSection = "IA"
    override val aiAssistant = "Assistente"
    override val aiAssistantHint = "Quem responde a uma pergunta feita pela barra de busca."
    override val aiProviderLocal = "No dispositivo"
    override fun aiWebChatHint(assistant: String) =
        "A pergunta abre $assistant nesta aba, já enviada. Ela é enviada aos servidores do $assistant — " +
            "diferente do modelo embutido, que responde aqui e mantém tudo neste computador."

    override val aiModel = "Modelo"
    override val aiModelReadyHint = "O modelo embutido do navegador. Roda neste computador — sem chave, e nada sai dele."
    override val aiModelDownloadableHint = "O navegador vai baixá-lo na primeira pergunta — algumas centenas de megabytes, uma única vez."
    override val aiModelDownloadingHint = "O navegador está baixando agora."
    override val aiModelNone = "Não disponível"
    override val aiModelNoneHint =
        "Este navegador não dá à página nenhum modelo embutido, então a busca não oferece perguntar a ele. " +
            "No Chrome ele está disponível para a extensão; uma página web comum precisa das flags para isso."
    override fun aiModelUnsupported(name: String) = "$name — indisponível"
    override val aiModelUnsupportedHint =
        "O navegador tem o modelo, mas não consegue executá-lo aqui: precisa de ~22 GB livres no disco que " +
            "contém o perfil do Chrome, e uma GPU com mais de 4 GB de memória."

    override fun resultsFor(query: String) = "Resultados para “$query”"
    override val noMatchingLinks = "Nenhum link correspondente."
    override val createCollectionToStart = "Crie uma coleção para começar a salvar links."
    override val sortLinks = "Ordenar os cartões deste grupo"
    override val sortMenuTitle = "Ordenar por"
    override val addCardSection = "Grupo"
    override val pasteUrl = "Colar uma URL"
    override val addLinkItem = "Link"
    override val addNoteItem = "Nota"
    override val addFileItem = "Arquivo"
    override val noLinksYet = "Ainda sem links — adicione um, ou arraste um até aqui."
    override val ungrouped = "Sem grupo"
    override val dragLinksHere = "Arraste links ou arquivos até aqui."
    override val editDescription = "Editar descrição"

    override fun filesTooLarge(names: List<String>, maxMb: Int) =
        "Não salvo — acima de $maxMb MB: ${names.joinToString(", ")}"

    override val protectSection = "Proteger com PIN"
    override val sectionProtection = "Proteção da seção"
    override val changePin = "Alterar PIN"
    override val removeProtection = "Remover proteção"
    override val lockNow = "Bloquear agora"
    override val lockedSection = "Protegida com PIN"
    override val unlockedSection = "Desbloqueada — clique para bloquear de novo"
    override val enterPinToView = "Digite o PIN para ver as coleções desta seção."
    override val pinPlaceholder = "PIN"
    override val unlock = "Desbloquear"
    override val wrongPin = "PIN incorreto."
    override val setPinHeading = "Proteger seção"
    override val changePinHeading = "Alterar PIN"
    override val newPinLabel = "Novo PIN"
    override val repeatPinLabel = "Repita o PIN"
    override val pinMismatch = "Os dois PINs não coincidem."
    override fun pinTooShort(min: Int) = "O PIN precisa ter pelo menos $min dígitos."
    override val pinNote = "O PIN esconde a seção inteira: suas coleções nem são nomeadas até que ele seja " +
        "digitado, e seus cartões ficam fora da busca e da exportação. Não há como redefinir um PIN " +
        "esquecido."

    override val makeReadOnlyHint = "Tornar somente leitura: nada poderá ser adicionado, alterado ou excluído aqui."
    override val allowEditing = "Permitir edição"
    override val allowEditingHint = "Permitir edição novamente."
    override val readOnlyBadge = "somente leitura"
    override val readOnlyHint = "Somente leitura: nada aqui pode ser adicionado, alterado ou excluído."

    override val security = "Segurança"
    override val autoLock = "Bloqueio automático"
    override val autoLockHint = "Bloqueia de novo as seções desbloqueadas após esse tempo sem atividade."
    override val autoLockNever = "Nunca"
    override fun autoLockMinutes(minutes: Int) = "$minutes min"

    override val openTabs = "Abas abertas"
    override val showTabs = "Mostrar abas abertas"
    override val hideTabs = "Ocultar abas abertas"
    override val noOpenTabs = "Nenhuma aba aberta para salvar."
    override val searchTabs = "Buscar nas abas…"
    override val noMatchingTabs = "Nenhuma aba correspondente."
    override val thisWindow = "Esta janela"
    override fun windowLabel(number: Int) = "Janela $number"
    override val closeTab = "Fechar aba"
    override val sortTabs = "Ordenar as abas desta janela"
    override fun saveTabsHint(count: Int, closing: Boolean) =
        "Salvar as abas desta janela ($count) na coleção aberta, sem grupo — " +
            if (closing) "e fechá-las" else "e deixá-las abertas"

    override fun confirmSaveTabs(count: Int, collection: String, closing: Boolean) =
        if (closing) "Salvar as abas desta janela ($count) em “$collection” e fechá-las?"
        else "Salvar as abas desta janela ($count) em “$collection”?"

    override val tabsSection = "Abas"
    override val closeSavedTabs = "Depois de salvar abas"
    override val closeSavedTabsHint =
        "O que acontece com as abas de uma janela depois de salvas em uma coleção."
    override val closeSavedTabsClose = "Fechá-las"
    override val closeSavedTabsKeep = "Deixá-las abertas"

    override val paneTabs = "Abas"
    override val paneHistory = "Histórico"
    override val searchHistory = "Buscar no histórico…"
    override val noHistory = "Nada no histórico ainda."
    override val noMatchingHistory = "Nada no histórico corresponde."
    override val today = "Hoje"
    override val yesterday = "Ontem"
    override val removeFromHistory = "Remover do histórico"

    override val emptyNote = "Nota vazia"
    override val fileLabel = "arquivo"
    override val renameCard = "Editar"
    override val cardNamePrompt = "Título do cartão"
    override val renameHeading = "Editar cartão"
    override val renameShowUrl = "Mostrar endereço"
    override val renameHideUrl = "Ocultar endereço"
    override val renameUrlPrompt = "Endereço"

    override val newNote = "Nova nota"
    override val editNote = "Editar nota"
    override val viewNote = "Nota"
    override val editNoteAction = "Editar"
    override val sectionDescription = "Descrição do grupo"
    override val titlePlaceholder = "Título"
    override val noteDefaultTitle = "Nota"
    override val notePlaceholder = "Comece a escrever…"
    override val toolBold = "Negrito"
    override val toolItalic = "Itálico"
    override val toolHighlight = "Destaque"
    override val toolCode = "Código"
    override val toolLink = "Link"
    override val toolHeading = "Título"
    override val toolList = "Lista com marcadores"
    override val toolListLabel = "Lista"
    override val highlightPlaceholder = "destaque"
    override val codePlaceholder = "código"
    override val linkUrlPrompt = "URL do link"
    override val draftRestored = "Rascunho não salvo restaurado"
    override val discardDraft = "Redefinir"

    override val addFile = "Adicionar arquivo"
    override val chooseFile = "Escolher um arquivo…"
    override val download = "Baixar"
    override val fileDefaultTitle = "Arquivo"
    override fun noPreviewFor(mime: String) = "Sem pré-visualização para $mime — use Baixar."

    override val appearance = "Aparência"
    override val theme = "Tema"
    override val themeHint = "Seguir o sistema, ou forçar dia/noite."
    override val themeAuto = "Automático"
    override val themeLight = "Claro"
    override val themeDark = "Escuro"
    override val accentColor = "Cor de destaque"
    override val accentColorHint = "A cor da marca por trás dos botões, da seleção e dos destaques."
    override val accentBlue = "Azul"
    override val accentPurple = "Roxo"
    override val accentGreen = "Verde"
    override val accentOrange = "Laranja"
    override val accentRose = "Rosa"
    override val language = "Idioma"
    override val languageHint = "O idioma da interface."
    override val cardUrls = "Endereços nos cartões"
    override val cardUrlsHint = "Se um cartão de link mostra seu endereço abaixo do título."
    override val cardUrlsShow = "Mostrar"
    override val cardUrlsHide = "Ocultar"
    override val groupsView = "Visualização das seções"
    override val groupsViewHint =
        "Mostrar as seções de uma coleção uma abaixo da outra, ou como pastas que abrem onde estão."
    override val groupsViewList = "Lista"
    override val groupsViewFolders = "Pastas"
    override val folderBack = "Voltar às pastas"
    override val swapSidebars = "Ordem dos painéis"
    override val swapSidebarsHint = "De que lado fica o painel de seções, em relação ao de abas/histórico."
    override val swapSidebarsLeft = "Seções à esquerda"
    override val swapSidebarsRight = "Seções à direita"
    override val tabsCardView = "Visualização das abas"
    override val tabsCardViewHint =
        "Mostrar as abas abertas em lista, ou em uma grade de cartões com a mesma largura do painel central."
    override val tabsCardViewList = "Lista"
    override val tabsCardViewCards = "Cartões"

    override val startupSection = "Inicialização"
    override val startView = "Ao abrir"
    override val startViewHint = "Qual coleção é exibida ao abrir o stramus. Uma coleção atrás de um PIN nunca " +
        "é ela — cada recarregamento bloqueia sua seção de novo."
    override val startViewLast = "Última aberta"
    override val startViewFirst = "Primeira coleção"

    override val dataSection = "Dados"

    override val export = "Exportar"
    override val exportHint = "Baixe todos os links salvos em todas as coleções. Uma seção ainda atrás de " +
        "seu PIN fica de fora."
    override val exportCsv = "Exportar CSV"
    override val exportBookmarks = "Exportar favoritos"

    override val import = "Importar"
    override val importHint = "Traga um arquivo de favoritos de qualquer navegador, ou um CSV exportado aqui. " +
        "Pastas viram seções, coleções e grupos; um link já salvo onde ele cairia é deixado como está."
    override val importFile = "Escolher um arquivo"
    override val importedTitle = "Importado"
    override fun importDone(added: Int, skipped: Int) = when (skipped) {
        0 -> "$added links importados."
        else -> "$added links importados; $skipped já estavam salvos."
    }
    override val importNothing = "Nenhum link para importar nesse arquivo."

    override val sortTitle = "Título A–Z"
    override val sortUrl = "URL"
    override val sortDomain = "Domínio"
    override val sortNewest = "Mais recentes primeiro"
    override val sortOldest = "Mais antigos primeiro"

    override val account = "Conta"
    override val accountSignedOutHint = "Entre para manter suas coleções em todos os navegadores que você usa. Tudo funciona sem conta — só que fica neste computador."
    override val signInAccount = "Entrar"
    override val signOut = "Sair"
    override val syncNow = "Sincronizar agora"
    override fun syncedAt(time: String) = "Sincronizado às $time"
    override fun conflictCopies(count: Int) =
        if (count == 1) "Uma nota foi editada em dois dispositivos ao mesmo tempo. As duas versões foram mantidas."
        else "$count notas foram editadas em dois dispositivos ao mesmo tempo. As duas versões de cada uma foram mantidas."
    override val joinAccountTitle = "Este navegador já tem coleções"
    override val joinAccountHint = "Você pode adicioná-las à conta, ou deixá-las aqui e usar o que a conta já tem."
    override val joinAccountKeep = "Adicioná-las à conta"
    override val joinAccountDiscard = "Usar as coleções da conta"
    override val exportAccountData = "Baixar meus dados"
    override val exportAccountDataHint = "Tudo o que o servidor guarda sobre esta conta, em JSON."
    override val exportAccountDataFailed = "Não foi possível baixar a exportação."
    override val deleteAccount = "Excluir conta"
    override val deleteAccountHint = "Apaga tudo o que o servidor guarda. O que está neste computador permanece."
    override val deleteAccountConfirm = "Excluir a conta e tudo o que o servidor guarda? Isso não pode ser desfeito."
    override val syncUsage = "Sincronizar estatísticas de navegação"
    override val syncUsageHint = "Quais páginas você abre e com que frequência — o que a busca usa para ordenar. Desativado significa que fica neste computador."
    override val optionOn = "Ativado"
    override val optionOff = "Desativado"
    override val signInWithGoogle = "Continuar com o Google"
    override val signInUnavailable = "O login não está configurado nesta versão. O app funciona sem conta, como sempre."
    override val serverUnavailable = "O servidor não está respondendo no momento. Isso precisa dele — tente de novo quando ele voltar."

    override val onboardingSignInTitle = "Entre para sincronizar em todos os lugares"
    override val onboardingSignInBody = "Suas coleções ficam apenas neste dispositivo até você entrar — depois, elas te acompanham automaticamente em cada navegador que você usar."
    override val onboardingSkip = "Depois"
    override val onboardingInstallTitle = "Instale a extensão"
    override val onboardingInstallBody = "O stramus funciona direto nesta aba, mas a extensão traz sua própria página de nova aba, salvar abas com um clique e uma busca sobre suas abas abertas e seu histórico — a experiência completa."
    override val onboardingInstallCta = "Instalar na Chrome Web Store"
    override val onboardingContinueInBrowser = "Continuar no navegador"
    override val onboardingOrganizeTitle = "Coleções, agrupadas em seções"
    override val onboardingOrganizeBody = "A barra lateral guarda suas seções; cada uma se abre nas suas coleções. Arraste uma aba, um link ou um arquivo para qualquer coleção para salvá-lo ali — nada sai do seu dispositivo a menos que você peça."
    override val onboardingSearchTitle = "Uma busca só para tudo"
    override val onboardingSearchBody = "A caixa de busca no topo encontra cartões salvos, abas abertas e histórico de navegação ao mesmo tempo — comece a digitar, e o stramus procura em tudo por você."
    override val onboardingBack = "Voltar"
    override val onboardingNext = "Próximo"
    override val onboardingGetStarted = "Começar"

    override val seed = StoreSeed(
        sectionTitle = "Principal",
        collectionTitle = "Primeiros passos",
        noteTitle = "Como usar o stramus",
        // Cada marcador é uma única linha: este é o markdown que `Markdown.kt` lê, e uma quebra de
        // linha dentro dele encerra a lista em vez de continuá-la.
        noteBody = """
            # Bem-vindo ao stramus

            O painel à esquerda tem **seções**, uma seção tem **coleções**, e uma coleção tem cartões — links, arquivos e notas como esta.

            ## Salvando uma página
            - Arraste uma aba do painel direito para uma coleção, ou use **⤓ Salvar abas abertas** para uma janela inteira de uma vez.
            - Passe o mouse sobre o título de uma seção e clique no seu **+** para adicionar um endereço colado, uma nota ou um arquivo direto nessa seção.
            - **+ Grupo** divide uma coleção grande em grupos — solte um cartão sobre um deles para movê-lo para lá.

            ## Encontrando uma página
            - A barra de busca no topo procura em tudo de uma vez: o que você salvou, as abas que você tem abertas e por onde você andou.
            - Digite um endereço para abri-lo, ou uma pergunta para consultar o modelo embutido do navegador.
            - ↑↓ para escolher, Enter para abrir, Esc para fechar.

            ## Mantendo tudo em ordem
            - **Proteger com PIN**: uma seção bloqueada nem mostra os nomes de suas coleções, e se bloqueia de novo quando você se afasta.
            - **🔒 Somente leitura** protege uma coleção pronta contra um deslize.
            - As configurações têm o tema, o idioma e uma exportação de tudo para CSV ou favoritos.

            Renomeie esta coleção, ou exclua esta nota — agora tudo isso é seu.
        """.trimIndent(),
    )
}

private object ZhCnStrings : Strings {
    override val on = "开"
    override val off = "关"
    override val experimental = "实验性"
    override val settings = "设置"
    override val close = "关闭"
    override val cancel = "取消"
    override val save = "保存"
    override val about = "关于"
    override fun aboutVersion(version: String) = "版本 $version"
    override fun aboutCopyright(year: String) = "© $year Stramus"
    override val aboutHomepage = "stramus.space"

    override val expandSidebar = "展开侧栏"
    override val collapseSidebar = "收起侧栏"
    override val newSection = "+ 新建分区"
    override val sectionNamePrompt = "分区名称"
    override val sectionNameDefault = "新建分区"
    override val collectionNamePrompt = "收藏夹名称"
    override val collectionNameDefault = "新建收藏夹"
    override val renameHint = "点击折叠，双击重命名，拖动排序"
    override val renameCollectionHint = "双击重命名"
    override val untitled = "未命名"

    override val newSectionHint = "在侧栏中添加一个分区"
    override val addCollectionHint = "在此分区中添加一个收藏夹"
    override val deleteSectionHint = "删除此分区及其中的收藏夹"
    override val deleteCollectionHint = "删除此收藏夹及其中的卡片"
    override val addCardSectionHint = "在此收藏夹中添加一个分组"
    override val deleteCardSectionHint = "删除此分组——其中的卡片仍保留在收藏夹中，只是不再分组"
    override val addCardHint = "添加一个链接——或从菜单中添加笔记或文件"
    override val deleteCardHint = "删除此卡片"
    override fun openAllHint(count: Int) = "在新标签页中打开全部 $count 张卡片"

    override fun confirmDeleteSection(title: String, cards: Int) =
        "“$title”及其收藏夹中共有 $cards 个已保存项目。删除该分区？"
    override fun confirmDeleteCollection(title: String, cards: Int) =
        "“$title”中有 $cards 个已保存项目。删除该收藏夹？"
    override fun confirmDeleteCardSection(title: String, cards: Int) =
        "“$title”中有 $cards 张卡片。删除该分组？卡片会保留，只是不再分组。"
    override fun deletedSection(title: String) = "分区“$title”已删除"
    override fun deletedCollection(title: String) = "收藏夹“$title”已删除"
    override fun deletedCardSection(title: String) = "分组“$title”已删除"
    override fun deletedCard(title: String) = "“$title”已删除"
    override fun movedCard(title: String) = "“$title”已移动"
    override val sortedCards = "卡片已排序"
    override val undo = "撤销"

    override val searchPlaceholder = "搜索、输入网址，或直接提问…"

    override val hitsTopSites = "常用网站"
    override val hitsTabs = "已打开的标签页"
    override val hitsCards = "已保存"
    override val hitsHistory = "历史记录"
    override val hitsSites = "网站"
    override val hitsCollections = "收藏夹"

    override val hitSwitchToTab = "切换"
    override val hitOpenCollection = "打开"
    override val hitAskAi = "提问"

    // 搜索引擎使用的是浏览器自身设置的那个，所以这里不具体命名。
    override fun hitWebSearch(query: String) = "在网络上搜索“$query”"
    override fun hitOpenUrl(query: String) = "打开 $query"
    override fun hitAskAiRow(assistant: String, query: String) = "向 $assistant 提问：“$query”"

    override val forgetSite = "不再推荐此页面"
    override val searchHints = "↑↓ 选择 · Enter 打开 · Alt+Enter 在网络中搜索 · ⌘/Ctrl+Enter 全部结果 · Esc 关闭"

    override val aiChip = "AI"
    override val aiHeading = "助手"
    override val aiEmpty = "可以问问当前打开的收藏夹，或者随便什么问题。"
    override val aiPlaceholder = "继续提问…"
    override val aiSend = "提问"
    override val aiThinking = "思考中…"
    override val aiCopy = "复制"
    override val aiSaveNote = "保存为笔记"
    override val aiUnavailable = "此浏览器没有可用的内置模型。"
    override val aiFailed = "模型无法回答。"
    override fun aiDownloading(percent: Int) = "正在下载模型——$percent%。此过程只会发生一次。"
    override val aiSystemPrompt = "你是 stramus（一款书签与标签页管理器）中的助手。" +
        "请用提问所使用的语言，简明扼要地回答。欢迎使用 Markdown。"

    override val aiTriageSetting = "用内置模型整理标签页"
    override val aiTriageSettingHint = "为标签页窗口添加一个按钮：模型会读取这些标签页，并为每一个提出一个" +
        "收藏夹建议，供你在保存前确认。一切都留在这台设备上。窗口较大时需要一两分钟，模型无法归类的会被略过。"
    override val triageTabs = "整理到收藏夹"
    override val triageHeading = "将标签页整理到收藏夹"
    override val triageSummaryHeading = "本次会话的内容概要"
    override val triageSummaryTitle = "会话摘要"
    override val triageNew = "新建"
    override fun triageNewHint(section: String) = "该收藏夹尚不存在——将在“$section”中创建。"
    override val triageNewSectionHint = "该收藏夹中尚无此分组——将会创建。"
    override val triageGroupHint = "这个新收藏夹会创建在侧栏的哪个分区中"
    override val triageSectionHint = "此标签页会归入收藏夹中的哪个分组"
    override val triageNoSection = "不分组"
    override fun triageProgress(done: Int, total: Int) = "正在整理网站——$done / $total…"
    override val triageUnsorted = "未整理"
    override val triageUnsortedHint = "模型对这些没有给出建议。请选择一个收藏夹，或者让它们保持打开。"
    override val triageSkip = "不保存"
    override val triageMoveHint = "此标签页会归入哪个收藏夹"
    override val triageDuplicate = "已保存"
    override val triageDuplicateHint = "此页面已在某个收藏夹中。勾选可再次保存。"
    override fun triageRelated(site: String, count: Int) = "已从 $site 保存（$count）："
    override fun triageApply(count: Int, closesTabs: Boolean) =
        if (closesTabs) "保存（$count）并关闭" else "保存（$count）"
    override val aiTriageSystemPrompt = "你需要把用户浏览器中打开的标签页整理到他们的收藏夹中。系统会给你提供" +
        "标签页列表和现有的收藏夹。对每个标签页，回答它所属的唯一一个收藏夹——只要合适就复用已有的名称，" +
        "只有在都不合适时才发明一个简短的新名称（一到两个词）。在收藏夹内，你也可以指定一个分组，同样优先" +
        "复用已有的分组。同一网站的多个标签页可以分属不同的收藏夹。只回答所需的 JSON，不要包含其他内容。"

    override val aiSection = "AI"
    override val aiAssistant = "助手"
    override val aiAssistantHint = "谁来回答从搜索框中提出的问题。"
    override val aiProviderLocal = "本机运行"
    override fun aiWebChatHint(assistant: String) =
        "问题会在此标签页中打开 $assistant，并已自动发送。它会被发送到 $assistant 的服务器——" +
            "这与本机运行的模型不同，后者在本地回答，一切都留在这台设备上。"

    override val aiModel = "模型"
    override val aiModelReadyHint = "浏览器内置的模型。运行在这台设备上——无需密钥，不会外传任何内容。"
    override val aiModelDownloadableHint = "浏览器会在第一次提问时下载它——几百兆字节，只需一次。"
    override val aiModelDownloadingHint = "浏览器正在下载它。"
    override val aiModelNone = "不可用"
    override val aiModelNoneHint =
        "此浏览器没有为页面提供内置模型，因此搜索不会提供提问选项。" +
            "在 Chrome 中，扩展程序可以使用它；普通网页则需要开启相应标志位。"
    override fun aiModelUnsupported(name: String) = "$name——不可用"
    override val aiModelUnsupportedHint =
        "浏览器拥有该模型，但无法在此运行：需要 Chrome 配置文件所在磁盘有约 22 GB 可用空间，" +
            "以及一块显存超过 4 GB 的显卡。"

    override fun resultsFor(query: String) = "“$query”的搜索结果"
    override val noMatchingLinks = "没有匹配的链接。"
    override val createCollectionToStart = "创建一个收藏夹即可开始保存链接。"
    override val sortLinks = "对该分组的卡片排序"
    override val sortMenuTitle = "排序方式"
    override val addCardSection = "分组"
    override val pasteUrl = "粘贴网址"
    override val addLinkItem = "链接"
    override val addNoteItem = "笔记"
    override val addFileItem = "文件"
    override val noLinksYet = "还没有链接——添加一个，或把它拖到这里。"
    override val ungrouped = "未分组"
    override val dragLinksHere = "把链接或文件拖到这里。"
    override val editDescription = "编辑描述"

    override fun filesTooLarge(names: List<String>, maxMb: Int) =
        "未保存——超过 $maxMb MB：${names.joinToString("、")}"

    override val protectSection = "用 PIN 码保护"
    override val sectionProtection = "分区保护"
    override val changePin = "修改 PIN 码"
    override val removeProtection = "取消保护"
    override val lockNow = "立即锁定"
    override val lockedSection = "已用 PIN 码保护"
    override val unlockedSection = "已解锁——点击重新锁定"
    override val enterPinToView = "输入 PIN 码即可查看该分区的收藏夹。"
    override val pinPlaceholder = "PIN 码"
    override val unlock = "解锁"
    override val wrongPin = "PIN 码错误。"
    override val setPinHeading = "保护分区"
    override val changePinHeading = "修改 PIN 码"
    override val newPinLabel = "新 PIN 码"
    override val repeatPinLabel = "再次输入 PIN 码"
    override val pinMismatch = "两次输入的 PIN 码不一致。"
    override fun pinTooShort(min: Int) = "PIN 码至少需要 $min 位数字。"
    override val pinNote = "PIN 码会隐藏整个分区：在输入之前，连收藏夹的名称都不会显示，其中的卡片也不会出现在" +
        "搜索和导出结果中。忘记的 PIN 码无法找回。"

    override val makeReadOnlyHint = "设为只读：此后无法在这里添加、修改或删除任何内容。"
    override val allowEditing = "允许编辑"
    override val allowEditingHint = "重新允许编辑。"
    override val readOnlyBadge = "只读"
    override val readOnlyHint = "只读：这里无法添加、修改或删除任何内容。"

    override val security = "安全"
    override val autoLock = "自动锁定"
    override val autoLockHint = "在无操作达到这段时间后，重新锁定已解锁的分区。"
    override val autoLockNever = "从不"
    override fun autoLockMinutes(minutes: Int) = "$minutes 分钟"

    override val openTabs = "已打开的标签页"
    override val showTabs = "显示已打开的标签页"
    override val hideTabs = "隐藏已打开的标签页"
    override val noOpenTabs = "没有可保存的已打开标签页。"
    override val searchTabs = "搜索标签页…"
    override val noMatchingTabs = "没有匹配的标签页。"
    override val thisWindow = "当前窗口"
    override fun windowLabel(number: Int) = "窗口 $number"
    override val closeTab = "关闭标签页"
    override val sortTabs = "对此窗口的标签页排序"
    override fun saveTabsHint(count: Int, closing: Boolean) =
        "将此窗口的标签页（$count 个）保存到已打开的收藏夹中，不分组——" +
            if (closing) "并关闭它们" else "并保持打开"

    override fun confirmSaveTabs(count: Int, collection: String, closing: Boolean) =
        if (closing) "将此窗口的标签页（$count 个）保存到“$collection”并关闭它们？"
        else "将此窗口的标签页（$count 个）保存到“$collection”？"

    override val tabsSection = "标签页"
    override val closeSavedTabs = "保存标签页之后"
    override val closeSavedTabsHint =
        "窗口中的标签页保存到收藏夹之后会发生什么。"
    override val closeSavedTabsClose = "关闭它们"
    override val closeSavedTabsKeep = "保持打开"

    override val paneTabs = "标签页"
    override val paneHistory = "历史记录"
    override val searchHistory = "搜索历史记录…"
    override val noHistory = "历史记录中暂时没有内容。"
    override val noMatchingHistory = "历史记录中没有匹配项。"
    override val today = "今天"
    override val yesterday = "昨天"
    override val removeFromHistory = "从历史记录中移除"

    override val emptyNote = "空白笔记"
    override val fileLabel = "文件"
    override val renameCard = "编辑"
    override val cardNamePrompt = "卡片标题"
    override val renameHeading = "编辑卡片"
    override val renameShowUrl = "显示网址"
    override val renameHideUrl = "隐藏网址"
    override val renameUrlPrompt = "网址"

    override val newNote = "新建笔记"
    override val editNote = "编辑笔记"
    override val viewNote = "笔记"
    override val editNoteAction = "编辑"
    override val sectionDescription = "分组描述"
    override val titlePlaceholder = "标题"
    override val noteDefaultTitle = "笔记"
    override val notePlaceholder = "开始输入…"
    override val toolBold = "加粗"
    override val toolItalic = "斜体"
    override val toolHighlight = "高亮"
    override val toolCode = "代码"
    override val toolLink = "链接"
    override val toolHeading = "标题"
    override val toolList = "项目符号列表"
    override val toolListLabel = "列表"
    override val highlightPlaceholder = "高亮文字"
    override val codePlaceholder = "代码"
    override val linkUrlPrompt = "链接网址"
    override val draftRestored = "已恢复未保存的草稿"
    override val discardDraft = "重置"

    override val addFile = "添加文件"
    override val chooseFile = "选择文件…"
    override val download = "下载"
    override val fileDefaultTitle = "文件"
    override fun noPreviewFor(mime: String) = "$mime 没有内置预览——请使用“下载”。"

    override val appearance = "外观"
    override val theme = "主题"
    override val themeHint = "跟随系统，或强制指定日间/夜间模式。"
    override val themeAuto = "自动"
    override val themeLight = "浅色"
    override val themeDark = "深色"
    override val accentColor = "强调色"
    override val accentColorHint = "按钮、选中状态和高亮背后所用的品牌色。"
    override val accentBlue = "蓝色"
    override val accentPurple = "紫色"
    override val accentGreen = "绿色"
    override val accentOrange = "橙色"
    override val accentRose = "玫红色"
    override val language = "语言"
    override val languageHint = "界面所使用的语言。"
    override val cardUrls = "卡片网址"
    override val cardUrlsHint = "链接卡片是否在标题下方显示其网址。"
    override val cardUrlsShow = "显示"
    override val cardUrlsHide = "隐藏"
    override val groupsView = "分组视图"
    override val groupsViewHint =
        "将收藏夹的分组逐个纵向排列显示，或显示为可就地展开的文件夹。"
    override val groupsViewList = "列表"
    override val groupsViewFolders = "文件夹"
    override val folderBack = "返回文件夹"
    override val swapSidebars = "侧栏顺序"
    override val swapSidebarsHint = "分区侧栏相对于标签页/历史记录侧栏所在的一侧。"
    override val swapSidebarsLeft = "分区在左"
    override val swapSidebarsRight = "分区在右"
    override val tabsCardView = "标签页视图"
    override val tabsCardViewHint =
        "以列表形式显示已打开的标签页，或以与中间面板同宽的卡片网格形式显示。"
    override val tabsCardViewList = "列表"
    override val tabsCardViewCards = "卡片"

    override val startupSection = "启动"
    override val startView = "打开时"
    override val startViewHint = "stramus 打开时显示哪个收藏夹。受 PIN 码保护的收藏夹永远不会是它——" +
        "每次重新加载都会重新锁定其所在分区。"
    override val startViewLast = "上次打开的"
    override val startViewFirst = "第一个收藏夹"

    override val dataSection = "数据"

    override val export = "导出"
    override val exportHint = "下载所有收藏夹中已保存的全部链接。仍处于 PIN 码保护下的分区不会包含在内。"
    override val exportCsv = "导出 CSV"
    override val exportBookmarks = "导出书签"

    override val import = "导入"
    override val importHint = "从任意浏览器导入书签文件，或导入在此处导出的 CSV。文件夹会变成分区、收藏夹" +
        "和分组；已保存在目标位置的链接会保持不变。"
    override val importFile = "选择文件"
    override val importedTitle = "已导入"
    override fun importDone(added: Int, skipped: Int) = when (skipped) {
        0 -> "已导入 $added 个链接。"
        else -> "已导入 $added 个链接；$skipped 个此前已保存。"
    }
    override val importNothing = "该文件中没有可导入的链接。"

    override val sortTitle = "标题 A–Z"
    override val sortUrl = "网址"
    override val sortDomain = "域名"
    override val sortNewest = "最新在前"
    override val sortOldest = "最旧在前"

    override val account = "账户"
    override val accountSignedOutHint = "登录以在你使用的每个浏览器中保留收藏夹。不登录账户一切照常可用——只是数据只留在本机。"
    override val signInAccount = "登录"
    override val signOut = "退出登录"
    override val syncNow = "立即同步"
    override fun syncedAt(time: String) = "已于 $time 同步"
    override fun conflictCopies(count: Int) =
        if (count == 1) "有一条笔记在两台设备上同时被编辑。两个版本都已保留。"
        else "有 $count 条笔记在两台设备上同时被编辑。每条笔记的两个版本都已保留。"
    override val joinAccountTitle = "此浏览器中已经有收藏夹"
    override val joinAccountHint = "你可以将它们加入账户，也可以留在本机，改用账户中已有的内容。"
    override val joinAccountKeep = "加入账户"
    override val joinAccountDiscard = "使用账户中的收藏夹"
    override val exportAccountData = "下载我的数据"
    override val exportAccountDataHint = "服务器保存的与此账户相关的所有数据，格式为 JSON。"
    override val exportAccountDataFailed = "无法下载导出内容。"
    override val deleteAccount = "删除账户"
    override val deleteAccountHint = "会清除服务器上保存的一切。本机上的内容会保留。"
    override val deleteAccountConfirm = "删除账户以及服务器上保存的一切？此操作无法撤销。"
    override val syncUsage = "同步浏览统计信息"
    override val syncUsageHint = "你打开了哪些页面、频率如何——这决定了搜索结果的排序方式。关闭表示这些信息只留在本机。"
    override val optionOn = "开"
    override val optionOff = "关"
    override val signInWithGoogle = "使用 Google 继续"
    override val signInUnavailable = "此版本未配置登录功能。应用无需账户即可照常使用。"
    override val serverUnavailable = "服务器目前没有响应。此操作需要它——请等它恢复后再试。"

    override val onboardingSignInTitle = "登录以在各处同步"
    override val onboardingSignInBody = "在你登录之前，收藏夹只保存在这台设备上——登录后，它们会自动同步到你使用的每个浏览器。"
    override val onboardingSkip = "以后再说"
    override val onboardingInstallTitle = "安装扩展程序"
    override val onboardingInstallBody = "stramus 在当前标签页里就能使用，但扩展程序还提供专属的新标签页、一键保存标签页，以及对已打开标签页和历史记录的搜索——完整体验。"
    override val onboardingInstallCta = "从 Chrome 网上应用店安装"
    override val onboardingContinueInBrowser = "在浏览器中继续"
    override val onboardingOrganizeTitle = "收藏夹按分组归入板块"
    override val onboardingOrganizeBody = "侧边栏保存着你的板块，每个板块下都有它自己的收藏夹。把标签页、链接或文件拖入任意收藏夹即可保存——除非你主动操作，否则不会离开你的设备。"
    override val onboardingSearchTitle = "一个搜索框搞定一切"
    override val onboardingSearchBody = "顶部的搜索框会同时查找已保存的卡片、打开的标签页和浏览历史——开始输入，stramus 会替你找遍所有地方。"
    override val onboardingBack = "上一步"
    override val onboardingNext = "下一步"
    override val onboardingGetStarted = "开始使用"

    override val seed = StoreSeed(
        sectionTitle = "主分区",
        collectionTitle = "快速上手",
        noteTitle = "如何使用 stramus",
        // 每个要点都是单独一行：这里的 markdown 由 `Markdown.kt` 解析，要点内部的换行会结束
        // 该列表，而不是让它继续。
        noteBody = """
            # 欢迎使用 stramus

            左侧侧栏包含**分区**，一个分区包含**收藏夹**，一个收藏夹则包含卡片——链接、文件，以及像这样的笔记。

            ## 保存一个页面
            - 把右侧侧栏中的标签页拖到某个收藏夹上，或使用**⤓ 保存已打开的标签页**一次性保存整个窗口。
            - 把鼠标移到分区标题上，点击其 **+** 号，即可将粘贴的网址、笔记或文件直接添加到该分区。
            - **+ 分组**可以把一个较大的收藏夹拆分成若干分组——把卡片拖到某个分组上即可将其移入其中。

            ## 查找一个页面
            - 顶部的搜索框会同时搜索所有内容：你保存过的、当前打开的标签页，以及你去过的地方。
            - 输入网址即可打开，或输入问题以询问浏览器内置的模型。
            - ↑↓ 选择，Enter 打开，Esc 关闭。

            ## 保持整洁
            - **用 PIN 码保护**：锁定的分区甚至不会显示其收藏夹的名称，且在你离开后会自动重新锁定。
            - **🔒 只读**可以保护已经整理完毕的收藏夹，避免误操作。
            - 设置中包含主题、语言，以及将全部内容导出为 CSV 或书签的选项。

            重命名这个收藏夹，或删除这条笔记——现在这一切都归你了。
        """.trimIndent(),
    )
}

private object JaStrings : Strings {
    override val on = "オン"
    override val off = "オフ"
    override val experimental = "実験的機能"
    override val settings = "設定"
    override val close = "閉じる"
    override val cancel = "キャンセル"
    override val save = "保存"
    override val about = "このアプリについて"
    override fun aboutVersion(version: String) = "バージョン $version"
    override fun aboutCopyright(year: String) = "© $year Stramus"
    override val aboutHomepage = "stramus.space"

    override val expandSidebar = "サイドバーを展開"
    override val collapseSidebar = "サイドバーを折りたたむ"
    override val newSection = "+ 新しいセクション"
    override val sectionNamePrompt = "セクション名"
    override val sectionNameDefault = "新しいセクション"
    override val collectionNamePrompt = "コレクション名"
    override val collectionNameDefault = "新しいコレクション"
    override val renameHint = "クリックで折りたたみ、ダブルクリックで名前を変更、ドラッグで並べ替え"
    override val renameCollectionHint = "ダブルクリックで名前を変更"
    override val untitled = "無題"

    override val newSectionHint = "サイドバーにセクションを追加"
    override val addCollectionHint = "このセクションにコレクションを追加"
    override val deleteSectionHint = "このセクションと中のコレクションを削除"
    override val deleteCollectionHint = "このコレクションとカードを削除"
    override val addCardSectionHint = "このコレクションにグループを追加"
    override val deleteCardSectionHint = "このグループを削除——カードはグループなしでコレクションに残ります"
    override val addCardHint = "リンクを追加——またはメニューからメモやファイルを追加"
    override val deleteCardHint = "このカードを削除"
    override fun openAllHint(count: Int) = "$count 枚のカードすべてを新しいタブで開く"

    override fun confirmDeleteSection(title: String, cards: Int) =
        "「$title」とそのコレクションには $cards 件の保存済み項目があります。セクションを削除しますか？"
    override fun confirmDeleteCollection(title: String, cards: Int) =
        "「$title」には $cards 件の保存済み項目があります。コレクションを削除しますか？"
    override fun confirmDeleteCardSection(title: String, cards: Int) =
        "「$title」には $cards 枚のカードがあります。グループを削除しますか？カードはグループなしで残ります。"
    override fun deletedSection(title: String) = "セクション「$title」を削除しました"
    override fun deletedCollection(title: String) = "コレクション「$title」を削除しました"
    override fun deletedCardSection(title: String) = "グループ「$title」を削除しました"
    override fun deletedCard(title: String) = "「$title」を削除しました"
    override fun movedCard(title: String) = "「$title」を移動しました"
    override val sortedCards = "カードを並べ替えました"
    override val undo = "元に戻す"

    override val searchPlaceholder = "検索、アドレスの入力、または質問…"

    override val hitsTopSites = "よく開くサイト"
    override val hitsTabs = "開いているタブ"
    override val hitsCards = "保存済み"
    override val hitsHistory = "履歴"
    override val hitsSites = "サイト"
    override val hitsCollections = "コレクション"

    override val hitSwitchToTab = "切り替え"
    override val hitOpenCollection = "開く"
    override val hitAskAi = "質問する"

    // 検索エンジンはブラウザ自体の設定によるものなので、ここでは名前を挙げていません。
    override fun hitWebSearch(query: String) = "「$query」をウェブで検索"
    override fun hitOpenUrl(query: String) = "$query を開く"
    override fun hitAskAiRow(assistant: String, query: String) = "$assistant に質問：「$query」"

    override val forgetSite = "このページを今後表示しない"
    override val searchHints = "↑↓ 選択 · Enter 開く · Alt+Enter ウェブ検索 · ⌘/Ctrl+Enter すべての結果 · Esc 閉じる"

    override val aiChip = "AI"
    override val aiHeading = "アシスタント"
    override val aiEmpty = "開いているコレクションについて、あるいは何でも質問してください。"
    override val aiPlaceholder = "続けて質問…"
    override val aiSend = "質問する"
    override val aiThinking = "考え中…"
    override val aiCopy = "コピー"
    override val aiSaveNote = "メモとして保存"
    override val aiUnavailable = "このブラウザには利用可能な内蔵モデルがありません。"
    override val aiFailed = "モデルは応答できませんでした。"
    override fun aiDownloading(percent: Int) = "モデルをダウンロード中——$percent%。これは一度だけ発生します。"
    override val aiSystemPrompt = "あなたは stramus（ブックマークとタブの管理アプリ）内のアシスタントです。" +
        "質問された言語で、簡潔に要点を答えてください。Markdown を使って構いません。"

    override val aiTriageSetting = "内蔵モデルでタブを整理する"
    override val aiTriageSettingHint = "タブのウィンドウにボタンを追加します。モデルがタブを読み取り、" +
        "それぞれに対してコレクションを提案し、保存前に確認できます。すべてこの端末内で処理されます。" +
        "大きなウィンドウでは1、2分かかり、分類できないものは除外されます。"
    override val triageTabs = "コレクションに整理"
    override val triageHeading = "タブをコレクションに整理"
    override val triageSummaryHeading = "このセッションの内容"
    override val triageSummaryTitle = "セッションの要約"
    override val triageNew = "新規"
    override fun triageNewHint(section: String) = "このコレクションはまだ存在しません——「$section」内に作成されます。"
    override val triageNewSectionHint = "このコレクションにはまだこのグループがありません——作成されます。"
    override val triageGroupHint = "この新しいコレクションをサイドバーのどのセクションに作成するか"
    override val triageSectionHint = "このタブをコレクション内のどのグループに入れるか"
    override val triageNoSection = "グループなし"
    override fun triageProgress(done: Int, total: Int) = "サイトを整理中——$done / $total…"
    override val triageUnsorted = "未整理"
    override val triageUnsortedHint = "モデルはこれらについて提案がありませんでした。コレクションを選ぶか、開いたままにしてください。"
    override val triageSkip = "保存しない"
    override val triageMoveHint = "このタブをどのコレクションに入れるか"
    override val triageDuplicate = "保存済み"
    override val triageDuplicateHint = "このページはすでにコレクションにあります。チェックすると再度保存されます。"
    override fun triageRelated(site: String, count: Int) = "$site からすでに保存済み（$count 件）："
    override fun triageApply(count: Int, closesTabs: Boolean) =
        if (closesTabs) "保存（$count）して閉じる" else "保存（$count）"
    override val aiTriageSystemPrompt = "あなたはユーザーのブラウザで開いているタブを、そのコレクションに整理します。" +
        "タブと既存のコレクションが与えられます。各タブについて、それが属する唯一のコレクションを答えてください——" +
        "タブに合う既存の名前があればそれを使い、どれにも合わない場合のみ短い新しい名前（1、2語）を考えてください。" +
        "コレクション内ではグループを指定でき、既存のグループも同様に再利用できます。同じサイトの複数のタブが" +
        "異なるコレクションに属してもかまいません。求められた JSON のみを回答してください。"

    override val aiSection = "AI"
    override val aiAssistant = "アシスタント"
    override val aiAssistantHint = "検索ボックスから質問したときに誰が答えるか。"
    override val aiProviderLocal = "端末内"
    override fun aiWebChatHint(assistant: String) =
        "この質問はこのタブで $assistant を開き、すでに送信された状態になります。" +
            "これは $assistant のサーバーに送信されます——端末内で応答しすべてをこの端末に保つ内蔵モデルとは異なります。"

    override val aiModel = "モデル"
    override val aiModelReadyHint = "ブラウザに内蔵されたモデルです。この端末上で動作し——キーは不要で、外部には何も送信されません。"
    override val aiModelDownloadableHint = "最初の質問時にブラウザがダウンロードします——数百メガバイト、一度だけです。"
    override val aiModelDownloadingHint = "ブラウザが現在ダウンロード中です。"
    override val aiModelNone = "利用不可"
    override val aiModelNoneHint =
        "このブラウザはページに内蔵モデルを提供していないため、検索でも質問する選択肢は表示されません。" +
            "Chrome では拡張機能から利用できます。通常のウェブページではフラグの設定が必要です。"
    override fun aiModelUnsupported(name: String) = "$name ——利用不可"
    override val aiModelUnsupportedHint =
        "ブラウザにはモデルがありますが、ここでは実行できません：Chrome プロファイルのあるドライブに約22GBの" +
            "空き容量、そして4GB以上のメモリを持つGPUが必要です。"

    override fun resultsFor(query: String) = "「$query」の検索結果"
    override val noMatchingLinks = "一致するリンクはありません。"
    override val createCollectionToStart = "コレクションを作成してリンクの保存を始めましょう。"
    override val sortLinks = "このグループのカードを並べ替え"
    override val sortMenuTitle = "並べ替え"
    override val addCardSection = "グループ"
    override val pasteUrl = "URLを貼り付け"
    override val addLinkItem = "リンク"
    override val addNoteItem = "メモ"
    override val addFileItem = "ファイル"
    override val noLinksYet = "まだリンクがありません——追加するか、ここにドラッグしてください。"
    override val ungrouped = "グループなし"
    override val dragLinksHere = "リンクやファイルをここにドラッグしてください。"
    override val editDescription = "説明を編集"

    override fun filesTooLarge(names: List<String>, maxMb: Int) =
        "保存されませんでした——$maxMb MBを超えています：${names.joinToString("、")}"

    override val protectSection = "PINで保護"
    override val sectionProtection = "セクションの保護"
    override val changePin = "PINを変更"
    override val removeProtection = "保護を解除"
    override val lockNow = "今すぐロック"
    override val lockedSection = "PINで保護されています"
    override val unlockedSection = "ロック解除中——クリックで再びロック"
    override val enterPinToView = "PINを入力するとこのセクションのコレクションが表示されます。"
    override val pinPlaceholder = "PIN"
    override val unlock = "ロック解除"
    override val wrongPin = "PINが正しくありません。"
    override val setPinHeading = "セクションを保護"
    override val changePinHeading = "PINを変更"
    override val newPinLabel = "新しいPIN"
    override val repeatPinLabel = "PINをもう一度入力"
    override val pinMismatch = "2つのPINが一致しません。"
    override fun pinTooShort(min: Int) = "PINは $min 桁以上である必要があります。"
    override val pinNote = "PINはセクション全体を隠します：入力されるまでコレクションの名前すら表示されず、" +
        "そのカードは検索やエクスポートの対象になりません。忘れたPINをリセットする方法はありません。"

    override val makeReadOnlyHint = "読み取り専用にする：ここでは何も追加・変更・削除できなくなります。"
    override val allowEditing = "編集を許可"
    override val allowEditingHint = "再び編集を許可します。"
    override val readOnlyBadge = "読み取り専用"
    override val readOnlyHint = "読み取り専用：ここでは何も追加・変更・削除できません。"

    override val security = "セキュリティ"
    override val autoLock = "自動ロック"
    override val autoLockHint = "この時間操作がないと、ロック解除されたセクションを再びロックします。"
    override val autoLockNever = "しない"
    override fun autoLockMinutes(minutes: Int) = "$minutes 分"

    override val openTabs = "開いているタブ"
    override val showTabs = "開いているタブを表示"
    override val hideTabs = "開いているタブを隠す"
    override val noOpenTabs = "保存できる開いているタブがありません。"
    override val searchTabs = "タブを検索…"
    override val noMatchingTabs = "一致するタブはありません。"
    override val thisWindow = "このウィンドウ"
    override fun windowLabel(number: Int) = "ウィンドウ $number"
    override val closeTab = "タブを閉じる"
    override val sortTabs = "このウィンドウのタブを並べ替え"
    override fun saveTabsHint(count: Int, closing: Boolean) =
        "このウィンドウのタブ（$count 個）を開いているコレクションにグループなしで保存——" +
            if (closing) "して閉じます" else "して開いたままにします"

    override fun confirmSaveTabs(count: Int, collection: String, closing: Boolean) =
        if (closing) "このウィンドウのタブ（$count 個）を「$collection」に保存して閉じますか？"
        else "このウィンドウのタブ（$count 個）を「$collection」に保存しますか？"

    override val tabsSection = "タブ"
    override val closeSavedTabs = "タブを保存した後"
    override val closeSavedTabsHint =
        "ウィンドウのタブがコレクションに保存された後どうなるか。"
    override val closeSavedTabsClose = "閉じる"
    override val closeSavedTabsKeep = "開いたままにする"

    override val paneTabs = "タブ"
    override val paneHistory = "履歴"
    override val searchHistory = "履歴を検索…"
    override val noHistory = "まだ履歴がありません。"
    override val noMatchingHistory = "一致する履歴はありません。"
    override val today = "今日"
    override val yesterday = "昨日"
    override val removeFromHistory = "履歴から削除"

    override val emptyNote = "空のメモ"
    override val fileLabel = "ファイル"
    override val renameCard = "編集"
    override val cardNamePrompt = "カードのタイトル"
    override val renameHeading = "カードを編集"
    override val renameShowUrl = "アドレスを表示"
    override val renameHideUrl = "アドレスを隠す"
    override val renameUrlPrompt = "アドレス"

    override val newNote = "新しいメモ"
    override val editNote = "メモを編集"
    override val viewNote = "メモ"
    override val editNoteAction = "編集"
    override val sectionDescription = "グループの説明"
    override val titlePlaceholder = "タイトル"
    override val noteDefaultTitle = "メモ"
    override val notePlaceholder = "入力を開始…"
    override val toolBold = "太字"
    override val toolItalic = "斜体"
    override val toolHighlight = "ハイライト"
    override val toolCode = "コード"
    override val toolLink = "リンク"
    override val toolHeading = "見出し"
    override val toolList = "箇条書きリスト"
    override val toolListLabel = "リスト"
    override val highlightPlaceholder = "ハイライト"
    override val codePlaceholder = "コード"
    override val linkUrlPrompt = "リンクURL"
    override val draftRestored = "未保存の下書きを復元しました"
    override val discardDraft = "リセット"

    override val addFile = "ファイルを追加"
    override val chooseFile = "ファイルを選択…"
    override val download = "ダウンロード"
    override val fileDefaultTitle = "ファイル"
    override fun noPreviewFor(mime: String) = "$mime のインラインプレビューはありません——ダウンロードを使ってください。"

    override val appearance = "外観"
    override val theme = "テーマ"
    override val themeHint = "システムに従うか、昼/夜を固定します。"
    override val themeAuto = "自動"
    override val themeLight = "ライト"
    override val themeDark = "ダーク"
    override val accentColor = "アクセントカラー"
    override val accentColorHint = "ボタン、選択状態、ハイライトの背後にあるブランドカラー。"
    override val accentBlue = "青"
    override val accentPurple = "紫"
    override val accentGreen = "緑"
    override val accentOrange = "オレンジ"
    override val accentRose = "ローズ"
    override val language = "言語"
    override val languageHint = "インターフェースの言語。"
    override val cardUrls = "カードのアドレス"
    override val cardUrlsHint = "リンクカードにタイトルの下にアドレスを表示するかどうか。"
    override val cardUrlsShow = "表示"
    override val cardUrlsHide = "非表示"
    override val groupsView = "セクション表示"
    override val groupsViewHint =
        "コレクションのセクションを縦に並べて表示するか、その場で開くフォルダーとして表示するか。"
    override val groupsViewList = "リスト"
    override val groupsViewFolders = "フォルダー"
    override val folderBack = "フォルダーに戻る"
    override val swapSidebars = "サイドバーの順序"
    override val swapSidebarsHint = "セクションのサイドバーがタブ/履歴のサイドバーに対してどちら側にあるか。"
    override val swapSidebarsLeft = "セクションを左に"
    override val swapSidebarsRight = "セクションを右に"
    override val tabsCardView = "タブの表示"
    override val tabsCardViewHint =
        "開いているタブをリストで表示するか、中央パネルと同じ幅のカードグリッドで表示するか。"
    override val tabsCardViewList = "リスト"
    override val tabsCardViewCards = "カード"

    override val startupSection = "起動時"
    override val startView = "開いたとき"
    override val startViewHint = "stramus を開いたときにどのコレクションを表示するか。PINで保護された" +
        "コレクションが表示されることはありません——再読み込みのたびにそのセクションは再びロックされます。"
    override val startViewLast = "最後に開いたもの"
    override val startViewFirst = "最初のコレクション"

    override val dataSection = "データ"

    override val export = "エクスポート"
    override val exportHint = "すべてのコレクションから保存済みのリンクをすべてダウンロードします。" +
        "PINがまだ入力されていないセクションは除外されます。"
    override val exportCsv = "CSVをエクスポート"
    override val exportBookmarks = "ブックマークをエクスポート"

    override val import = "インポート"
    override val importHint = "任意のブラウザのブックマークファイル、またはここでエクスポートしたCSVを" +
        "取り込みます。フォルダーはセクション、コレクション、グループになります。すでに保存先にあるリンクは" +
        "そのままにされます。"
    override val importFile = "ファイルを選択"
    override val importedTitle = "インポート済み"
    override fun importDone(added: Int, skipped: Int) = when (skipped) {
        0 -> "$added 件のリンクをインポートしました。"
        else -> "$added 件のリンクをインポートしました。$skipped 件はすでに保存済みでした。"
    }
    override val importNothing = "このファイルにはインポートできるリンクがありません。"

    override val sortTitle = "タイトル A–Z"
    override val sortUrl = "URL"
    override val sortDomain = "ドメイン"
    override val sortNewest = "新しい順"
    override val sortOldest = "古い順"

    override val account = "アカウント"
    override val accountSignedOutHint = "サインインすると、使用するすべてのブラウザでコレクションを保持できます。アカウントがなくても、この端末に保存されたまますべて機能します。"
    override val signInAccount = "サインイン"
    override val signOut = "サインアウト"
    override val syncNow = "今すぐ同期"
    override fun syncedAt(time: String) = "$time に同期しました"
    override fun conflictCopies(count: Int) =
        if (count == 1) "1件のメモが2台の端末で同時に編集されました。両方のバージョンが保持されました。"
        else "$count 件のメモが2台の端末で同時に編集されました。それぞれ両方のバージョンが保持されました。"
    override val joinAccountTitle = "このブラウザにはすでにコレクションがあります"
    override val joinAccountHint = "アカウントに追加するか、ここに残してアカウントにすでにある内容を使うか選べます。"
    override val joinAccountKeep = "アカウントに追加する"
    override val joinAccountDiscard = "アカウントのコレクションを使う"
    override val exportAccountData = "自分のデータをダウンロード"
    override val exportAccountDataHint = "サーバーがこのアカウントについて保持しているすべてのデータをJSON形式で。"
    override val exportAccountDataFailed = "エクスポートをダウンロードできませんでした。"
    override val deleteAccount = "アカウントを削除"
    override val deleteAccountHint = "サーバーが保持しているすべてを消去します。この端末上のものは残ります。"
    override val deleteAccountConfirm = "アカウントとサーバーが保持しているすべてを削除しますか？この操作は元に戻せません。"
    override val syncUsage = "閲覧統計を同期"
    override val syncUsageHint = "どのページをどのくらいの頻度で開いたか——検索の並び順に使われます。オフにするとこの端末内にとどまります。"
    override val optionOn = "オン"
    override val optionOff = "オフ"
    override val signInWithGoogle = "Googleで続ける"
    override val signInUnavailable = "このビルドではサインインが設定されていません。これまで通り、アプリはアカウントなしで動作します。"
    override val serverUnavailable = "現在サーバーが応答していません。これにはサーバーが必要です——復旧後にもう一度お試しください。"

    override val onboardingSignInTitle = "サインインしてどこでも同期"
    override val onboardingSignInBody = "サインインするまで、コレクションはこの端末だけに残ります。サインインすれば、使用するすべてのブラウザに自動的に反映されます。"
    override val onboardingSkip = "あとで"
    override val onboardingInstallTitle = "拡張機能をインストール"
    override val onboardingInstallBody = "stramus はこのタブでそのまま使えますが、拡張機能を入れると専用の新規タブページ、ワンクリックでのタブ保存、開いているタブと履歴を対象にした検索が使えます——フル機能の体験です。"
    override val onboardingInstallCta = "Chromeウェブストアからインストール"
    override val onboardingContinueInBrowser = "ブラウザで続ける"
    override val onboardingOrganizeTitle = "コレクションをセクションにまとめる"
    override val onboardingOrganizeBody = "サイドバーにはセクションが並び、それぞれがコレクションを開きます。タブやリンク、ファイルを任意のコレクションにドラッグすると保存されます——自分から操作しない限り、何もこの端末の外へは出ません。"
    override val onboardingSearchTitle = "ひとつの検索ですべてを"
    override val onboardingSearchBody = "上部の検索ボックスは、保存したカード・開いているタブ・閲覧履歴を同時に検索します。入力を始めるだけで、stramus がすべてを探してくれます。"
    override val onboardingBack = "戻る"
    override val onboardingNext = "次へ"
    override val onboardingGetStarted = "はじめる"

    override val seed = StoreSeed(
        sectionTitle = "メイン",
        collectionTitle = "はじめに",
        noteTitle = "stramus の使い方",
        // 各箇条書きは1行です：ここに書かれたMarkdownは `Markdown.kt` が読み込むもので、
        // 途中で改行するとリストが続くのではなく終わってしまいます。
        noteBody = """
            # stramus へようこそ

            左のサイドバーには**セクション**があり、セクションには**コレクション**があり、コレクションにはカード——リンク、ファイル、そしてこのようなメモ——が入ります。

            ## ページを保存する
            - 右のサイドバーからタブをコレクションにドラッグするか、**⤓ 開いているタブを保存**でウィンドウ全体を一度に保存できます。
            - セクションのヘッダーにカーソルを合わせて **+** を押すと、貼り付けたアドレスやメモ、ファイルをそのセクションに直接追加できます。
            - **+ グループ**は大きなコレクションをグループに分割します——カードをグループにドロップすると移動します。

            ## ページを見つける
            - 上部の検索ボックスは、保存したもの、開いているタブ、これまでに訪れた場所をすべて一度に検索します。
            - アドレスを入力すると開き、質問を入力するとブラウザ内蔵モデルに質問できます。
            - ↑↓ で選択、Enter で開く、Esc で閉じる。

            ## 整理しておく
            - **PINで保護**：ロックされたセクションはコレクションの名前すら表示せず、離れると再びロックされます。
            - **🔒 読み取り専用**は、完成したコレクションをうっかり触ってしまうことから守ります。
            - 設定にはテーマ、言語、そしてCSVやブックマークへのエクスポートがあります。

            このコレクションの名前を変更するか、このメモを削除してください——ここからはすべてあなたのものです。
        """.trimIndent(),
    )
}

private object KoStrings : Strings {
    override val on = "켜짐"
    override val off = "꺼짐"
    override val experimental = "실험적 기능"
    override val settings = "설정"
    override val close = "닫기"
    override val cancel = "취소"
    override val save = "저장"
    override val about = "정보"
    override fun aboutVersion(version: String) = "버전 $version"
    override fun aboutCopyright(year: String) = "© $year Stramus"
    override val aboutHomepage = "stramus.space"

    override val expandSidebar = "사이드바 펼치기"
    override val collapseSidebar = "사이드바 접기"
    override val newSection = "+ 새 섹션"
    override val sectionNamePrompt = "섹션 이름"
    override val sectionNameDefault = "새 섹션"
    override val collectionNamePrompt = "컬렉션 이름"
    override val collectionNameDefault = "새 컬렉션"
    override val renameHint = "클릭하면 접히고, 더블클릭하면 이름을 바꾸고, 드래그하면 순서를 바꿀 수 있습니다"
    override val renameCollectionHint = "더블클릭하면 이름을 바꿀 수 있습니다"
    override val untitled = "제목 없음"

    override val newSectionHint = "사이드바에 섹션 추가"
    override val addCollectionHint = "이 섹션에 컬렉션 추가"
    override val deleteSectionHint = "이 섹션과 그 안의 컬렉션 삭제"
    override val deleteCollectionHint = "이 컬렉션과 카드 삭제"
    override val addCardSectionHint = "이 컬렉션에 그룹 추가"
    override val deleteCardSectionHint = "이 그룹 삭제——카드는 그룹 없이 컬렉션에 남습니다"
    override val addCardHint = "링크 추가——또는 메뉴에서 메모나 파일 추가"
    override val deleteCardHint = "이 카드 삭제"
    override fun openAllHint(count: Int) = "카드 $count 개를 모두 새 탭에서 열기"

    override fun confirmDeleteSection(title: String, cards: Int) =
        "“$title”와(과) 그 컬렉션에 저장된 항목이 $cards 개 있습니다. 섹션을 삭제할까요?"
    override fun confirmDeleteCollection(title: String, cards: Int) =
        "“$title”에 저장된 항목이 $cards 개 있습니다. 컬렉션을 삭제할까요?"
    override fun confirmDeleteCardSection(title: String, cards: Int) =
        "“$title”에 카드가 $cards 개 있습니다. 그룹을 삭제할까요? 카드는 그룹 없이 남습니다."
    override fun deletedSection(title: String) = "섹션 “$title”을(를) 삭제했습니다"
    override fun deletedCollection(title: String) = "컬렉션 “$title”을(를) 삭제했습니다"
    override fun deletedCardSection(title: String) = "그룹 “$title”을(를) 삭제했습니다"
    override fun deletedCard(title: String) = "“$title”을(를) 삭제했습니다"
    override fun movedCard(title: String) = "“$title”을(를) 이동했습니다"
    override val sortedCards = "카드를 정렬했습니다"
    override val undo = "실행 취소"

    override val searchPlaceholder = "검색, 주소 입력, 또는 질문…"

    override val hitsTopSites = "자주 방문한 사이트"
    override val hitsTabs = "열린 탭"
    override val hitsCards = "저장됨"
    override val hitsHistory = "기록"
    override val hitsSites = "사이트"
    override val hitsCollections = "컬렉션"

    override val hitSwitchToTab = "전환"
    override val hitOpenCollection = "열기"
    override val hitAskAi = "질문"

    // 검색 엔진은 브라우저 자체에 설정된 것을 사용하므로 여기서는 이름을 명시하지 않습니다.
    override fun hitWebSearch(query: String) = "웹에서 “$query” 검색"
    override fun hitOpenUrl(query: String) = "$query 열기"
    override fun hitAskAiRow(assistant: String, query: String) = "$assistant 에게 질문: “$query”"

    override val forgetSite = "이 페이지 그만 추천하기"
    override val searchHints = "↑↓ 선택 · Enter 열기 · Alt+Enter 웹 검색 · ⌘/Ctrl+Enter 모든 결과 · Esc 닫기"

    override val aiChip = "AI"
    override val aiHeading = "어시스턴트"
    override val aiEmpty = "열려 있는 컬렉션에 대해, 또는 무엇이든 물어보세요."
    override val aiPlaceholder = "이어서 질문…"
    override val aiSend = "질문"
    override val aiThinking = "생각 중…"
    override val aiCopy = "복사"
    override val aiSaveNote = "메모로 저장"
    override val aiUnavailable = "이 브라우저에는 사용할 수 있는 내장 모델이 없습니다."
    override val aiFailed = "모델이 답변하지 못했습니다."
    override fun aiDownloading(percent: Int) = "모델 다운로드 중——$percent%. 이 과정은 한 번만 진행됩니다."
    override val aiSystemPrompt = "당신은 북마크와 탭 관리 앱인 stramus 안의 어시스턴트입니다. " +
        "질문에 사용된 언어로 간결하고 정확하게 답변하세요. 마크다운을 사용해도 좋습니다."

    override val aiTriageSetting = "내장 모델로 탭 정리하기"
    override val aiTriageSettingHint = "탭 창에 버튼을 추가합니다. 모델이 탭을 읽고 각 탭에 대해 컬렉션을 " +
        "제안하며, 저장되기 전에 직접 확인할 수 있습니다. 모든 처리는 이 기기 안에서 이루어집니다. " +
        "탭이 많은 창에서는 1~2분 정도 걸리며, 분류할 수 없는 항목은 제외됩니다."
    override val triageTabs = "컬렉션으로 정리"
    override val triageHeading = "탭을 컬렉션으로 정리"
    override val triageSummaryHeading = "이번 세션의 내용"
    override val triageSummaryTitle = "세션 요약"
    override val triageNew = "새로 만들기"
    override fun triageNewHint(section: String) = "아직 존재하지 않는 컬렉션입니다——“$section”에 만들어집니다."
    override val triageNewSectionHint = "이 컬렉션에는 아직 이 그룹이 없습니다——새로 만들어집니다."
    override val triageGroupHint = "이 새 컬렉션을 사이드바의 어느 섹션에 만들지"
    override val triageSectionHint = "이 탭을 컬렉션의 어느 그룹에 넣을지"
    override val triageNoSection = "그룹 없음"
    override fun triageProgress(done: Int, total: Int) = "사이트 정리 중——$done / $total…"
    override val triageUnsorted = "정리되지 않음"
    override val triageUnsortedHint = "모델이 이 항목들에 대해 제안한 내용이 없습니다. 컬렉션을 선택하거나 열린 채로 두세요."
    override val triageSkip = "저장하지 않기"
    override val triageMoveHint = "이 탭을 어느 컬렉션에 넣을지"
    override val triageDuplicate = "이미 저장됨"
    override val triageDuplicateHint = "이 페이지는 이미 어떤 컬렉션에 있습니다. 체크하면 다시 저장됩니다."
    override fun triageRelated(site: String, count: Int) = "$site 에서 이미 저장됨（$count 개）:"
    override fun triageApply(count: Int, closesTabs: Boolean) =
        if (closesTabs) "저장（$count）하고 닫기" else "저장（$count）"
    override val aiTriageSystemPrompt = "당신은 사용자의 브라우저에 열려 있는 탭을 그의 컬렉션으로 정리합니다. " +
        "탭 목록과 기존 컬렉션이 주어집니다. 각 탭에 대해 그 탭이 속하는 단 하나의 컬렉션으로 답하세요——" +
        "탭에 맞는 기존 이름이 있으면 그것을 재사용하고, 어느 것에도 맞지 않을 때만 짧은 새 이름(한두 단어)을 " +
        "만드세요. 컬렉션 안에서는 그룹을 지정할 수 있으며, 기존 그룹도 마찬가지로 재사용하세요. 같은 사이트의 " +
        "탭들이 서로 다른 컬렉션에 속해도 됩니다. 요청된 JSON만 답변하세요."

    override val aiSection = "AI"
    override val aiAssistant = "어시스턴트"
    override val aiAssistantHint = "검색창에서 한 질문에 누가 답하는지."
    override val aiProviderLocal = "기기 내장"
    override fun aiWebChatHint(assistant: String) =
        "질문을 하면 이 탭에서 $assistant 가 열리고 이미 질문이 입력된 상태가 됩니다. " +
            "이는 $assistant 의 서버로 전송됩니다——이 기기 안에서 답하고 모든 것을 이 기기에 보관하는 " +
            "내장 모델과는 다릅니다."

    override val aiModel = "모델"
    override val aiModelReadyHint = "브라우저에 내장된 모델입니다. 이 기기에서 실행되며——키가 필요 없고 아무것도 밖으로 나가지 않습니다."
    override val aiModelDownloadableHint = "첫 질문 시 브라우저가 다운로드합니다——수백 메가바이트, 한 번만입니다."
    override val aiModelDownloadingHint = "브라우저가 지금 다운로드하고 있습니다."
    override val aiModelNone = "사용 불가"
    override val aiModelNoneHint =
        "이 브라우저는 페이지에 내장 모델을 제공하지 않으므로 검색에서도 질문 옵션을 제공하지 않습니다. " +
            "Chrome에서는 확장 프로그램이 사용할 수 있으며, 일반 웹페이지에서는 플래그 설정이 필요합니다."
    override fun aiModelUnsupported(name: String) = "$name ——사용 불가"
    override val aiModelUnsupportedHint =
        "브라우저에 모델은 있지만 여기서 실행할 수는 없습니다: Chrome 프로필이 있는 드라이브에 약 22GB의 " +
            "여유 공간과 4GB 이상의 메모리를 가진 GPU가 필요합니다."

    override fun resultsFor(query: String) = "“$query”에 대한 결과"
    override val noMatchingLinks = "일치하는 링크가 없습니다."
    override val createCollectionToStart = "링크를 저장하려면 컬렉션을 만드세요."
    override val sortLinks = "이 그룹의 카드 정렬"
    override val sortMenuTitle = "정렬 기준"
    override val addCardSection = "그룹"
    override val pasteUrl = "URL 붙여넣기"
    override val addLinkItem = "링크"
    override val addNoteItem = "메모"
    override val addFileItem = "파일"
    override val noLinksYet = "아직 링크가 없습니다——하나 추가하거나 여기로 끌어다 놓으세요."
    override val ungrouped = "그룹 없음"
    override val dragLinksHere = "링크나 파일을 여기로 끌어다 놓으세요."
    override val editDescription = "설명 편집"

    override fun filesTooLarge(names: List<String>, maxMb: Int) =
        "저장되지 않음——$maxMb MB 초과: ${names.joinToString(", ")}"

    override val protectSection = "PIN으로 보호"
    override val sectionProtection = "섹션 보호"
    override val changePin = "PIN 변경"
    override val removeProtection = "보호 해제"
    override val lockNow = "지금 잠그기"
    override val lockedSection = "PIN으로 보호됨"
    override val unlockedSection = "잠금 해제됨——클릭하면 다시 잠깁니다"
    override val enterPinToView = "이 섹션의 컬렉션을 보려면 PIN을 입력하세요."
    override val pinPlaceholder = "PIN"
    override val unlock = "잠금 해제"
    override val wrongPin = "PIN이 올바르지 않습니다."
    override val setPinHeading = "섹션 보호"
    override val changePinHeading = "PIN 변경"
    override val newPinLabel = "새 PIN"
    override val repeatPinLabel = "PIN 다시 입력"
    override val pinMismatch = "두 PIN이 일치하지 않습니다."
    override fun pinTooShort(min: Int) = "PIN은 최소 $min 자리여야 합니다."
    override val pinNote = "PIN은 섹션 전체를 숨깁니다. 입력하기 전에는 컬렉션 이름조차 표시되지 않으며, " +
        "그 안의 카드는 검색과 내보내기에서도 제외됩니다. 잊어버린 PIN을 재설정할 방법은 없습니다."

    override val makeReadOnlyHint = "읽기 전용으로 설정: 이후로는 이곳에서 추가, 변경, 삭제가 불가능합니다."
    override val allowEditing = "편집 허용"
    override val allowEditingHint = "편집을 다시 허용합니다."
    override val readOnlyBadge = "읽기 전용"
    override val readOnlyHint = "읽기 전용: 이곳에서는 아무것도 추가, 변경, 삭제할 수 없습니다."

    override val security = "보안"
    override val autoLock = "자동 잠금"
    override val autoLockHint = "이 시간 동안 활동이 없으면 잠금 해제된 섹션을 다시 잠급니다."
    override val autoLockNever = "안 함"
    override fun autoLockMinutes(minutes: Int) = "$minutes 분"

    override val openTabs = "열린 탭"
    override val showTabs = "열린 탭 표시"
    override val hideTabs = "열린 탭 숨기기"
    override val noOpenTabs = "저장할 열린 탭이 없습니다."
    override val searchTabs = "탭 검색…"
    override val noMatchingTabs = "일치하는 탭이 없습니다."
    override val thisWindow = "이 창"
    override fun windowLabel(number: Int) = "창 $number"
    override val closeTab = "탭 닫기"
    override val sortTabs = "이 창의 탭 정렬"
    override fun saveTabsHint(count: Int, closing: Boolean) =
        "이 창의 탭（$count 개）을 열려 있는 컬렉션에 그룹 없이 저장——" +
            if (closing) "하고 닫기" else "하고 열어 두기"

    override fun confirmSaveTabs(count: Int, collection: String, closing: Boolean) =
        if (closing) "이 창의 탭（$count 개）을 “$collection”에 저장하고 닫을까요?"
        else "이 창의 탭（$count 개）을 “$collection”에 저장할까요?"

    override val tabsSection = "탭"
    override val closeSavedTabs = "탭을 저장한 뒤"
    override val closeSavedTabsHint =
        "창의 탭이 컬렉션에 저장된 뒤 어떻게 처리할지."
    override val closeSavedTabsClose = "닫기"
    override val closeSavedTabsKeep = "열어 두기"

    override val paneTabs = "탭"
    override val paneHistory = "기록"
    override val searchHistory = "기록 검색…"
    override val noHistory = "아직 기록이 없습니다."
    override val noMatchingHistory = "일치하는 기록이 없습니다."
    override val today = "오늘"
    override val yesterday = "어제"
    override val removeFromHistory = "기록에서 삭제"

    override val emptyNote = "빈 메모"
    override val fileLabel = "파일"
    override val renameCard = "편집"
    override val cardNamePrompt = "카드 제목"
    override val renameHeading = "카드 편집"
    override val renameShowUrl = "주소 표시"
    override val renameHideUrl = "주소 숨기기"
    override val renameUrlPrompt = "주소"

    override val newNote = "새 메모"
    override val editNote = "메모 편집"
    override val viewNote = "메모"
    override val editNoteAction = "편집"
    override val sectionDescription = "그룹 설명"
    override val titlePlaceholder = "제목"
    override val noteDefaultTitle = "메모"
    override val notePlaceholder = "입력을 시작하세요…"
    override val toolBold = "굵게"
    override val toolItalic = "기울임꼴"
    override val toolHighlight = "강조 표시"
    override val toolCode = "코드"
    override val toolLink = "링크"
    override val toolHeading = "제목"
    override val toolList = "글머리 기호 목록"
    override val toolListLabel = "목록"
    override val highlightPlaceholder = "강조"
    override val codePlaceholder = "코드"
    override val linkUrlPrompt = "링크 URL"
    override val draftRestored = "저장하지 않은 초안을 복원했습니다"
    override val discardDraft = "초기화"

    override val addFile = "파일 추가"
    override val chooseFile = "파일 선택…"
    override val download = "다운로드"
    override val fileDefaultTitle = "파일"
    override fun noPreviewFor(mime: String) = "$mime 에 대한 인라인 미리보기가 없습니다——다운로드를 사용하세요."

    override val appearance = "모양"
    override val theme = "테마"
    override val themeHint = "시스템을 따르거나, 라이트/다크를 강제로 지정합니다."
    override val themeAuto = "자동"
    override val themeLight = "라이트"
    override val themeDark = "다크"
    override val accentColor = "강조 색상"
    override val accentColorHint = "버튼, 선택 항목, 강조 표시 뒤에 사용되는 브랜드 색상."
    override val accentBlue = "파랑"
    override val accentPurple = "보라"
    override val accentGreen = "초록"
    override val accentOrange = "주황"
    override val accentRose = "장미색"
    override val language = "언어"
    override val languageHint = "인터페이스 언어."
    override val cardUrls = "카드 주소"
    override val cardUrlsHint = "링크 카드가 제목 아래에 주소를 표시할지 여부."
    override val cardUrlsShow = "표시"
    override val cardUrlsHide = "숨기기"
    override val groupsView = "섹션 보기"
    override val groupsViewHint =
        "컬렉션의 섹션을 위아래로 나열해 표시하거나, 제자리에서 펼쳐지는 폴더 형태로 표시합니다."
    override val groupsViewList = "목록"
    override val groupsViewFolders = "폴더"
    override val folderBack = "폴더로 돌아가기"
    override val swapSidebars = "사이드바 순서"
    override val swapSidebarsHint = "섹션 사이드바가 탭/기록 사이드바에 대해 어느 쪽에 있는지."
    override val swapSidebarsLeft = "섹션 왼쪽"
    override val swapSidebarsRight = "섹션 오른쪽"
    override val tabsCardView = "탭 보기"
    override val tabsCardViewHint =
        "열린 탭을 목록으로 표시하거나, 가운데 패널과 같은 너비의 카드 그리드로 표시합니다."
    override val tabsCardViewList = "목록"
    override val tabsCardViewCards = "카드"

    override val startupSection = "시작"
    override val startView = "열었을 때"
    override val startViewHint = "stramus를 열었을 때 표시되는 컬렉션. PIN으로 보호된 컬렉션은 절대 " +
        "표시되지 않습니다——새로 고칠 때마다 해당 섹션이 다시 잠깁니다."
    override val startViewLast = "마지막으로 연 것"
    override val startViewFirst = "첫 번째 컬렉션"

    override val dataSection = "데이터"

    override val export = "내보내기"
    override val exportHint = "모든 컬렉션에 저장된 링크를 모두 다운로드합니다. 아직 PIN이 입력되지 " +
        "않은 섹션은 제외됩니다."
    override val exportCsv = "CSV 내보내기"
    override val exportBookmarks = "북마크 내보내기"

    override val import = "가져오기"
    override val importHint = "어떤 브라우저에서든 북마크 파일을 가져오거나, 여기서 내보낸 CSV를 " +
        "가져올 수 있습니다. 폴더는 섹션, 컬렉션, 그룹이 됩니다. 이미 저장될 위치에 있는 링크는 " +
        "그대로 둡니다."
    override val importFile = "파일 선택"
    override val importedTitle = "가져옴"
    override fun importDone(added: Int, skipped: Int) = when (skipped) {
        0 -> "링크 $added 개를 가져왔습니다."
        else -> "링크 $added 개를 가져왔습니다. $skipped 개는 이미 저장되어 있었습니다."
    }
    override val importNothing = "이 파일에는 가져올 링크가 없습니다."

    override val sortTitle = "제목 A–Z"
    override val sortUrl = "URL"
    override val sortDomain = "도메인"
    override val sortNewest = "최신순"
    override val sortOldest = "오래된순"

    override val account = "계정"
    override val accountSignedOutHint = "로그인하면 사용하는 모든 브라우저에서 컬렉션을 유지할 수 있습니다. 계정 없이도 모든 기능이 그대로 작동하며, 이 경우 데이터는 이 기기에만 남습니다."
    override val signInAccount = "로그인"
    override val signOut = "로그아웃"
    override val syncNow = "지금 동기화"
    override fun syncedAt(time: String) = "$time 에 동기화됨"
    override fun conflictCopies(count: Int) =
        if (count == 1) "메모 하나가 두 기기에서 동시에 수정되었습니다. 두 버전 모두 보존되었습니다."
        else "메모 $count 개가 두 기기에서 동시에 수정되었습니다. 각 메모의 두 버전이 모두 보존되었습니다."
    override val joinAccountTitle = "이 브라우저에는 이미 컬렉션이 있습니다"
    override val joinAccountHint = "계정에 추가하거나, 여기 그대로 두고 계정에 이미 있는 것을 사용할 수 있습니다."
    override val joinAccountKeep = "계정에 추가하기"
    override val joinAccountDiscard = "계정의 컬렉션 사용하기"
    override val exportAccountData = "내 데이터 다운로드"
    override val exportAccountDataHint = "서버가 이 계정에 대해 보관하는 모든 데이터를 JSON 형식으로."
    override val exportAccountDataFailed = "내보내기를 다운로드하지 못했습니다."
    override val deleteAccount = "계정 삭제"
    override val deleteAccountHint = "서버가 보관하는 모든 것을 지웁니다. 이 기기에 있는 것은 남습니다."
    override val deleteAccountConfirm = "계정과 서버가 보관하는 모든 것을 삭제할까요? 이 작업은 되돌릴 수 없습니다."
    override val syncUsage = "방문 통계 동기화"
    override val syncUsageHint = "어떤 페이지를 얼마나 자주 여는지——검색 순위에 사용되는 정보입니다. 끄면 이 기기에만 남습니다."
    override val optionOn = "켜짐"
    override val optionOff = "꺼짐"
    override val signInWithGoogle = "Google로 계속하기"
    override val signInUnavailable = "이 빌드에서는 로그인이 설정되어 있지 않습니다. 앱은 언제나처럼 계정 없이도 작동합니다."
    override val serverUnavailable = "현재 서버가 응답하지 않습니다. 이 작업에는 서버가 필요합니다——서버가 복구되면 다시 시도하세요."

    override val onboardingSignInTitle = "로그인하고 어디서나 동기화하세요"
    override val onboardingSignInBody = "로그인하기 전까지 컬렉션은 이 기기에만 남아 있습니다. 로그인하면 사용하는 모든 브라우저에 자동으로 따라옵니다."
    override val onboardingSkip = "나중에 하기"
    override val onboardingInstallTitle = "확장 프로그램 설치"
    override val onboardingInstallBody = "stramus는 이 탭에서 바로 작동하지만, 확장 프로그램을 설치하면 전용 새 탭 페이지, 원클릭 탭 저장, 열린 탭과 방문 기록에 대한 검색까지—온전한 경험을 이용할 수 있습니다."
    override val onboardingInstallCta = "Chrome 웹 스토어에서 설치"
    override val onboardingContinueInBrowser = "브라우저에서 계속하기"
    override val onboardingOrganizeTitle = "섹션으로 묶인 컬렉션"
    override val onboardingOrganizeBody = "사이드바에는 섹션이 있고, 각 섹션을 열면 그 안의 컬렉션이 나타납니다. 탭이나 링크, 파일을 원하는 컬렉션에 끌어다 놓으면 저장됩니다—직접 요청하지 않는 한 아무것도 이 기기를 벗어나지 않습니다."
    override val onboardingSearchTitle = "모든 것을 위한 검색 하나"
    override val onboardingSearchBody = "상단의 검색창은 저장된 카드, 열린 탭, 방문 기록을 한 번에 찾아줍니다—입력을 시작하기만 하면 stramus가 대신 모든 곳을 찾아봅니다."
    override val onboardingBack = "이전"
    override val onboardingNext = "다음"
    override val onboardingGetStarted = "시작하기"

    override val seed = StoreSeed(
        sectionTitle = "메인",
        collectionTitle = "시작하기",
        noteTitle = "stramus 사용법",
        // 각 글머리 기호는 한 줄입니다: 이 마크다운은 `Markdown.kt`가 읽으며, 글머리 기호 안에서
        // 줄이 바뀌면 목록이 이어지지 않고 끝나 버립니다.
        noteBody = """
            # stramus에 오신 것을 환영합니다

            왼쪽 사이드바에는 **섹션**이 있고, 섹션에는 **컬렉션**이, 컬렉션에는 카드——링크, 파일, 그리고 이런 메모——가 들어 있습니다.

            ## 페이지 저장하기
            - 오른쪽 사이드바에서 탭을 컬렉션으로 끌어다 놓거나, **⤓ 열린 탭 저장**을 사용해 창 전체를 한 번에 저장하세요.
            - 섹션 헤더에 마우스를 올리고 **+**를 누르면 붙여넣은 주소, 메모, 파일을 그 섹션에 바로 추가할 수 있습니다.
            - **+ 그룹**은 큰 컬렉션을 여러 그룹으로 나눕니다——카드를 그룹 위에 놓으면 그 안으로 이동합니다.

            ## 페이지 찾기
            - 위쪽 검색창은 저장한 것, 열려 있는 탭, 방문했던 곳을 한 번에 모두 검색합니다.
            - 주소를 입력하면 열리고, 질문을 입력하면 브라우저 내장 모델에게 물어볼 수 있습니다.
            - ↑↓ 선택, Enter 열기, Esc 닫기.

            ## 정리해 두기
            - **PIN으로 보호**: 잠긴 섹션은 컬렉션의 이름조차 표시하지 않으며, 자리를 비우면 다시 잠깁니다.
            - **🔒 읽기 전용**은 완성된 컬렉션을 실수로 건드리지 않도록 보호합니다.
            - 설정에는 테마, 언어, 그리고 모든 것을 CSV나 북마크로 내보내는 기능이 있습니다.

            이 컬렉션의 이름을 바꾸거나 이 메모를 삭제하세요——이제부터는 모두 당신의 것입니다.
        """.trimIndent(),
    )
}

private object ItStrings : Strings {
    override val on = "Attivo"
    override val off = "Disattivo"
    override val experimental = "sperimentale"
    override val settings = "Impostazioni"
    override val close = "Chiudi"
    override val cancel = "Annulla"
    override val save = "Salva"
    override val about = "Informazioni"
    override fun aboutVersion(version: String) = "Versione $version"
    override fun aboutCopyright(year: String) = "© $year Stramus"
    override val aboutHomepage = "stramus.space"

    override val expandSidebar = "Espandi pannello"
    override val collapseSidebar = "Comprimi pannello"
    override val newSection = "+ Nuova sezione"
    override val sectionNamePrompt = "Nome della sezione"
    override val sectionNameDefault = "Nuova sezione"
    override val collectionNamePrompt = "Nome della raccolta"
    override val collectionNameDefault = "Nuova raccolta"
    override val renameHint = "Clic per comprimere, doppio clic per rinominare, trascina per riordinare"
    override val renameCollectionHint = "Doppio clic per rinominare"
    override val untitled = "Senza titolo"

    override val newSectionHint = "Aggiungi una sezione al pannello"
    override val addCollectionHint = "Aggiungi una raccolta a questa sezione"
    override val deleteSectionHint = "Elimina questa sezione e le raccolte che contiene"
    override val deleteCollectionHint = "Elimina questa raccolta e le sue schede"
    override val addCardSectionHint = "Aggiungi un gruppo a questa raccolta"
    override val deleteCardSectionHint = "Elimina questo gruppo — le sue schede restano nella raccolta, senza gruppo"
    override val addCardHint = "Aggiungi un link — oppure, dal menu, una nota o un file"
    override val deleteCardHint = "Elimina questa scheda"
    override fun openAllHint(count: Int) = "Apri tutte le $count schede in nuove schede del browser"

    override fun confirmDeleteSection(title: String, cards: Int) =
        "“$title” e le sue raccolte contengono $cards elementi salvati. Eliminare la sezione?"
    override fun confirmDeleteCollection(title: String, cards: Int) =
        "“$title” contiene $cards elementi salvati. Eliminare la raccolta?"
    override fun confirmDeleteCardSection(title: String, cards: Int) =
        "“$title” contiene $cards schede. Eliminare il gruppo? Le schede restano, senza gruppo."
    override fun deletedSection(title: String) = "Sezione “$title” eliminata"
    override fun deletedCollection(title: String) = "Raccolta “$title” eliminata"
    override fun deletedCardSection(title: String) = "Gruppo “$title” eliminato"
    override fun deletedCard(title: String) = "“$title” eliminata"
    override fun movedCard(title: String) = "“$title” spostata"
    override val sortedCards = "Schede ordinate"
    override val undo = "Annulla"

    override val searchPlaceholder = "Cerca, digita un indirizzo, o fai una domanda…"

    override val hitsTopSites = "Aperti di frequente"
    override val hitsTabs = "Schede aperte"
    override val hitsCards = "Salvato"
    override val hitsHistory = "Cronologia"
    override val hitsSites = "Siti"
    override val hitsCollections = "Raccolte"

    override val hitSwitchToTab = "Passa a"
    override val hitOpenCollection = "Apri"
    override val hitAskAi = "Chiedi"

    // Il motore è quello del browser stesso — qualunque esso sia — quindi qui non viene nominato.
    override fun hitWebSearch(query: String) = "Cerca “$query” nel web"
    override fun hitOpenUrl(query: String) = "Apri $query"
    override fun hitAskAiRow(assistant: String, query: String) = "Chiedi a $assistant: “$query”"

    override val forgetSite = "Non suggerire più questa pagina"
    override val searchHints = "↑↓ scegli · Invio apri · Alt+Invio cerca nel web · ⌘/Ctrl+Invio tutti i risultati · Esc chiudi"

    override val aiChip = "IA"
    override val aiHeading = "Assistente"
    override val aiEmpty = "Chiedi qualcosa sulla raccolta aperta, o su qualsiasi altra cosa."
    override val aiPlaceholder = "Fai un'altra domanda…"
    override val aiSend = "Chiedi"
    override val aiThinking = "Sto pensando…"
    override val aiCopy = "Copia"
    override val aiSaveNote = "Salva come nota"
    override val aiUnavailable = "Questo browser non ha nessun modello integrato disponibile."
    override val aiFailed = "Il modello non è riuscito a rispondere."
    override fun aiDownloading(percent: Int) = "Download del modello — $percent%. Questo avviene una sola volta."
    override val aiSystemPrompt = "Sei l'assistente all'interno di stramus, un gestore di segnalibri e schede. " +
        "Rispondi in modo breve e diretto, nella lingua della domanda. Il Markdown è benvenuto."

    override val aiTriageSetting = "Ordina le schede con il modello integrato"
    override val aiTriageSettingHint = "Aggiunge un pulsante a una finestra di schede: il modello le legge e propone " +
        "una raccolta per ciascuna, da controllare prima che venga salvato qualcosa. Tutto resta su " +
        "questo dispositivo. Su una finestra grande richiede uno o due minuti, e lascia fuori ciò che non riesce a classificare."
    override val triageTabs = "Ordina in raccolte"
    override val triageHeading = "Ordina le schede in raccolte"
    override val triageSummaryHeading = "Di cosa parlava questa sessione"
    override val triageSummaryTitle = "Riepilogo della sessione"
    override val triageNew = "nuova"
    override fun triageNewHint(section: String) = "Questa raccolta non esiste ancora — verrà creata in “$section”."
    override val triageNewSectionHint = "Questo gruppo non esiste ancora in questa raccolta — verrà creato."
    override val triageGroupHint = "In quale sezione del pannello verrà creata questa nuova raccolta"
    override val triageSectionHint = "In quale gruppo della raccolta andrà questa scheda"
    override val triageNoSection = "Nessun gruppo"
    override fun triageProgress(done: Int, total: Int) = "Ordinamento dei siti — $done di $total…"
    override val triageUnsorted = "Non ordinate"
    override val triageUnsortedHint = "Il modello non ha proposto nulla per queste. Scegli una raccolta, o lasciale aperte."
    override val triageSkip = "Non salvare"
    override val triageMoveHint = "In quale raccolta andrà questa scheda"
    override val triageDuplicate = "già salvato"
    override val triageDuplicateHint = "Questa pagina è già in una raccolta. Selezionala per salvarla di nuovo."
    override fun triageRelated(site: String, count: Int) = "Già salvato da $site ($count):"
    override fun triageApply(count: Int, closesTabs: Boolean) =
        if (closesTabs) "Salva ($count) e chiudi" else "Salva ($count)"
    override val aiTriageSystemPrompt = "Ordini le schede aperte nel browser di un utente nelle sue raccolte. " +
        "Ti vengono fornite le schede e le raccolte esistenti. Per ogni scheda, rispondi con l'unica " +
        "raccolta a cui appartiene — riusa un nome esistente ovunque la scheda vi si adatti, e inventa " +
        "un nome breve (una o due parole) solo se non si adatta a nessuna. All'interno di una raccolta " +
        "puoi indicare un gruppo, riusando anche quelli esistenti. Schede dello stesso sito possono " +
        "appartenere a raccolte diverse. Rispondi solo con il JSON richiesto."

    override val aiSection = "IA"
    override val aiAssistant = "Assistente"
    override val aiAssistantHint = "Chi risponde a una domanda posta dalla barra di ricerca."
    override val aiProviderLocal = "Sul dispositivo"
    override fun aiWebChatHint(assistant: String) =
        "La domanda apre $assistant in questa scheda, già posta. Viene inviata ai server di $assistant — " +
            "a differenza del modello integrato, che risponde qui e mantiene tutto su questo dispositivo."

    override val aiModel = "Modello"
    override val aiModelReadyHint = "Il modello integrato del browser. Funziona su questo dispositivo — senza chiave, e nulla ne esce."
    override val aiModelDownloadableHint = "Il browser lo scaricherà alla prima domanda — qualche centinaio di megabyte, una sola volta."
    override val aiModelDownloadingHint = "Il browser lo sta scaricando in questo momento."
    override val aiModelNone = "Non disponibile"
    override val aiModelNoneHint =
        "Questo browser non fornisce alla pagina alcun modello integrato, quindi la ricerca non offre di interpellarlo. " +
            "In Chrome è disponibile per l'estensione; una normale pagina web ha bisogno dei flag per questo."
    override fun aiModelUnsupported(name: String) = "$name — non disponibile"
    override val aiModelUnsupportedHint =
        "Il browser dispone del modello ma non può eseguirlo qui: servono ~22 GB liberi sul disco che " +
            "contiene il profilo di Chrome, e una GPU con più di 4 GB di memoria."

    override fun resultsFor(query: String) = "Risultati per “$query”"
    override val noMatchingLinks = "Nessun link corrispondente."
    override val createCollectionToStart = "Crea una raccolta per iniziare a salvare link."
    override val sortLinks = "Ordina le schede di questo gruppo"
    override val sortMenuTitle = "Ordina per"
    override val addCardSection = "Gruppo"
    override val pasteUrl = "Incolla un URL"
    override val addLinkItem = "Link"
    override val addNoteItem = "Nota"
    override val addFileItem = "File"
    override val noLinksYet = "Ancora nessun link — aggiungine uno, o trascinane uno qui."
    override val ungrouped = "Senza gruppo"
    override val dragLinksHere = "Trascina qui link o file."
    override val editDescription = "Modifica descrizione"

    override fun filesTooLarge(names: List<String>, maxMb: Int) =
        "Non salvato — oltre $maxMb MB: ${names.joinToString(", ")}"

    override val protectSection = "Proteggi con un PIN"
    override val sectionProtection = "Protezione della sezione"
    override val changePin = "Cambia PIN"
    override val removeProtection = "Rimuovi protezione"
    override val lockNow = "Blocca ora"
    override val lockedSection = "Protetta con un PIN"
    override val unlockedSection = "Sbloccata — clic per bloccarla di nuovo"
    override val enterPinToView = "Inserisci il PIN per vedere le raccolte di questa sezione."
    override val pinPlaceholder = "PIN"
    override val unlock = "Sblocca"
    override val wrongPin = "PIN errato."
    override val setPinHeading = "Proteggi sezione"
    override val changePinHeading = "Cambia PIN"
    override val newPinLabel = "Nuovo PIN"
    override val repeatPinLabel = "Ripeti il PIN"
    override val pinMismatch = "I due PIN non coincidono."
    override fun pinTooShort(min: Int) = "Il PIN deve avere almeno $min cifre."
    override val pinNote = "Il PIN nasconde l'intera sezione: le sue raccolte non vengono nemmeno nominate " +
        "finché non viene inserito, e le loro schede restano fuori dalla ricerca e dall'esportazione. " +
        "Non esiste modo di ripristinare un PIN dimenticato."

    override val makeReadOnlyHint = "Rendi di sola lettura: qui non sarà più possibile aggiungere, modificare o eliminare nulla."
    override val allowEditing = "Consenti modifica"
    override val allowEditingHint = "Consenti di nuovo la modifica."
    override val readOnlyBadge = "sola lettura"
    override val readOnlyHint = "Sola lettura: qui non è possibile aggiungere, modificare o eliminare nulla."

    override val security = "Sicurezza"
    override val autoLock = "Blocco automatico"
    override val autoLockHint = "Blocca di nuovo le sezioni sbloccate dopo questo tempo di inattività."
    override val autoLockNever = "Mai"
    override fun autoLockMinutes(minutes: Int) = "$minutes min"

    override val openTabs = "Schede aperte"
    override val showTabs = "Mostra schede aperte"
    override val hideTabs = "Nascondi schede aperte"
    override val noOpenTabs = "Nessuna scheda aperta da salvare."
    override val searchTabs = "Cerca nelle schede…"
    override val noMatchingTabs = "Nessuna scheda corrispondente."
    override val thisWindow = "Questa finestra"
    override fun windowLabel(number: Int) = "Finestra $number"
    override val closeTab = "Chiudi scheda"
    override val sortTabs = "Ordina le schede di questa finestra"
    override fun saveTabsHint(count: Int, closing: Boolean) =
        "Salva le schede di questa finestra ($count) nella raccolta aperta, senza gruppo — " +
            if (closing) "e chiudile" else "e lasciale aperte"

    override fun confirmSaveTabs(count: Int, collection: String, closing: Boolean) =
        if (closing) "Salvare le schede di questa finestra ($count) in “$collection” e chiuderle?"
        else "Salvare le schede di questa finestra ($count) in “$collection”?"

    override val tabsSection = "Schede"
    override val closeSavedTabs = "Dopo aver salvato le schede"
    override val closeSavedTabsHint =
        "Cosa succede alle schede di una finestra una volta salvate in una raccolta."
    override val closeSavedTabsClose = "Chiudile"
    override val closeSavedTabsKeep = "Lasciale aperte"

    override val paneTabs = "Schede"
    override val paneHistory = "Cronologia"
    override val searchHistory = "Cerca nella cronologia…"
    override val noHistory = "Ancora nulla nella cronologia."
    override val noMatchingHistory = "Nulla corrisponde nella cronologia."
    override val today = "Oggi"
    override val yesterday = "Ieri"
    override val removeFromHistory = "Rimuovi dalla cronologia"

    override val emptyNote = "Nota vuota"
    override val fileLabel = "file"
    override val renameCard = "Modifica"
    override val cardNamePrompt = "Titolo della scheda"
    override val renameHeading = "Modifica scheda"
    override val renameShowUrl = "Mostra indirizzo"
    override val renameHideUrl = "Nascondi indirizzo"
    override val renameUrlPrompt = "Indirizzo"

    override val newNote = "Nuova nota"
    override val editNote = "Modifica nota"
    override val viewNote = "Nota"
    override val editNoteAction = "Modifica"
    override val sectionDescription = "Descrizione del gruppo"
    override val titlePlaceholder = "Titolo"
    override val noteDefaultTitle = "Nota"
    override val notePlaceholder = "Inizia a scrivere…"
    override val toolBold = "Grassetto"
    override val toolItalic = "Corsivo"
    override val toolHighlight = "Evidenzia"
    override val toolCode = "Codice"
    override val toolLink = "Link"
    override val toolHeading = "Titolo"
    override val toolList = "Elenco puntato"
    override val toolListLabel = "Elenco"
    override val highlightPlaceholder = "evidenziato"
    override val codePlaceholder = "codice"
    override val linkUrlPrompt = "URL del link"
    override val draftRestored = "Bozza non salvata ripristinata"
    override val discardDraft = "Ripristina"

    override val addFile = "Aggiungi file"
    override val chooseFile = "Scegli un file…"
    override val download = "Scarica"
    override val fileDefaultTitle = "File"
    override fun noPreviewFor(mime: String) = "Nessuna anteprima per $mime — usa Scarica."

    override val appearance = "Aspetto"
    override val theme = "Tema"
    override val themeHint = "Segui il sistema, o forza giorno/notte."
    override val themeAuto = "Auto"
    override val themeLight = "Chiaro"
    override val themeDark = "Scuro"
    override val accentColor = "Colore accento"
    override val accentColorHint = "Il colore del marchio dietro pulsanti, selezione ed evidenziazioni."
    override val accentBlue = "Blu"
    override val accentPurple = "Viola"
    override val accentGreen = "Verde"
    override val accentOrange = "Arancione"
    override val accentRose = "Rosa"
    override val language = "Lingua"
    override val languageHint = "La lingua dell'interfaccia."
    override val cardUrls = "Indirizzi sulle schede"
    override val cardUrlsHint = "Se una scheda link mostra il suo indirizzo sotto il titolo."
    override val cardUrlsShow = "Mostra"
    override val cardUrlsHide = "Nascondi"
    override val groupsView = "Vista delle sezioni"
    override val groupsViewHint =
        "Mostra le sezioni di una raccolta una sotto l'altra, o come cartelle che si aprono dove si trovano."
    override val groupsViewList = "Elenco"
    override val groupsViewFolders = "Cartelle"
    override val folderBack = "Torna alle cartelle"
    override val swapSidebars = "Ordine dei pannelli"
    override val swapSidebarsHint = "Da che lato si trova il pannello delle sezioni, rispetto a quello di schede/cronologia."
    override val swapSidebarsLeft = "Sezioni a sinistra"
    override val swapSidebarsRight = "Sezioni a destra"
    override val tabsCardView = "Vista delle schede"
    override val tabsCardViewHint =
        "Mostra le schede aperte come elenco, o come griglia di schede della stessa larghezza del pannello centrale."
    override val tabsCardViewList = "Elenco"
    override val tabsCardViewCards = "Schede"

    override val startupSection = "Avvio"
    override val startView = "All'apertura"
    override val startViewHint = "Quale raccolta viene mostrata all'apertura di stramus. Una raccolta protetta " +
        "da un PIN non lo è mai — ogni ricaricamento blocca di nuovo la sua sezione."
    override val startViewLast = "Ultima aperta"
    override val startViewFirst = "Prima raccolta"

    override val dataSection = "Dati"

    override val export = "Esporta"
    override val exportHint = "Scarica tutti i link salvati in tutte le raccolte. Una sezione ancora protetta " +
        "dal suo PIN viene esclusa."
    override val exportCsv = "Esporta CSV"
    override val exportBookmarks = "Esporta segnalibri"

    override val import = "Importa"
    override val importHint = "Importa un file di segnalibri da qualsiasi browser, o un CSV esportato qui. " +
        "Le cartelle diventano sezioni, raccolte e gruppi; un link già salvato dove finirebbe viene " +
        "lasciato invariato."
    override val importFile = "Scegli un file"
    override val importedTitle = "Importato"
    override fun importDone(added: Int, skipped: Int) = when (skipped) {
        0 -> "$added link importati."
        else -> "$added link importati; $skipped erano già salvati."
    }
    override val importNothing = "Nessun link da importare in quel file."

    override val sortTitle = "Titolo A–Z"
    override val sortUrl = "URL"
    override val sortDomain = "Dominio"
    override val sortNewest = "Più recenti prima"
    override val sortOldest = "Più vecchi prima"

    override val account = "Account"
    override val accountSignedOutHint = "Accedi per mantenere le tue raccolte su ogni browser che usi. Tutto funziona senza account — resta semplicemente su questo dispositivo."
    override val signInAccount = "Accedi"
    override val signOut = "Esci"
    override val syncNow = "Sincronizza ora"
    override fun syncedAt(time: String) = "Sincronizzato alle $time"
    override fun conflictCopies(count: Int) =
        if (count == 1) "Una nota è stata modificata su due dispositivi contemporaneamente. Entrambe le versioni sono state conservate."
        else "$count note sono state modificate su due dispositivi contemporaneamente. Di ciascuna sono state conservate entrambe le versioni."
    override val joinAccountTitle = "Questo browser ha già delle raccolte"
    override val joinAccountHint = "Puoi aggiungerle all'account, oppure lasciarle qui e prendere ciò che l'account ha già."
    override val joinAccountKeep = "Aggiungile all'account"
    override val joinAccountDiscard = "Usa le raccolte dell'account"
    override val exportAccountData = "Scarica i miei dati"
    override val exportAccountDataHint = "Tutto ciò che il server conserva su questo account, in formato JSON."
    override val exportAccountDataFailed = "Impossibile scaricare l'esportazione."
    override val deleteAccount = "Elimina account"
    override val deleteAccountHint = "Cancella tutto ciò che il server conserva. Ciò che è su questo dispositivo resta."
    override val deleteAccountConfirm = "Eliminare l'account e tutto ciò che il server conserva? L'operazione non può essere annullata."
    override val syncUsage = "Sincronizza statistiche di navigazione"
    override val syncUsageHint = "Quali pagine apri e con quale frequenza — ciò su cui si basa l'ordinamento della ricerca. Disattivato significa che resta su questo dispositivo."
    override val optionOn = "Attivo"
    override val optionOff = "Disattivo"
    override val signInWithGoogle = "Continua con Google"
    override val signInUnavailable = "L'accesso non è configurato in questa build. L'app funziona senza account, come sempre."
    override val serverUnavailable = "Il server non risponde in questo momento. Questa funzione ne ha bisogno — riprova quando sarà di nuovo raggiungibile."

    override val onboardingSignInTitle = "Accedi per sincronizzare ovunque"
    override val onboardingSignInBody = "Le tue raccolte restano su questo dispositivo finché non accedi — dopo, ti seguiranno automaticamente su ogni browser che usi."
    override val onboardingSkip = "Più tardi"
    override val onboardingInstallTitle = "Installa l'estensione"
    override val onboardingInstallBody = "stramus funziona già in questa scheda, ma l'estensione aggiunge una pagina di nuova scheda dedicata, il salvataggio delle schede con un clic e una ricerca su schede aperte e cronologia — l'esperienza completa."
    override val onboardingInstallCta = "Installa dal Chrome Web Store"
    override val onboardingContinueInBrowser = "Continua nel browser"
    override val onboardingOrganizeTitle = "Raccolte, raggruppate in sezioni"
    override val onboardingOrganizeBody = "La barra laterale contiene le tue sezioni; ciascuna si apre sulle sue raccolte. Trascina una scheda, un link o un file in una raccolta per salvarlo lì — niente lascia il tuo dispositivo a meno che tu non lo chieda."
    override val onboardingSearchTitle = "Una sola ricerca per tutto"
    override val onboardingSearchBody = "La barra di ricerca in alto trova insieme le schede salvate, le schede aperte e la cronologia di navigazione — inizia a digitare, e stramus cerca ovunque al posto tuo."
    override val onboardingBack = "Indietro"
    override val onboardingNext = "Avanti"
    override val onboardingGetStarted = "Inizia"

    override val seed = StoreSeed(
        sectionTitle = "Principale",
        collectionTitle = "Per iniziare",
        noteTitle = "Come usare stramus",
        // Ogni punto elenco è una sola riga: questo è il markdown che legge `Markdown.kt`, e un ritorno
        // a capo al suo interno termina l'elenco invece di farlo continuare.
        noteBody = """
            # Benvenuto in stramus

            Il pannello a sinistra contiene **sezioni**, una sezione contiene **raccolte**, e una raccolta contiene schede — link, file e note come questa.

            ## Salvare una pagina
            - Trascina una scheda dal pannello destro su una raccolta, oppure usa **⤓ Salva schede aperte** per un'intera finestra in una volta sola.
            - Passa il mouse sull'intestazione di una sezione e premi il suo **+** per aggiungere un indirizzo incollato, una nota o un file direttamente in quella sezione.
            - **+ Gruppo** divide una raccolta grande in gruppi — trascina una scheda su uno di essi per spostarla lì.

            ## Trovare una pagina
            - La barra di ricerca in alto cerca ovunque in una volta sola: ciò che hai salvato, le schede che hai aperte, e dove sei stato.
            - Digita un indirizzo per aprirlo, o una domanda per interpellare il modello integrato del browser.
            - ↑↓ per scegliere, Invio per aprire, Esc per chiudere.

            ## Mantenere l'ordine
            - **Proteggi con un PIN**: una sezione bloccata non nomina nemmeno le sue raccolte, e si blocca di nuovo quando ti allontani.
            - **🔒 Sola lettura** protegge una raccolta finita da una svista.
            - Le impostazioni contengono il tema, la lingua e un'esportazione di tutto in CSV o segnalibri.

            Rinomina questa raccolta, o elimina questa nota — ormai tutto questo è tuo.
        """.trimIndent(),
    )
}

private object TrStrings : Strings {
    override val on = "Açık"
    override val off = "Kapalı"
    override val experimental = "deneysel"
    override val settings = "Ayarlar"
    override val close = "Kapat"
    override val cancel = "İptal"
    override val save = "Kaydet"
    override val about = "Hakkında"
    override fun aboutVersion(version: String) = "Sürüm $version"
    override fun aboutCopyright(year: String) = "© $year Stramus"
    override val aboutHomepage = "stramus.space"

    override val expandSidebar = "Kenar çubuğunu genişlet"
    override val collapseSidebar = "Kenar çubuğunu daralt"
    override val newSection = "+ Yeni bölüm"
    override val sectionNamePrompt = "Bölüm adı"
    override val sectionNameDefault = "Yeni bölüm"
    override val collectionNamePrompt = "Koleksiyon adı"
    override val collectionNameDefault = "Yeni koleksiyon"
    override val renameHint = "Daraltmak için tıkla, yeniden adlandırmak için çift tıkla, sıralamak için sürükle"
    override val renameCollectionHint = "Yeniden adlandırmak için çift tıkla"
    override val untitled = "Adsız"

    override val newSectionHint = "Kenar çubuğuna bir bölüm ekle"
    override val addCollectionHint = "Bu bölüme bir koleksiyon ekle"
    override val deleteSectionHint = "Bu bölümü ve içindeki koleksiyonları sil"
    override val deleteCollectionHint = "Bu koleksiyonu ve kartlarını sil"
    override val addCardSectionHint = "Bu koleksiyona bir grup ekle"
    override val deleteCardSectionHint = "Bu grubu sil — kartları koleksiyonda gruplandırılmadan kalır"
    override val addCardHint = "Bir bağlantı ekle — veya menüden bir not ya da dosya ekle"
    override val deleteCardHint = "Bu kartı sil"
    override fun openAllHint(count: Int) = "$count kartın tümünü yeni sekmelerde aç"

    override fun confirmDeleteSection(title: String, cards: Int) =
        "“$title” ve koleksiyonlarında $cards kayıtlı öğe var. Bölüm silinsin mi?"
    override fun confirmDeleteCollection(title: String, cards: Int) =
        "“$title” içinde $cards kayıtlı öğe var. Koleksiyon silinsin mi?"
    override fun confirmDeleteCardSection(title: String, cards: Int) =
        "“$title” içinde $cards kart var. Grup silinsin mi? Kartlar gruplandırılmadan kalır."
    override fun deletedSection(title: String) = "“$title” bölümü silindi"
    override fun deletedCollection(title: String) = "“$title” koleksiyonu silindi"
    override fun deletedCardSection(title: String) = "“$title” grubu silindi"
    override fun deletedCard(title: String) = "“$title” silindi"
    override fun movedCard(title: String) = "“$title” taşındı"
    override val sortedCards = "Kartlar sıralandı"
    override val undo = "Geri al"

    override val searchPlaceholder = "Ara, bir adres yaz, ya da soru sor…"

    override val hitsTopSites = "Sık açılanlar"
    override val hitsTabs = "Açık sekmeler"
    override val hitsCards = "Kaydedilenler"
    override val hitsHistory = "Geçmiş"
    override val hitsSites = "Siteler"
    override val hitsCollections = "Koleksiyonlar"

    override val hitSwitchToTab = "Geç"
    override val hitOpenCollection = "Aç"
    override val hitAskAi = "Sor"

    // Arama motoru tarayıcının kendi ayarına bağlıdır — hangisi olursa olsun — bu yüzden burada belirtilmez.
    override fun hitWebSearch(query: String) = "“$query” için web'de ara"
    override fun hitOpenUrl(query: String) = "$query aç"
    override fun hitAskAiRow(assistant: String, query: String) = "$assistant'a sor: “$query”"

    override val forgetSite = "Bu sayfayı bir daha önerme"
    override val searchHints = "↑↓ seç · Enter aç · Alt+Enter web'de ara · ⌘/Ctrl+Enter tüm sonuçlar · Esc kapat"

    override val aiChip = "YZ"
    override val aiHeading = "Asistan"
    override val aiEmpty = "Açık olan koleksiyon hakkında ya da başka herhangi bir şey hakkında soru sor."
    override val aiPlaceholder = "Başka bir soru sor…"
    override val aiSend = "Sor"
    override val aiThinking = "Düşünüyor…"
    override val aiCopy = "Kopyala"
    override val aiSaveNote = "Not olarak kaydet"
    override val aiUnavailable = "Bu tarayıcıda kullanılabilir yerleşik bir model yok."
    override val aiFailed = "Model yanıt veremedi."
    override fun aiDownloading(percent: Int) = "Model indiriliyor — %$percent. Bu yalnızca bir kez olur."
    override val aiSystemPrompt = "Sen, bir yer imi ve sekme yöneticisi olan stramus içindeki asistansın. " +
        "Sorunun sorulduğu dilde, kısa ve net cevap ver. Markdown kullanabilirsin."

    override val aiTriageSetting = "Sekmeleri yerleşik modelle sırala"
    override val aiTriageSettingHint = "Bir sekme penceresine bir düğme ekler: model sekmeleri okur ve " +
        "her biri için, herhangi bir şey kaydedilmeden önce senin kontrol edebileceğin bir koleksiyon önerir. " +
        "Her şey bu cihazda kalır. Büyük bir pencerede bir iki dakika sürer ve sınıflandıramadıklarını dışarıda bırakır."
    override val triageTabs = "Koleksiyonlara ayır"
    override val triageHeading = "Sekmeleri koleksiyonlara ayır"
    override val triageSummaryHeading = "Bu oturum neyle ilgiliydi"
    override val triageSummaryTitle = "Oturum özeti"
    override val triageNew = "yeni"
    override fun triageNewHint(section: String) = "Bu koleksiyon henüz yok — “$section” içinde oluşturulacak."
    override val triageNewSectionHint = "Bu grup bu koleksiyonda henüz yok — oluşturulacak."
    override val triageGroupHint = "Bu yeni koleksiyon kenar çubuğunun hangi bölümünde oluşturulacak"
    override val triageSectionHint = "Bu sekme koleksiyonun hangi grubuna girecek"
    override val triageNoSection = "Grup yok"
    override fun triageProgress(done: Int, total: Int) = "Siteler sıralanıyor — $total üzerinden $done…"
    override val triageUnsorted = "Sıralanmadı"
    override val triageUnsortedHint = "Model bunlar hakkında bir şey söylemedi. Bir koleksiyon seç, ya da açık bırak."
    override val triageSkip = "Kaydetme"
    override val triageMoveHint = "Bu sekme hangi koleksiyona girecek"
    override val triageDuplicate = "zaten kayıtlı"
    override val triageDuplicateHint = "Bu sayfa zaten bir koleksiyonda var. Tekrar kaydetmek için işaretle."
    override fun triageRelated(site: String, count: Int) = "$site sitesinden zaten kayıtlı ($count):"
    override fun triageApply(count: Int, closesTabs: Boolean) =
        if (closesTabs) "Kaydet ($count) ve kapat" else "Kaydet ($count)"
    override val aiTriageSystemPrompt = "Bir kullanıcının tarayıcısında açık olan sekmeleri onun koleksiyonlarına " +
        "ayırıyorsun. Sana sekmeler ve mevcut koleksiyonlar veriliyor. Her sekme için, ait olduğu tek " +
        "koleksiyonla cevap ver — sekme uyduğu her yerde mevcut bir adı yeniden kullan, ve hiçbirine " +
        "uymuyorsa sadece kısa yeni bir ad (bir ya da iki kelime) uydur. Bir koleksiyon içinde, mevcut " +
        "olanları da yeniden kullanarak bir grup belirtebilirsin. Aynı sitenin sekmeleri farklı " +
        "koleksiyonlara ait olabilir. Sadece istenen JSON ile cevap ver."

    override val aiSection = "YZ"
    override val aiAssistant = "Asistan"
    override val aiAssistantHint = "Arama kutusundan sorulan bir soruyu kimin yanıtladığı."
    override val aiProviderLocal = "Cihaz üzerinde"
    override fun aiWebChatHint(assistant: String) =
        "Soru, bu sekmede $assistant'ı açar ve soru zaten sorulmuş olur. Bu, $assistant'ın sunucularına " +
            "gönderilir — burada yanıt veren ve her şeyi bu cihazda tutan cihaz üzerindeki modelin aksine."

    override val aiModel = "Model"
    override val aiModelReadyHint = "Tarayıcının yerleşik modeli. Bu cihazda çalışır — anahtar gerekmez, hiçbir şey dışarı çıkmaz."
    override val aiModelDownloadableHint = "Tarayıcı bunu ilk soruda indirecek — birkaç yüz megabayt, yalnızca bir kez."
    override val aiModelDownloadingHint = "Tarayıcı şu anda indiriyor."
    override val aiModelNone = "Kullanılamıyor"
    override val aiModelNoneHint =
        "Bu tarayıcı sayfaya yerleşik bir model sunmuyor, bu yüzden arama da ona soru sormayı önermiyor. " +
            "Chrome'da bu, uzantı için kullanılabilir; sıradan bir web sayfasının bunun için bayraklara ihtiyacı vardır."
    override fun aiModelUnsupported(name: String) = "$name — kullanılamıyor"
    override val aiModelUnsupportedHint =
        "Tarayıcıda model var ama burada çalıştıramıyor: Chrome profilini içeren diskte ~22 GB boş alan " +
            "ve 4 GB'den fazla belleğe sahip bir GPU gerekiyor."

    override fun resultsFor(query: String) = "“$query” için sonuçlar"
    override val noMatchingLinks = "Eşleşen bağlantı yok."
    override val createCollectionToStart = "Bağlantı kaydetmeye başlamak için bir koleksiyon oluştur."
    override val sortLinks = "Bu grubun kartlarını sırala"
    override val sortMenuTitle = "Sırala"
    override val addCardSection = "Grup"
    override val pasteUrl = "Bir URL yapıştır"
    override val addLinkItem = "Bağlantı"
    override val addNoteItem = "Not"
    override val addFileItem = "Dosya"
    override val noLinksYet = "Henüz bağlantı yok — bir tane ekle, ya da buraya sürükle."
    override val ungrouped = "Grupsuz"
    override val dragLinksHere = "Bağlantıları veya dosyaları buraya sürükle."
    override val editDescription = "Açıklamayı düzenle"

    override fun filesTooLarge(names: List<String>, maxMb: Int) =
        "Kaydedilmedi — $maxMb MB'ı aşıyor: ${names.joinToString(", ")}"

    override val protectSection = "PIN ile koru"
    override val sectionProtection = "Bölüm koruması"
    override val changePin = "PIN'i değiştir"
    override val removeProtection = "Korumayı kaldır"
    override val lockNow = "Şimdi kilitle"
    override val lockedSection = "PIN ile korunuyor"
    override val unlockedSection = "Kilidi açık — yeniden kilitlemek için tıkla"
    override val enterPinToView = "Bu bölümün koleksiyonlarını görmek için PIN'i gir."
    override val pinPlaceholder = "PIN"
    override val unlock = "Kilidi aç"
    override val wrongPin = "Yanlış PIN."
    override val setPinHeading = "Bölümü koru"
    override val changePinHeading = "PIN'i değiştir"
    override val newPinLabel = "Yeni PIN"
    override val repeatPinLabel = "PIN'i tekrar gir"
    override val pinMismatch = "İki PIN birbirini tutmuyor."
    override fun pinTooShort(min: Int) = "PIN en az $min haneli olmalı."
    override val pinNote = "PIN tüm bölümü gizler: girilmeden koleksiyonlarının adları bile görünmez, " +
        "kartları arama ve dışa aktarmanın dışında kalır. Unutulan bir PIN sıfırlanamaz."

    override val makeReadOnlyHint = "Salt okunur yap: burada artık hiçbir şey eklenemez, değiştirilemez veya silinemez."
    override val allowEditing = "Düzenlemeye izin ver"
    override val allowEditingHint = "Düzenlemeye yeniden izin ver."
    override val readOnlyBadge = "salt okunur"
    override val readOnlyHint = "Salt okunur: burada hiçbir şey eklenemez, değiştirilemez veya silinemez."

    override val security = "Güvenlik"
    override val autoLock = "Otomatik kilit"
    override val autoLockHint = "Bu kadar süre etkinlik olmadığında kilidi açık bölümleri yeniden kilitle."
    override val autoLockNever = "Asla"
    override fun autoLockMinutes(minutes: Int) = "$minutes dk"

    override val openTabs = "Açık sekmeler"
    override val showTabs = "Açık sekmeleri göster"
    override val hideTabs = "Açık sekmeleri gizle"
    override val noOpenTabs = "Kaydedilecek açık sekme yok."
    override val searchTabs = "Sekmelerde ara…"
    override val noMatchingTabs = "Eşleşen sekme yok."
    override val thisWindow = "Bu pencere"
    override fun windowLabel(number: Int) = "Pencere $number"
    override val closeTab = "Sekmeyi kapat"
    override val sortTabs = "Bu pencerenin sekmelerini sırala"
    override fun saveTabsHint(count: Int, closing: Boolean) =
        "Bu pencerenin sekmelerini ($count) açık koleksiyona, grupsuz olarak kaydet — " +
            if (closing) "ve kapat" else "ve açık bırak"

    override fun confirmSaveTabs(count: Int, collection: String, closing: Boolean) =
        if (closing) "Bu pencerenin sekmeleri ($count) “$collection” içine kaydedilip kapatılsın mı?"
        else "Bu pencerenin sekmeleri ($count) “$collection” içine kaydedilsin mi?"

    override val tabsSection = "Sekmeler"
    override val closeSavedTabs = "Sekmeleri kaydettikten sonra"
    override val closeSavedTabsHint =
        "Bir pencerenin sekmeleri bir koleksiyona kaydedildikten sonra onlara ne olacağı."
    override val closeSavedTabsClose = "Kapat"
    override val closeSavedTabsKeep = "Açık bırak"

    override val paneTabs = "Sekmeler"
    override val paneHistory = "Geçmiş"
    override val searchHistory = "Geçmişte ara…"
    override val noHistory = "Geçmişte henüz bir şey yok."
    override val noMatchingHistory = "Geçmişte eşleşen bir şey yok."
    override val today = "Bugün"
    override val yesterday = "Dün"
    override val removeFromHistory = "Geçmişten kaldır"

    override val emptyNote = "Boş not"
    override val fileLabel = "dosya"
    override val renameCard = "Düzenle"
    override val cardNamePrompt = "Kart başlığı"
    override val renameHeading = "Kartı düzenle"
    override val renameShowUrl = "Adresi göster"
    override val renameHideUrl = "Adresi gizle"
    override val renameUrlPrompt = "Adres"

    override val newNote = "Yeni not"
    override val editNote = "Notu düzenle"
    override val viewNote = "Not"
    override val editNoteAction = "Düzenle"
    override val sectionDescription = "Grup açıklaması"
    override val titlePlaceholder = "Başlık"
    override val noteDefaultTitle = "Not"
    override val notePlaceholder = "Yazmaya başla…"
    override val toolBold = "Kalın"
    override val toolItalic = "İtalik"
    override val toolHighlight = "Vurgula"
    override val toolCode = "Kod"
    override val toolLink = "Bağlantı"
    override val toolHeading = "Başlık"
    override val toolList = "Madde işaretli liste"
    override val toolListLabel = "Liste"
    override val highlightPlaceholder = "vurgu"
    override val codePlaceholder = "kod"
    override val linkUrlPrompt = "Bağlantı URL'si"
    override val draftRestored = "Kaydedilmemiş taslak geri yüklendi"
    override val discardDraft = "Sıfırla"

    override val addFile = "Dosya ekle"
    override val chooseFile = "Bir dosya seç…"
    override val download = "İndir"
    override val fileDefaultTitle = "Dosya"
    override fun noPreviewFor(mime: String) = "$mime için satır içi önizleme yok — İndir'i kullan."

    override val appearance = "Görünüm"
    override val theme = "Tema"
    override val themeHint = "Sistemi izle, ya da gündüz/gece modunu zorla."
    override val themeAuto = "Otomatik"
    override val themeLight = "Açık"
    override val themeDark = "Koyu"
    override val accentColor = "Vurgu rengi"
    override val accentColorHint = "Düğmelerin, seçimin ve vurguların arkasındaki marka rengi."
    override val accentBlue = "Mavi"
    override val accentPurple = "Mor"
    override val accentGreen = "Yeşil"
    override val accentOrange = "Turuncu"
    override val accentRose = "Gül"
    override val language = "Dil"
    override val languageHint = "Arayüzün dili."
    override val cardUrls = "Kart adresleri"
    override val cardUrlsHint = "Bir bağlantı kartının başlığın altında adresini gösterip göstermeyeceği."
    override val cardUrlsShow = "Göster"
    override val cardUrlsHide = "Gizle"
    override val groupsView = "Bölüm görünümü"
    override val groupsViewHint =
        "Bir koleksiyonun bölümlerini alt alta göster, ya da bulundukları yerde açılan klasörler olarak göster."
    override val groupsViewList = "Liste"
    override val groupsViewFolders = "Klasörler"
    override val folderBack = "Klasörlere dön"
    override val swapSidebars = "Kenar çubuğu sırası"
    override val swapSidebarsHint = "Bölümler kenar çubuğunun, sekmeler/geçmiş kenar çubuğuna göre hangi tarafta olduğu."
    override val swapSidebarsLeft = "Bölümler solda"
    override val swapSidebarsRight = "Bölümler sağda"
    override val tabsCardView = "Sekme görünümü"
    override val tabsCardViewHint =
        "Açık sekmeleri liste olarak, ya da orta panelle aynı genişlikte bir kart ızgarası olarak göster."
    override val tabsCardViewList = "Liste"
    override val tabsCardViewCards = "Kartlar"

    override val startupSection = "Başlangıç"
    override val startView = "Açılışta"
    override val startViewHint = "stramus açıldığında hangi koleksiyonun gösterileceği. Bir PIN'in arkasındaki " +
        "koleksiyon asla bu olmaz — her yeniden yüklemede bölümü yeniden kilitlenir."
    override val startViewLast = "Son açılan"
    override val startViewFirst = "İlk koleksiyon"

    override val dataSection = "Veri"

    override val export = "Dışa aktar"
    override val exportHint = "Tüm koleksiyonlardaki tüm kayıtlı bağlantıları indir. PIN'i henüz girilmemiş " +
        "bir bölüm dışarıda bırakılır."
    override val exportCsv = "CSV olarak dışa aktar"
    override val exportBookmarks = "Yer imlerini dışa aktar"

    override val import = "İçe aktar"
    override val importHint = "Herhangi bir tarayıcıdan bir yer imi dosyası, ya da burada dışa aktarılmış bir " +
        "CSV içe aktar. Klasörler bölümlere, koleksiyonlara ve gruplara dönüşür; ineceği yerde zaten " +
        "kayıtlı olan bir bağlantı olduğu gibi bırakılır."
    override val importFile = "Bir dosya seç"
    override val importedTitle = "İçe aktarıldı"
    override fun importDone(added: Int, skipped: Int) = when (skipped) {
        0 -> "$added bağlantı içe aktarıldı."
        else -> "$added bağlantı içe aktarıldı; $skipped tanesi zaten kayıtlıydı."
    }
    override val importNothing = "O dosyada içe aktarılacak bağlantı yok."

    override val sortTitle = "Başlık A–Z"
    override val sortUrl = "URL"
    override val sortDomain = "Alan adı"
    override val sortNewest = "Önce en yeni"
    override val sortOldest = "Önce en eski"

    override val account = "Hesap"
    override val accountSignedOutHint = "Koleksiyonlarını kullandığın her tarayıcıda tutmak için oturum aç. Hesap olmadan da her şey çalışır — sadece bu cihazda kalır."
    override val signInAccount = "Oturum aç"
    override val signOut = "Oturumu kapat"
    override val syncNow = "Şimdi senkronize et"
    override fun syncedAt(time: String) = "$time saatinde senkronize edildi"
    override fun conflictCopies(count: Int) =
        if (count == 1) "Bir not iki cihazda aynı anda düzenlendi. Her iki sürüm de korundu."
        else "$count not iki cihazda aynı anda düzenlendi. Her birinin iki sürümü de korundu."
    override val joinAccountTitle = "Bu tarayıcıda zaten koleksiyonlar var"
    override val joinAccountHint = "Onları hesaba ekleyebilir, ya da burada bırakıp hesapta zaten var olanları alabilirsin."
    override val joinAccountKeep = "Hesaba ekle"
    override val joinAccountDiscard = "Hesabın koleksiyonlarını kullan"
    override val exportAccountData = "Verilerimi indir"
    override val exportAccountDataHint = "Sunucunun bu hesap hakkında sakladığı her satır, JSON olarak."
    override val exportAccountDataFailed = "Dışa aktarma indirilemedi."
    override val deleteAccount = "Hesabı sil"
    override val deleteAccountHint = "Sunucunun sakladığı her şeyi siler. Bu cihazda olanlar kalır."
    override val deleteAccountConfirm = "Hesap ve sunucunun sakladığı her şey silinsin mi? Bu geri alınamaz."
    override val syncUsage = "Tarama istatistiklerini senkronize et"
    override val syncUsageHint = "Hangi sayfaları ne sıklıkla açtığın — aramanın sıralamada kullandığı şey. Kapalı, bunun bu cihazda kaldığı anlamına gelir."
    override val optionOn = "Açık"
    override val optionOff = "Kapalı"
    override val signInWithGoogle = "Google ile devam et"
    override val signInUnavailable = "Bu derlemede oturum açma ayarlanmamış. Uygulama her zaman olduğu gibi hesap olmadan da çalışır."
    override val serverUnavailable = "Sunucu şu anda yanıt vermiyor. Bunun için sunucuya ihtiyaç var — geri geldiğinde tekrar dene."

    override val onboardingSignInTitle = "Her yerde eşitlemek için oturum aç"
    override val onboardingSignInBody = "Oturum açana kadar koleksiyonların yalnızca bu cihazda kalır — oturum açtığında, kullandığın her tarayıcıya otomatik olarak taşınır."
    override val onboardingSkip = "Şimdi değil"
    override val onboardingInstallTitle = "Uzantıyı yükle"
    override val onboardingInstallBody = "stramus bu sekmede zaten çalışır, ancak uzantı kendi yeni sekme sayfasını, tek tıkla sekme kaydetmeyi ve açık sekmelerinle geçmişin üzerinde arama yapmayı ekler — eksiksiz deneyim."
    override val onboardingInstallCta = "Chrome Web Store'dan yükle"
    override val onboardingContinueInBrowser = "Tarayıcıda devam et"
    override val onboardingOrganizeTitle = "Bölümlere gruplanmış koleksiyonlar"
    override val onboardingOrganizeBody = "Kenar çubuğu bölümlerini tutar; her biri kendi koleksiyonlarını açar. Bir sekmeyi, bağlantıyı ya da dosyayı herhangi bir koleksiyona sürükleyerek kaydet — sen istemedikçe hiçbir şey cihazından ayrılmaz."
    override val onboardingSearchTitle = "Her şey için tek bir arama"
    override val onboardingSearchBody = "Üstteki arama kutusu kayıtlı kartları, açık sekmeleri ve tarayıcı geçmişini aynı anda bulur — yazmaya başla, stramus her yeri senin için arasın."
    override val onboardingBack = "Geri"
    override val onboardingNext = "İleri"
    override val onboardingGetStarted = "Başla"

    override val seed = StoreSeed(
        sectionTitle = "Ana",
        collectionTitle = "Başlarken",
        noteTitle = "stramus nasıl kullanılır",
        // Her madde tek bir satırdır: burada `Markdown.kt` tarafından okunan markdown var, ve içinde
        // bir satır sonu, listeyi devam ettirmek yerine bitirir.
        noteBody = """
            # stramus'a hoş geldin

            Soldaki kenar çubuğu **bölümler** içerir, bir bölüm **koleksiyonlar** içerir, ve bir koleksiyon kartlar içerir — bağlantılar, dosyalar ve bunun gibi notlar.

            ## Bir sayfayı kaydetme
            - Sağ kenar çubuğundaki bir sekmeyi bir koleksiyona sürükle, ya da tüm bir pencereyi bir kerede kaydetmek için **⤓ Açık sekmeleri kaydet**'i kullan.
            - Bir bölümün başlığının üzerine gel ve yapıştırılmış bir adresi, bir notu ya da bir dosyayı doğrudan o bölüme eklemek için **+**'ya bas.
            - **+ Grup**, büyük bir koleksiyonu gruplara böler — bir kartı bir grubun üzerine bırakarak oraya taşı.

            ## Bir sayfa bulma
            - Üstteki arama kutusu her şeyi aynı anda arar: kaydettiklerini, açık olan sekmelerini, ve nerelerde bulunduğunu.
            - Açmak için bir adres yaz, ya da tarayıcının yerleşik modeline sormak için bir soru yaz.
            - Seçmek için ↑↓, açmak için Enter, kapatmak için Esc.

            ## Düzeni korumak
            - **PIN ile koru**: kilitli bir bölüm koleksiyonlarının adlarını bile göstermez, ve sen uzaklaştığında yeniden kilitlenir.
            - **🔒 Salt okunur**, bitmiş bir koleksiyonu yanlışlıkla dokunmaktan korur.
            - Ayarlar; temayı, dili ve her şeyin CSV'ye ya da yer imlerine dışa aktarılmasını içerir.

            Bu koleksiyonu yeniden adlandır, ya da bu notu sil — artık bunların hepsi senin.
        """.trimIndent(),
    )
}

