package stramus.server

import org.slf4j.LoggerFactory

/**
 * How a one-time code reaches the person who asked for it.
 *
 * An interface with one method, and no provider behind it yet: which mail service this ends up talking
 * to is a decision that can wait, and nothing above this line has to change when it is made. In
 * development the code goes to the log ([LoggingMailer]), which is all a developer signing in on their
 * own machine needs, and in a test it goes into a list ([RecordingMailer]) so the test can read it.
 */
fun interface Mailer {
    suspend fun sendLoginCode(email: String, code: String)
}

/** Prints the code. A server that does this in production is a server anyone can sign in to. */
class LoggingMailer : Mailer {
    private val log = LoggerFactory.getLogger(LoggingMailer::class.java)

    override suspend fun sendLoginCode(email: String, code: String) {
        log.info("Sign-in code for {}: {}", email, code)
    }
}

/** Keeps what it was asked to send, for tests to read back. */
class RecordingMailer : Mailer {
    private val sent = mutableListOf<Pair<String, String>>()

    override suspend fun sendLoginCode(email: String, code: String) {
        sent += email to code
    }

    /** The last code sent to [email], or null if none was. */
    fun lastCodeFor(email: String): String? = sent.lastOrNull { it.first == email }?.second
}
