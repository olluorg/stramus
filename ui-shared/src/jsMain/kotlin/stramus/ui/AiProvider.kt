package stramus.ui

/**
 * Who answers the question typed into the search box.
 *
 * [LOCAL] is the browser's own model: it answers in a window over the collection, on this machine, and
 * nothing is sent anywhere. The rest are the web chats the user may already be paying for and already
 * be signed into — they cannot answer inside the app, so the question opens *there*, in a chat with the
 * message already sent. That is the whole difference the setting makes, and it is not a small one: a
 * question asked of ChatGPT, Gemini or Claude leaves this machine.
 */
enum class AiProvider(val id: String) {
    LOCAL("local"),
    CHATGPT("chatgpt"),
    GEMINI("gemini"),
    CLAUDE("claude"),
    ;

    /** The option's label in the settings. The web chats are called what their makers call them. */
    fun label(s: Strings): String = when (this) {
        LOCAL -> s.aiProviderLocal
        CHATGPT -> "ChatGPT"
        GEMINI -> "Gemini"
        CLAUDE -> "Claude"
    }

    /**
     * The name in the search box's row — "Ask ChatGPT: …". The built-in model has no name to give, so
     * it is asked as what it is, which is also what the badge on its window says.
     */
    fun askName(s: Strings): String = if (this == LOCAL) s.aiChip else label(s)

    /**
     * The address of a chat with [question] already asked — each of the three takes it in the `q`
     * parameter of the page that starts a new conversation, and sends it on arrival, so the user lands
     * on the answer rather than on a filled-in box they still have to submit.
     *
     * Null for [LOCAL]: it has no page to open, it *is* the page.
     */
    fun chatUrl(question: String): String? {
        val q = encodeURIComponent(question)
        return when (this) {
            LOCAL -> null
            CHATGPT -> "https://chatgpt.com/?q=$q"
            GEMINI -> "https://gemini.google.com/app?q=$q"
            CLAUDE -> "https://claude.ai/new?q=$q"
        }
    }

    companion object {
        /**
         * Who answers where the browser has no model of its own — most browsers, today. It is only a
         * default: the settings offer the other two, and the choice is remembered.
         */
        val WEB_DEFAULT = CHATGPT

        fun from(id: String?): AiProvider = entries.firstOrNull { it.id == id } ?: LOCAL
    }
}

/** Where the chosen assistant is kept between sessions. */
internal const val AI_PROVIDER_PREF = "aiProvider"

/**
 * Where the tab triage's switch is kept. Absent means off — it is an experiment, and an install that
 * has never been asked has not said yes.
 */
internal const val AI_TRIAGE_PREF = "aiTriage"

/**
 * Where the triage's *cloud* switch is kept — a second, narrower opt-in on top of [AI_TRIAGE_PREF],
 * not a replacement for it: the triage feature itself must still be on, and this only decides which
 * model answers it. Absent means off, same reasoning as the feature it sits inside — a window of open
 * tabs going to a paid third party a user never asked about would be the opposite of what this app is
 * for. Shown only to a signed-in account (see `App.kt`), because it is meaningless without one.
 */
internal const val AI_TRIAGE_CLOUD_PREF = "aiTriageCloud"
