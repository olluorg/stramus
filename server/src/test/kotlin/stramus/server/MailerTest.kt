package stramus.server

import com.icegreen.greenmail.util.GreenMail
import com.icegreen.greenmail.util.ServerSetup
import jakarta.mail.internet.MimeMultipart
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * The sign-in mail, sent through a real SMTP server running in this JVM.
 *
 * Mail is the one part of signing in that is not ours: a letter that is built wrongly, or sent to the wrong
 * address, or has the code only in a part no client renders, fails in a way no unit test of our own objects
 * would notice — the object would look perfect. So this sends it, receives it, and reads what arrived.
 */
class MailerTest {

    private lateinit var smtp: GreenMail

    @BeforeTest
    fun start() {
        smtp = GreenMail(ServerSetup(0, "127.0.0.1", "smtp")).also { it.start() }
    }

    @AfterTest
    fun stop() {
        smtp.stop()
    }

    @Test
    fun `the code arrives, in the subject and in both parts of the body`() = runTest {
        val mailer = SmtpMailer(configFor(smtp.smtp.port))

        mailer.sendLoginCode("ada@example.org", "483920")

        assertTrue(smtp.waitForIncomingEmail(5_000, 1), "no mail arrived")
        val received = smtp.receivedMessages.single()

        assertEquals("ada@example.org", received.allRecipients.single().toString())
        // In the subject as well as the body: a phone shows the subject in the notification, and the letter
        // then never has to be opened at all.
        assertTrue(received.subject.contains("483920"), "subject was: ${received.subject}")

        val parts = (received.content as MimeMultipart)
        val bodies = (0 until parts.count).map { parts.getBodyPart(it).content.toString() }
        assertEquals(2, bodies.size, "a letter should carry plain text as well as HTML")
        assertTrue(bodies.all { it.contains("483920") }, "every part must carry the code: $bodies")
        // The plain-text part is not a courtesy: a mail client that will not render HTML must still be able
        // to sign the user in.
        assertTrue(bodies.any { !it.contains("<html", ignoreCase = true) })
    }

    @Test
    fun `with no mail server configured, the codes go to the log on a developer's machine`() {
        assertTrue(mailerFor(ServerConfig()) is LoggingMailer)
        assertTrue(mailerFor(ServerConfig(smtpHost = "smtp.example.org")) is SmtpMailer)
    }

    @Test
    fun `with no mail server configured, production closes the mailed-code door instead of logging`() {
        assertTrue(mailerFor(production().copy(smtpHost = "", production = true)) is DisabledMailer)
    }

    @Test
    fun `a production server starts without a mail server — it just cannot mail a code`() = runTest {
        production().copy(smtpHost = "").requireProduction() // must not throw

        val failure = runCatching { DisabledMailer().sendLoginCode("ada@example.org", "123456") }.exceptionOrNull()
        assertTrue(
            failure is AccountException && failure.status == 501,
            "requesting a code should say the door is closed, not pretend to have sent one: $failure",
        )
    }

    @Test
    fun `a production server will not send mail in the clear`() {
        val failure = runCatching { production().copy(smtpRequireTls = false).requireProduction() }
            .exceptionOrNull()
        assertTrue(
            failure?.message?.contains("clear") == true,
            "an unencrypted relay should be refused, not merely noted: ${failure?.message}",
        )
        // And the same settings *with* TLS start perfectly well — the check is about encryption, not about
        // being generally suspicious.
        production().requireProduction()
    }

    private fun production() = ServerConfig(
        jwtSecret = "a-real-secret-that-is-long-enough-to-pass",
        allowedOrigins = listOf("https://stramus.example.org"),
        smtpHost = "smtp.example.org",
    )
}

private fun configFor(port: Int) = ServerConfig(
    smtpHost = "127.0.0.1",
    smtpPort = port,
    mailFrom = "stramus@example.org",
    // The relay is in this JVM: there is no network to encrypt, and GreenMail speaks no TLS. Anywhere else
    // this would be the setting that puts every sign-in code on the wire in the clear — and production will
    // not start with it (see the test below).
    smtpRequireTls = false,
)
