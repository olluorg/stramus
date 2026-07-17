package stramus.server

import jakarta.mail.Authenticator
import jakarta.mail.Message
import jakarta.mail.PasswordAuthentication
import jakarta.mail.Session
import jakarta.mail.Transport
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeBodyPart
import jakarta.mail.internet.MimeMessage
import jakarta.mail.internet.MimeMultipart
import java.util.Properties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The one-time codes, by mail.
 *
 * SMTP rather than some provider's HTTP API, because every mail service in the world speaks SMTP: Postmark,
 * SES, Fastmail, a relay on the same machine. Which one is behind this is a decision about a hostname, not
 * about code, and it can be made — or changed — without touching this file.
 *
 * The letter is deliberately dull. It says who it is from, what the code is, how long it lasts, and what to
 * do if you did not ask for it — and it says nothing else at all. A sign-in mail that looks like marketing
 * is a sign-in mail that trains people to click things in mail that looks like marketing, and it is the
 * exact letter a phisher would imitate.
 */
class SmtpMailer(private val config: ServerConfig) : Mailer {

    private val session: Session = Session.getInstance(
        Properties().apply {
            put("mail.smtp.host", config.smtpHost)
            put("mail.smtp.port", config.smtpPort.toString())
            put("mail.smtp.auth", (config.smtpUser != null).toString())
            // STARTTLS on the submission port, implicit TLS on 465. Both are encrypted; what is not
            // acceptable is neither, and a password would otherwise cross the network in the clear. The
            // one exception is a test relay inside this JVM, where there is no wire to listen to.
            val starttls = config.smtpRequireTls && config.smtpPort != 465
            put("mail.smtp.starttls.enable", starttls.toString())
            put("mail.smtp.starttls.required", starttls.toString())
            if (config.smtpRequireTls && config.smtpPort == 465) {
                put("mail.smtp.ssl.enable", "true")
            }
            put("mail.smtp.connectiontimeout", "10000")
            put("mail.smtp.timeout", "10000")
            put("mail.smtp.writetimeout", "10000")
        },
        config.smtpUser?.let { user ->
            object : Authenticator() {
                override fun getPasswordAuthentication() =
                    PasswordAuthentication(user, config.smtpPassword.orEmpty())
            }
        },
    )

    override suspend fun sendLoginCode(email: String, code: String) {
        val minutes = config.loginCodeTtl.inWholeMinutes

        val message = MimeMessage(session).apply {
            setFrom(InternetAddress(config.mailFrom, "stramus"))
            setRecipients(Message.RecipientType.TO, InternetAddress.parse(email))
            subject = "$code — your stramus sign-in code"
            setContent(
                MimeMultipart("alternative").apply {
                    addBodyPart(MimeBodyPart().apply { setText(plainText(code, minutes), "utf-8") })
                    addBodyPart(MimeBodyPart().apply { setContent(html(code, minutes), "text/html; charset=utf-8") })
                },
            )
        }

        // Jakarta Mail blocks, and a mail server can take seconds to answer. Off the request thread with it:
        // a slow relay must not be a slow sign-in page for everybody else.
        withContext(Dispatchers.IO) { Transport.send(message) }
    }

    // The code is in the subject as well, so that a phone shows it in the notification and the letter itself
    // never has to be opened.
    private fun plainText(code: String, minutes: Long) = """
        Your stramus sign-in code is $code

        It is good for $minutes minutes, and for one sign-in.

        If you did not ask to sign in, you can ignore this letter — the code is useless to anyone who does
        not have it, and nothing has happened to your account.
    """.trimIndent()

    private fun html(code: String, minutes: Long) = """
        <!DOCTYPE html>
        <html lang="en">
        <body style="font: 16px/1.6 -apple-system, Segoe UI, Roboto, sans-serif; color: #1c2024; padding: 24px;">
            <p>Your stramus sign-in code is</p>
            <p style="font: 700 32px/1.2 ui-monospace, SFMono-Regular, Menlo, monospace; letter-spacing: 4px; margin: 16px 0;">
                $code
            </p>
            <p>It is good for $minutes minutes, and for one sign-in.</p>
            <p style="color: #6b7280;">
                If you did not ask to sign in, you can ignore this letter — the code is useless to anyone who
                does not have it, and nothing has happened to your account.
            </p>
        </body>
        </html>
    """.trimIndent()
}

/**
 * The mailer the configuration asks for: a real one when there is a mail server to talk to, and the log
 * when there is not.
 *
 * A development machine has no relay and should not need one — the code goes to the log, and the developer
 * signs in. A production server without `STRAMUS_SMTP_HOST` would do the same thing, which would print
 * every sign-in code of every user into the log and hand the account to whoever can read it; so it refuses
 * to start instead (see [ServerConfig.requireProduction]).
 */
fun mailerFor(config: ServerConfig): Mailer =
    if (config.smtpHost.isNotBlank()) SmtpMailer(config) else LoggingMailer()
