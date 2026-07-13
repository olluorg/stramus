package stramus.ui

import stramus.core.db.StoreSeed

/** A UI language. [id] is what gets persisted in localStorage and stamped on `<html lang>`. */
enum class Lang(val id: String, val label: String) {
    EN("en", "English"),
    RU("ru", "Русский"),
    ;

    val strings: Strings get() = if (this == RU) RuStrings else EnStrings

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
    val settings: String
    val close: String
    val cancel: String
    val save: String

    // Left sidebar
    val expandSidebar: String
    val collapseSidebar: String
    val newSection: String
    val sectionNamePrompt: String
    val sectionNameDefault: String
    val collectionNamePrompt: String
    val collectionNameDefault: String

    /** Tooltip on a section title, which collapses on a click and renames on a double click. */
    val renameHint: String

    /** Tooltip on a collection title, which is selected on a click and renamed on a double click. */
    val renameCollectionHint: String

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

    // Deleting, and the way back from it. Only what holds something is asked about — deleting an
    // empty section is nothing to stop the user over — but everything deleted can be undone.
    fun confirmDeleteSection(title: String, cards: Int): String
    fun confirmDeleteCollection(title: String, cards: Int): String
    fun confirmDeleteCardSection(title: String, cards: Int): String
    fun deletedSection(title: String): String
    fun deletedCollection(title: String): String
    fun deletedCardSection(title: String): String

    /** The button on the toast that puts the deleted thing back. */
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

    /** The action rows themselves. */
    fun hitWebSearch(query: String): String
    fun hitOpenUrl(query: String): String
    fun hitAskAiRow(query: String): String

    /** The × on a top site: stop counting this page among the ones the user lives in. */
    val forgetSite: String

    /** The keys, spelled out under the list. */
    val searchHints: String

    // The built-in model (Chrome's on-device AI)
    val aiChip: String
    val aiChipHint: String
    val aiPlaceholder: String
    val aiHeading: String
    val aiThinking: String
    val aiCopy: String
    val aiSaveNote: String
    val aiClose: String
    val aiUnavailable: String
    val aiFailed: String

    /** The model itself is downloaded on first use — a few hundred megabytes, once. */
    fun aiDownloading(percent: Int): String

    /** What the model is told before the first question: where it is, and how to answer. */
    val aiSystemPrompt: String

