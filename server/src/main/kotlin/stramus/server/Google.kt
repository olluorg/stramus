package stramus.server

import com.auth0.jwk.JwkProviderBuilder
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.interfaces.RSAPublicKey
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private val ISSUERS = listOf("https://accounts.google.com", "accounts.google.com")

/** Who Google says the caller is. [subject] is Google's own id for them, and it never changes. */
data class GoogleIdentity(
    val subject: String,
    val email: String,
    val emailVerified: Boolean,
)

/**
 * Checks an ID token from Google.
 *
 * An interface, not a function, for one reason: the real one goes to the internet for Google's public keys,
 * and a test that cannot run without Google is a test that does not run. The account logic behind this —
 * which is where the interesting mistakes live — is exercised against a fake.
 */
fun interface GoogleVerifier {
    /** The identity in [idToken], or null if it is not a token this server should believe. */
    suspend fun verify(idToken: String): GoogleIdentity?
}

/**
 * The real one: Google's signature, Google's issuer, our audience, unexpired.
 *
 * All four matter, and the third is the one that is easy to leave out and fatal to leave out. Google will
 * happily sign an ID token for *any* application — including one an attacker registered five minutes ago —
 * and such a token is perfectly valid, perfectly signed, and says whatever email its holder gave Google. The
 * only thing that makes a token *ours* is that its `aud` names our client id. Without that check, anyone
 * with a Google account and their own app can sign in as anyone.
 */
class GoogleIdTokenVerifier(private val clientId: String) : GoogleVerifier {

    private val jwks = JwkProviderBuilder(URI("https://www.googleapis.com/oauth2/v3/certs").toURL())
        // Google rotates these keys; cached, they cost nothing, and rate-limiting keeps a burst of sign-ins
        // from turning into a burst of requests to Google.
        .cached(10, 24, TimeUnit.HOURS)
        .rateLimited(10, 1, TimeUnit.MINUTES)
        .build()

    override suspend fun verify(idToken: String): GoogleIdentity? = withContext(Dispatchers.IO) {
        runCatching {
            val decoded = JWT.decode(idToken)
            val key = jwks.get(decoded.keyId).publicKey as RSAPublicKey

            // Google issues under both spellings of its issuer, and has for years. A server that knows only
            // one of them rejects perfectly good tokens for a reason nobody can guess from the outside.
            if (decoded.issuer !in ISSUERS) return@runCatching null

            val verified = JWT.require(Algorithm.RSA256(key, null))
                .withIssuer(*ISSUERS.toTypedArray())
                .withAudience(clientId)
                .build()
                .verify(idToken)

            val email = verified.getClaim("email").asString() ?: return@runCatching null
            GoogleIdentity(
                subject = verified.subject,
                email = email,
                emailVerified = verified.getClaim("email_verified").asBoolean() ?: false,
            )
        }.getOrNull()
    }
}

/**
 * Checks an *access* token from Google — what `chrome.identity.getAuthToken` hands back, as opposed to the
 * signed ID token [GoogleIdTokenVerifier] checks. It is opaque: nothing here can be verified against a
 * local key the way a JWT's signature can, so the only way to learn what the token is good for is to ask
 * Google directly, which `tokeninfo` answers for exactly this reason.
 *
 * The `aud` it returns matters for the same reason the ID token's `aud` claim does: Google will hand out a
 * perfectly valid access token to any application a user has granted, including one that is not this one.
 * Without checking it names *our* extension's client id, this would believe an access token minted for
 * somebody else's app just as readily as one minted for ours.
 */
class GoogleAccessTokenVerifier(private val clientId: String) : GoogleVerifier {

    private val http = HttpClient.newHttpClient()

    override suspend fun verify(idToken: String): GoogleIdentity? = withContext(Dispatchers.IO) {
        runCatching {
            val request = HttpRequest.newBuilder(
                URI("https://oauth2.googleapis.com/tokeninfo?access_token=${URLEncoder.encode(idToken, "UTF-8")}"),
            ).GET().build()
            val response = http.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() != 200) return@runCatching null

            val body = Json.parseToJsonElement(response.body()).jsonObject
            fun field(name: String) = body[name]?.jsonPrimitive?.content

            if (field("aud") != clientId) return@runCatching null
            val email = field("email") ?: return@runCatching null
            val subject = field("sub") ?: field("user_id") ?: return@runCatching null

            GoogleIdentity(
                subject = subject,
                email = email,
                emailVerified = (field("email_verified") ?: field("verified_email"))?.toBoolean() ?: false,
            )
        }.getOrNull()
    }
}
