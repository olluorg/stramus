package stramus.ui

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

    // Content area
    val searchPlaceholder: String
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

    // Sort modes
    val sortManual: String
    val sortTitle: String
    val sortUrl: String
    val sortNewest: String
    val sortOldest: String
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

    override val searchPlaceholder = "Search all links…"
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
    override val sortNewest = "Newest first"
    override val sortOldest = "Oldest first"
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

    override val searchPlaceholder = "Поиск по всем ссылкам…"
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
    override val sortNewest = "Сначала новые"
    override val sortOldest = "Сначала старые"
}

