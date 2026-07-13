package stramus.core.platform

import kotlinx.coroutines.flow.Flow

/**
 * Whether the browser can answer at all, and at what cost.
 *
 * The model is a download of some hundreds of megabytes that the browser fetches once, on first use,
 * and then keeps; [DOWNLOADABLE] means the machine can run it but has not fetched it yet, so the first
 * question will take a while and the UI has to say so rather than appear to hang.
 */
enum class AiAvailability { UNAVAILABLE, DOWNLOADABLE, DOWNLOADING, AVAILABLE }

/** One conversation with the model: questions in order, each answer streamed back. */
interface AiSession {
    /**
     * Ask, and get the answer as it is written: each value is the *whole* answer so far, so a UI can
     * simply draw the latest one. Cancelling the collection stops the generation.
     */
    fun ask(question: String): Flow<String>

    /** Give the model back. A session left open holds its context — and its memory — until it is closed. */
    fun close()
}

/**
 * The browser's own language model, running on the user's machine — no key, no account, no request
 * leaving the computer. In Chrome this is Gemini Nano behind the Prompt API; where the browser has no
 * such thing, `builtInAi()` returns null and the search box simply never offers to ask it.
 *
 * This is why the AI lives at the platform layer next to [TabCapture] and [HistoryAccess]: it is a
 * capability of the host, present or absent, and the UI is built to work either way.
 */
interface AiAssistant {
    /** Which model this actually is, for the settings page to name — the user is entitled to know. */
    val name: String

    /** Whether the model is there, and whether using it will first download it. */
    suspend fun availability(): AiAvailability

    /**
     * Open a conversation. [systemPrompt] frames it once, for every question in the session.
     * [onDownloadProgress] reports 0.0–1.0 while the model is being fetched (first use only), so the
     * wait can be shown rather than merely endured.
     */
    suspend fun start(systemPrompt: String, onDownloadProgress: (Double) -> Unit = {}): AiSession
}
