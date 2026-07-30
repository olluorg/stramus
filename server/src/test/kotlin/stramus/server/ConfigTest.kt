package stramus.server

import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * Which verifier a [ServerConfig] hands out, for the two client ids it can be given independently of each
 * other. The verifiers' own correctness — checking a signature, asking Google's `tokeninfo` — is not
 * exercised here; see [GoogleAuthTest] for that, against a fake.
 */
class ConfigTest {

    @Test
    fun `neither client id means no Google door at all`() {
        assertNull(ServerConfig().googleVerifier())
    }

    @Test
    fun `only the web client id means only the ID token door`() {
        assertIs<GoogleIdTokenVerifier>(ServerConfig(googleClientId = "web-client").googleVerifier())
    }

    @Test
    fun `only the extension client id means only the access token door`() {
        assertIs<GoogleAccessTokenVerifier>(
            ServerConfig(googleExtensionClientId = "extension-client").googleVerifier(),
        )
    }

    @Test
    fun `both client ids means both doors, tried as one verifier`() {
        val verifier = ServerConfig(googleClientId = "web-client", googleExtensionClientId = "extension-client")
            .googleVerifier()
        assertIs<GoogleVerifier>(verifier)
        // Not a GoogleIdTokenVerifier or a GoogleAccessTokenVerifier on its own: a composite of both.
        kotlin.test.assertTrue(verifier !is GoogleIdTokenVerifier && verifier !is GoogleAccessTokenVerifier)
    }
}
