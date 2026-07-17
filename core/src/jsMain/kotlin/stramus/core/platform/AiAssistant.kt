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

    /**
     * Ask, and get one answer that is JSON of the shape [schema] describes (a JSON Schema, as text).
     *
     * The difference from [ask] is not the streaming, it is the *constraint*: the browser holds the
     * model to the shape while it writes, so what comes back parses or the call throws. A small model
     * asked in prose for a structure answers with a preamble, a code fence and an apology around it,
     * none of which can be told from the answer by looking at it — so nothing that has to be acted on
     * is asked for in prose. What the shape does not constrain is the content, which is the caller's
     * to check: see `planFrom` in [stramus.core.ai].
     *
     * Not streamed, because half of a JSON document is not JSON: there is nothing to draw until it
     * is finished.
     */
    suspend fun askJson(question: String, schema: String): String

    /**
     * A second session framed exactly as this one was — the same system prompt — but with none of what
     * has been said in it.
     *
     * This is how a great many independent questions are asked of one model without the answers piling
     * up: a session remembers, and remembering is what runs it out of context. Asking each question in
     * a clone of a common session costs a fraction of what opening a session from scratch does, and the
     * context each question sees is the same size as the first one's — which is what makes the number
     * of questions a matter of time rather than of a limit. See `triage` in [stramus.core.ai].
     *
     * The clone is the caller's to [close].
     */
    suspend fun clone(): AiSession

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
