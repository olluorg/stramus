package stramus.web

import stramus.ui.Lang

/**
 * The words on the landing page, and nowhere else.
 *
 * They are kept apart from the app's own table (`I18n.kt`, which both the web app and the extension
 * compile) because this is the one screen the extension has no use for: an extension is already
 * installed by the time it runs, and nothing about it needs selling. Everything the *form* says —
 * "email", "password", "send code" — still comes from that shared table, so the door on the landing
 * page and the door inside the app never drift apart in wording.
 */
interface LandingStrings {
    val navExtension: String
    val navSource: String
    val navPrivacy: String

    val heroTitle: String
    val heroLead: String

    /** Opens the app for somebody who is already signed in — the landing reached deliberately, at `#about`. */
    val openApp: String
    val tryWithoutAccount: String
    val tryWithoutAccountHint: String

    val signInTitle: String
    val signInLead: String
    val signedInAs: (String) -> String

    val featuresTitle: String
    val features: List<Pair<String, String>>

    val syncTitle: String
    val syncLead: String

    val extensionTitle: String
    val extensionLead: String

    /** The made-up sidebar and cards in the picture of the app. Translated: it is a picture of *this* app. */
    val previewSections: List<String>
    val previewCollections: List<String>
    val previewCards: List<String>
    val previewSearch: String

    val footerNote: String
}

object EnLanding : LandingStrings {
    override val navExtension = "Chrome extension"
    override val navSource = "Source"
    override val navPrivacy = "Privacy"

    override val heroTitle = "Tabs that stop getting lost"
    override val heroLead = "stramus puts your open tabs into collections and sections, searches everything at " +
        "once — what you saved, what is open, where you have been — and keeps it all in the browser. " +
        "An account is only there to make it the same on your second computer."

    override val openApp = "Open stramus"
    override val tryWithoutAccount = "Try without an account"
    override val tryWithoutAccountHint = "Everything stays in this browser. Sign in later and it comes with you."

    override val signInTitle = "Sign in"
    override val signInLead = "Your mail and a one-time code — there is no password to invent."
    override val signedInAs = { email: String -> "Signed in as $email" }

    override val featuresTitle = "What it does"
    override val features = listOf(
        "Collections and sections" to
            "Section → collection → card. Links, files and notes stay where you put them, instead of in one " +
            "flat list of a thousand bookmarks.",
        "One search over everything" to
            "A single box looks through what you saved, what is open right now and where you have been. It " +
            "ranks by what you actually use, and goes to the tab you already have open rather than opening a " +
            "second copy of it.",
        "Local first" to
            "A SQLite database inside the browser. With no network everything still works — synchronisation " +
            "is a background job on top of it, never a condition of getting in.",
        "A PIN, and read-only" to
            "A locked section does not show so much as the names of its collections, and locks itself again " +
            "when you step away. A finished collection can be protected from a careless drag.",
    )

    override val syncTitle = "The account, and what it is for"
    override val syncLead = "Sign in and the same collections are on the other machine, and in the extension. " +
        "The work is still on this one: signing out or deleting the account leaves every card where it is. " +
        "The server is an addition, not the source of truth."

    override val extensionTitle = "In place of the new tab page"
    override val extensionLead = "The Chrome extension puts stramus where the new tab page was, saves a whole " +
        "window of tabs at once, and can bring in what is already in your browser history."

    override val previewSections = listOf("Main", "Reading")
    override val previewCollections = listOf("Work", "Kotlin", "To read", "Trip")
    override val previewCards = listOf("Design review", "Ktor docs", "Sync notes", "Flights")
    override val previewSearch = "Search everything"

    override val footerNote = "Free and open source."
}

object RuLanding : LandingStrings {
    override val navExtension = "Расширение для Chrome"
    override val navSource = "Исходники"
    override val navPrivacy = "Приватность"

    override val heroTitle = "Вкладки, которые больше не теряются"
    override val heroLead = "stramus раскладывает открытые вкладки по коллекциям и секциям, ищет сразу везде — " +
        "в сохранённом, в открытых вкладках и в истории — и держит всё это в браузере. Аккаунт нужен только " +
        "затем, чтобы на втором компьютере было ровно то же самое."

    override val openApp = "Открыть stramus"
    override val tryWithoutAccount = "Попробовать без аккаунта"
    override val tryWithoutAccountHint = "Всё останется в этом браузере. Войдёте позже — уедет вместе с вами."

    override val signInTitle = "Вход"
    override val signInLead = "Почта и одноразовый код — пароль придумывать не обязательно."
    override val signedInAs = { email: String -> "Вы вошли как $email" }

    override val featuresTitle = "Что он умеет"
    override val features = listOf(
        "Коллекции и секции" to
            "Раздел → коллекция → карточка. Ссылки, файлы и заметки лежат там, куда вы их положили, а не в " +
            "плоском списке на тысячу закладок.",
        "Один поиск по всему" to
            "Одна строка смотрит в сохранённое, в открытые вкладки и в историю. Ранжирует по тому, чем вы " +
            "правда пользуетесь, и переходит на уже открытую вкладку, а не открывает её вторую копию.",
        "Сначала локально" to
            "База SQLite живёт прямо в браузере. Без сети всё работает по-прежнему: синхронизация — фоновая " +
            "задача поверх, а не условие входа.",
        "PIN и только чтение" to
            "Закрытый раздел не показывает даже названий коллекций и запирается снова, когда вы отходите. " +
            "Законченную коллекцию можно уберечь от случайного движения руки.",
    )

    override val syncTitle = "Зачем аккаунт"
    override val syncLead = "Войдите — и те же коллекции окажутся на другой машине и в расширении. Работа " +
        "при этом остаётся и на этой: выход из аккаунта и даже его удаление не трогают ни одной карточки. " +
        "Сервер — дополнение, а не источник истины."

    override val extensionTitle = "Вместо страницы новой вкладки"
    override val extensionLead = "Расширение для Chrome ставит stramus на место новой вкладки, сохраняет целое " +
        "окно вкладок разом и умеет забрать то, что уже лежит в истории браузера."

    override val previewSections = listOf("Главный", "Чтение")
    override val previewCollections = listOf("Работа", "Kotlin", "Прочитать", "Поездка")
    override val previewCards = listOf("Ревью дизайна", "Документация Ktor", "Заметки о синке", "Билеты")
    override val previewSearch = "Искать везде"

    override val footerNote = "Бесплатно, с открытым исходным кодом."
}

/** The landing's table for a language, beside [Lang.strings] — which stays the app's own. */
val Lang.landing: LandingStrings get() = if (this == Lang.RU) RuLanding else EnLanding