    // Settings: which model answers, and — when none does — why not
    val aiSection: String
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
    val sortLinks: String
    val addCardSection: String
    val saveOpenTabs: String
    val pasteUrl: String
    val addLink: String
    val addLinkItem: String
    val addNoteItem: String
    val addFileItem: String
    val noLinksYet: String
    val ungrouped: String
    val dragLinksHere: String
    val editDescription: String

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
    /** The two ends of the toggle in the collection's header, and the badge a guarded one wears. */
    val makeReadOnly: String
    val allowEditing: String
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
    val tabsButton: String
    val noOpenTabs: String
    val searchTabs: String
    val noMatchingTabs: String
    /** The window the app itself is open in, told apart from the user's other windows. */
    val thisWindow: String
    fun windowLabel(number: Int): String
    val goToTab: String
    val closeTab: String
    /** The ⇅ menu in a window's header, which rearranges that window's tabs in the browser itself. */
    val sortTabs: String
    /**
     * The two ⤓ — the one in a window's header, the one in the collection's toolbar — say the same
     * thing: how many tabs the click would save, where they land, and whether the browser keeps them.
     * [closing] is the "after saving tabs" setting, so the tooltip is the answer the user chose.
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

    // Note editor
    val newNote: String
    val editNote: String
    val sectionDescription: String
    val titlePlaceholder: String
    val noteDefaultTitle: String
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
    val language: String
    val languageHint: String
    val export: String
    val exportHint: String
    val exportCsv: String
    val exportBookmarks: String

    // Sort modes. Cards take all of them; a window's tabs are sorted by title, domain or URL.
    val sortManual: String
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
    val seed: StoreSeed
}

private object EnStrings : Strings {
    override val settings = "Settings"
    override val close = "Close"
    override val cancel = "Cancel"
    override val save = "Save"

    override val expandSidebar = "Expand sidebar"
    override val collapseSidebar = "Collapse sidebar"
    override val newSection = "+ New section"
    override val sectionNamePrompt = "Section name"
    override val sectionNameDefault = "New section"
    override val collectionNamePrompt = "Collection name"
    override val collectionNameDefault = "New collection"
    override val renameHint = "Click to collapse, double-click to rename"
    override val renameCollectionHint = "Double-click to rename"

    override val newSectionHint = "Add a section to the sidebar"
    override val addCollectionHint = "Add a collection to this section"
    override val deleteSectionHint = "Delete this section and the collections in it"
    override val deleteCollectionHint = "Delete this collection and its cards"
    override val addCardSectionHint = "Add a section to this collection"
    override val deleteCardSectionHint = "Delete this section — its cards stay in the collection, ungrouped"
    override val addCardHint = "Add a link — or, from the menu, a note or a file"
    override val deleteCardHint = "Delete this card"

    override fun confirmDeleteSection(title: String, cards: Int) =
        "“$title” and its collections hold $cards saved items. Delete the section?"
    override fun confirmDeleteCollection(title: String, cards: Int) =
        "“$title” holds $cards saved items. Delete the collection?"
    override fun confirmDeleteCardSection(title: String, cards: Int) =
        "“$title” holds $cards cards. Delete the section? The cards stay, ungrouped."
    override fun deletedSection(title: String) = "Section “$title” deleted"
    override fun deletedCollection(title: String) = "Collection “$title” deleted"
    override fun deletedCardSection(title: String) = "Section “$title” deleted"
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
    override fun hitAskAiRow(query: String) = "Ask the AI: “$query”"

    override val forgetSite = "Stop suggesting this page"
    override val searchHints = "↑↓ choose · Enter open · Alt+Enter search the web · ⌘/Ctrl+Enter all results · Esc close"

    override val aiChip = "AI"
    override val aiChipHint = "Asking the browser's built-in model — Esc to go back to search"
    override val aiPlaceholder = "Ask a follow-up…"
    override val aiHeading = "Answer"
    override val aiThinking = "Thinking…"
    override val aiCopy = "Copy"
    override val aiSaveNote = "Save as note"
    override val aiClose = "Back to search"
    override val aiUnavailable = "This browser has no built-in model available."
    override val aiFailed = "The model could not answer."
    override fun aiDownloading(percent: Int) = "Downloading the model — $percent%. This happens once."
    override val aiSystemPrompt = "You are the assistant inside stramus, a bookmark and tab manager. " +
        "Answer briefly and to the point, in the language the question is asked in. Markdown is welcome."

    override val aiSection = "AI"
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
    override val sortLinks = "Sort links"
    override val addCardSection = "+ Section"
    override val saveOpenTabs = "⤓ Save open tabs"
    override val pasteUrl = "Paste a URL"
    override val addLink = "+ Add link ▾"
    override val addLinkItem = "🔗 Link"
    override val addNoteItem = "📝 Note"
    override val addFileItem = "📎 File"
    override val noLinksYet = "No links yet — add one, or drag one here."
    override val ungrouped = "Ungrouped"
    override val dragLinksHere = "Drag links here."
    override val editDescription = "Edit description"

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

    override val makeReadOnly = "🔒 Read-only"
    override val allowEditing = "✎ Allow editing"
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
    override val tabsButton = "⧉ Tabs"
    override val noOpenTabs = "No open tabs to save."
    override val searchTabs = "Search tabs…"
    override val noMatchingTabs = "No matching tabs."
    override val thisWindow = "This window"
    override fun windowLabel(number: Int) = "Window $number"
    override val goToTab = "Go to this tab"
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
    override val renameCard = "Rename"
    override val cardNamePrompt = "Card title"

    override val newNote = "New note"
    override val editNote = "Edit note"
    override val sectionDescription = "Section description"
    override val titlePlaceholder = "Title"
    override val noteDefaultTitle = "Note"
    override val toolBold = "Bold"
    override val toolItalic = "Italic"
    override val toolHighlight = "Highlight"
    override val toolCode = "Code"
    override val toolLink = "Link"
    override val toolHeading = "Heading"
    override val toolList = "Bulleted list"
    override val toolListLabel = "• List"
    override val highlightPlaceholder = "highlight"
    override val codePlaceholder = "code"
    override val linkUrlPrompt = "Link URL"

    override val addFile = "Add file"
    override val chooseFile = "Choose a file…"
    override val download = "⤓ Download"
    override val fileDefaultTitle = "File"
    override fun noPreviewFor(mime: String) = "No inline preview for $mime — use Download."

    override val appearance = "Appearance"
    override val theme = "Theme"
    override val themeHint = "Follow the system, or force day/night."
    override val themeAuto = "◑ Auto"
    override val themeLight = "☀ Light"
    override val themeDark = "☾ Dark"
    override val language = "Language"
    override val languageHint = "The language of the interface."
    override val export = "Export"
    override val exportHint = "Download every saved link across all collections. A section still behind " +
        "its PIN is left out."
    override val exportCsv = "⤒ Export CSV"
    override val exportBookmarks = "⤒ Export bookmarks"

    override val sortManual = "Manual"
    override val sortTitle = "Title A–Z"
    override val sortUrl = "URL"
    override val sortDomain = "Domain"
    override val sortNewest = "Newest first"
    override val sortOldest = "Oldest first"

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
            - **+ Add link** takes a pasted address, a note, or a file.
            - **+ Section** splits a large collection into groups — drop a card on one to move it in.

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
    override val settings = "Настройки"
    override val close = "Закрыть"
    override val cancel = "Отмена"
    override val save = "Сохранить"

    override val expandSidebar = "Развернуть панель"
    override val collapseSidebar = "Свернуть панель"
    override val newSection = "+ Новый раздел"
    override val sectionNamePrompt = "Название раздела"
    override val sectionNameDefault = "Новый раздел"
    override val collectionNamePrompt = "Название коллекции"
    override val collectionNameDefault = "Новая коллекция"
    override val renameHint = "Клик — свернуть, двойной клик — переименовать"
    override val renameCollectionHint = "Двойной клик — переименовать"

    override val newSectionHint = "Создать раздел в боковой панели"
    override val addCollectionHint = "Добавить коллекцию в этот раздел"
    override val deleteSectionHint = "Удалить раздел вместе с его коллекциями"
    override val deleteCollectionHint = "Удалить коллекцию вместе с её карточками"
    override val addCardSectionHint = "Добавить раздел в эту коллекцию"
    override val deleteCardSectionHint = "Удалить раздел — его карточки останутся в коллекции, без раздела"
    override val addCardHint = "Добавить ссылку — или, из меню, заметку либо файл"
    override val deleteCardHint = "Удалить карточку"

    override fun confirmDeleteSection(title: String, cards: Int) =
        "В разделе «$title» и его коллекциях сохранено элементов: $cards. Удалить раздел?"
    override fun confirmDeleteCollection(title: String, cards: Int) =
        "В коллекции «$title» сохранено элементов: $cards. Удалить коллекцию?"
    override fun confirmDeleteCardSection(title: String, cards: Int) =
        "В секции «$title» карточек: $cards. Удалить секцию? Карточки останутся — без секции."
    override fun deletedSection(title: String) = "Раздел «$title» удалён"
    override fun deletedCollection(title: String) = "Коллекция «$title» удалена"
    override fun deletedCardSection(title: String) = "Секция «$title» удалена"
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
    override fun hitAskAiRow(query: String) = "Спросить ИИ: «$query»"

    override val forgetSite = "Больше не предлагать эту страницу"
    override val searchHints = "↑↓ выбрать · Enter открыть · Alt+Enter — поиск в вебе · ⌘/Ctrl+Enter — все результаты · Esc закрыть"

    override val aiChip = "ИИ"
    override val aiChipHint = "Вопрос встроенной модели браузера — Esc, чтобы вернуться к поиску"
    override val aiPlaceholder = "Спросить ещё…"
    override val aiHeading = "Ответ"
    override val aiThinking = "Думает…"
    override val aiCopy = "Скопировать"
    override val aiSaveNote = "Сохранить заметкой"
    override val aiClose = "Вернуться к поиску"
    override val aiUnavailable = "В этом браузере встроенная модель недоступна."
    override val aiFailed = "Модель не смогла ответить."
    override fun aiDownloading(percent: Int) = "Модель скачивается — $percent%. Это происходит один раз."
    override val aiSystemPrompt = "Ты — помощник внутри stramus, менеджера закладок и вкладок. " +
        "Отвечай кратко и по делу, на языке вопроса. Markdown приветствуется."

    override val aiSection = "ИИ"
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
    override val sortLinks = "Сортировка ссылок"
    override val addCardSection = "+ Раздел"
    override val saveOpenTabs = "⤓ Сохранить вкладки"
    override val pasteUrl = "Вставьте ссылку"
    override val addLink = "+ Добавить ссылку ▾"
    override val addLinkItem = "🔗 Ссылка"
    override val addNoteItem = "📝 Заметка"
    override val addFileItem = "📎 Файл"
    override val noLinksYet = "Пока нет ссылок — добавьте одну или перетащите сюда."
    override val ungrouped = "Без раздела"
    override val dragLinksHere = "Перетащите ссылки сюда."
    override val editDescription = "Изменить описание"

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

    override val makeReadOnly = "🔒 Только чтение"
    override val allowEditing = "✎ Разрешить правку"
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
    override val tabsButton = "⧉ Вкладки"
    override val noOpenTabs = "Нет открытых вкладок."
    override val searchTabs = "Поиск по вкладкам…"
    override val noMatchingTabs = "Вкладки не найдены."
    override val thisWindow = "Это окно"
    override fun windowLabel(number: Int) = "Окно $number"
    override val goToTab = "Перейти к вкладке"
    override val closeTab = "Закрыть вкладку"
    override val sortTabs = "Отсортировать вкладки этого окна"
    override fun saveTabsHint(count: Int, closing: Boolean) =
        "Сохранить вкладки этого окна ($count) в открытую коллекцию, без раздела — " +
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
    override val renameCard = "Переименовать"
    override val cardNamePrompt = "Название карточки"

    override val newNote = "Новая заметка"
    override val editNote = "Изменить заметку"
    override val sectionDescription = "Описание раздела"
    override val titlePlaceholder = "Заголовок"
    override val noteDefaultTitle = "Заметка"
    override val toolBold = "Жирный"
    override val toolItalic = "Курсив"
    override val toolHighlight = "Выделение"
    override val toolCode = "Код"
    override val toolLink = "Ссылка"
    override val toolHeading = "Заголовок"
    override val toolList = "Маркированный список"
    override val toolListLabel = "• Список"
    override val highlightPlaceholder = "выделение"
    override val codePlaceholder = "код"
    override val linkUrlPrompt = "Адрес ссылки"

    override val addFile = "Добавить файл"
    override val chooseFile = "Выберите файл…"
    override val download = "⤓ Скачать"
    override val fileDefaultTitle = "Файл"
    override fun noPreviewFor(mime: String) = "Нет предпросмотра для $mime — используйте «Скачать»."

    override val appearance = "Оформление"
    override val theme = "Тема"
    override val themeHint = "Следовать системе или выбрать день/ночь."
    override val themeAuto = "◑ Авто"
    override val themeLight = "☀ День"
    override val themeDark = "☾ Ночь"
    override val language = "Язык"
    override val languageHint = "Язык интерфейса."
    override val export = "Экспорт"
    override val exportHint = "Скачайте все сохранённые ссылки из всех коллекций. Разделы, PIN-код " +
        "которых не введён, в экспорт не попадают."
    override val exportCsv = "⤒ Экспорт CSV"
    override val exportBookmarks = "⤒ Экспорт закладок"

    override val sortManual = "Вручную"
    override val sortTitle = "По названию"
    override val sortUrl = "По адресу"
    override val sortDomain = "По домену"
    override val sortNewest = "Сначала новые"
    override val sortOldest = "Сначала старые"

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
            - **+ Добавить ссылку** — вставленный адрес, заметка или файл.
            - **+ Раздел** делит большую коллекцию на группы: перетащите карточку на раздел, и она окажется в нём.

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

