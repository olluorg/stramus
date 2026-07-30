package stramus.server

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The guard on the icon endpoint.
 *
 * `/v1/favicon` is anonymous and makes this server fetch a host the caller names, which is the shape of
 * every server-side request forgery there has ever been. What keeps it honest is that a host has to look
 * like a public name *and* resolve to a public address before anything is fetched — so these are not
 * cosmetic input checks, and a change that loosens one of them is a change worth arguing about.
 */
class FaviconHostTest {

    @Test
    fun `ordinary hosts are allowed through`() {
        listOf("example.com", "news.ycombinator.com", "xn--80ak6aa92e.com", "a-b.co.uk").forEach {
            assertTrue(isWellFormedHost(it), "$it should be fetchable")
        }
    }

    @Test
    fun `names that resolve only inside the network are refused`() {
        // `localhost` and friends never leave the machine, and a single-label name is whatever the local
        // resolver decides it is — on a corporate network, quite possibly something interesting.
        listOf("localhost", "router", "metadata", "db.internal", "printer.local").forEach {
            assertFalse(isWellFormedHost(it), "$it should not be fetchable")
        }
    }

    @Test
    fun `bare addresses are not hosts`() {
        // Nobody saved a link to an IP address, and every interesting SSRF target is one — 169.254.169.254
        // above all, which on most clouds answers with credentials.
        listOf("127.0.0.1", "169.254.169.254", "10.0.0.1", "192.168.1.1", "0.0.0.0").forEach {
            assertFalse(isWellFormedHost(it), "$it should not be fetchable")
            assertFalse(isFetchableHost(it), "$it should not be fetchable")
        }
    }

    @Test
    fun `nothing smuggled past the host is accepted`() {
        // The host arrives as a query parameter and is pasted into a URL. Anything that could end the host
        // early — a port, credentials, a path, a second scheme — has to be refused rather than trimmed.
        listOf(
            "example.com:22",
            "user@example.com",
            "example.com/../admin",
            "example.com#x",
            "example.com?x=1",
            "https://example.com",
            "exa mple.com",
            "",
            ".",
            "-example.com",
            "example-.com",
        ).forEach {
            assertFalse(isWellFormedHost(it), "\"$it\" should not be fetchable")
        }
    }

    @Test
    fun `a name that resolves nowhere is refused rather than attempted`() {
        assertFalse(isFetchableHost("no-such-host-${System.nanoTime()}.invalid"))
    }
}

/** The rationing on cache misses — the only part of the endpoint that costs an outbound request. */
class MissBudgetTest {

    @Test
    fun `a caller gets its allowance and no more`() {
        val budget = MissBudget(perMinute = 3)
        repeat(3) { assertTrue(budget.take("198.51.100.7"), "the first three should be allowed") }
        assertFalse(budget.take("198.51.100.7"), "the fourth in the same minute should not be")
    }

    @Test
    fun `one caller's allowance is not another's`() {
        val budget = MissBudget(perMinute = 1)
        assertTrue(budget.take("198.51.100.7"))
        assertFalse(budget.take("198.51.100.7"))
        // One busy client must not be able to spend everybody else's budget for them.
        assertTrue(budget.take("203.0.113.9"))
    }
}
