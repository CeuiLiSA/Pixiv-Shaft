package ceui.pixiv.websocket

import java.io.IOException
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExponentialBackoffWithJitterTest {

    /** Generic transient failure used by tests that only care about timing math. */
    private val transient: FailureContext =
        FailureContext.Failure(IOException("simulated network error"), httpCode = null)

    @Test
    fun `delays double until they hit the cap`() {
        val backoff = ExponentialBackoffWithJitter(
            initialDelayMillis = 1_000,
            maxDelayMillis = 30_000,
            multiplier = 2.0,
            jitterFactor = 0.0, // deterministic
        )
        // 1s, 2s, 4s, 8s, 16s, then capped at 30s forever
        assertEquals(1_000L, backoff.nextDelayMillis(1, transient))
        assertEquals(2_000L, backoff.nextDelayMillis(2, transient))
        assertEquals(4_000L, backoff.nextDelayMillis(3, transient))
        assertEquals(8_000L, backoff.nextDelayMillis(4, transient))
        assertEquals(16_000L, backoff.nextDelayMillis(5, transient))
        assertEquals(30_000L, backoff.nextDelayMillis(6, transient))
        assertEquals(30_000L, backoff.nextDelayMillis(7, transient))
        assertEquals(30_000L, backoff.nextDelayMillis(100, transient))
    }

    @Test
    fun `null returned past maxAttempts`() {
        val backoff = ExponentialBackoffWithJitter(
            maxAttempts = 3,
            jitterFactor = 0.0,
        )
        assertNotNull(backoff.nextDelayMillis(1, transient))
        assertNotNull(backoff.nextDelayMillis(2, transient))
        assertNotNull(backoff.nextDelayMillis(3, transient))
        assertNull(backoff.nextDelayMillis(4, transient))
        assertNull(backoff.nextDelayMillis(100, transient))
    }

    @Test
    fun `jitter stays within plusminus jitterFactor of capped delay`() {
        // Run many trials with a fixed seed and verify every observed jittered
        // delay is within ±20% of the deterministic 4s base for attempt 3.
        val seed = 12345L
        val backoff = ExponentialBackoffWithJitter(
            initialDelayMillis = 1_000,
            maxDelayMillis = 30_000,
            multiplier = 2.0,
            jitterFactor = 0.2,
            random = Random(seed),
        )
        val capped = 4_000L
        val tolerance = (capped * 0.2).toLong()
        repeat(1000) {
            val d = backoff.nextDelayMillis(3, transient) ?: error("unexpected null")
            assertTrue(
                "delay $d outside [${capped - tolerance}, ${capped + tolerance}]",
                d in (capped - tolerance)..(capped + tolerance)
            )
        }
    }

    @Test
    fun `jitter never produces a negative delay`() {
        // jitterFactor = 1.0 means jitter can subtract the entire capped value;
        // the implementation must clamp at zero.
        val backoff = ExponentialBackoffWithJitter(
            initialDelayMillis = 1_000,
            maxDelayMillis = 1_000,
            jitterFactor = 1.0,
            random = Random(99),
        )
        repeat(500) {
            val d = backoff.nextDelayMillis(1, transient) ?: error("unexpected null")
            assertTrue("delay $d was negative", d >= 0L)
        }
    }

    @Test
    fun `NoRetry strategy always returns null regardless of failure`() {
        assertNull(ReconnectStrategy.NoRetry.nextDelayMillis(1, transient))
        assertNull(ReconnectStrategy.NoRetry.nextDelayMillis(50, transient))
        // Even with a transient close code, NoRetry says no.
        val closed1011 = FailureContext.Closed(1011, "server error")
        assertNull(ReconnectStrategy.NoRetry.nextDelayMillis(1, closed1011))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `attempt below 1 throws`() {
        ExponentialBackoffWithJitter().nextDelayMillis(0, transient)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `invalid initial delay throws`() {
        ExponentialBackoffWithJitter(initialDelayMillis = 0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `max less than initial throws`() {
        ExponentialBackoffWithJitter(initialDelayMillis = 1_000, maxDelayMillis = 500)
    }

    // ── shouldReconnect predicate (P0-2 fix) ──────────────────────────────────

    @Test
    fun `default predicate gives up on fatal close codes`() {
        val backoff = ExponentialBackoffWithJitter(jitterFactor = 0.0)
        for (code in ExponentialBackoffWithJitter.FATAL_CLOSE_CODES) {
            val failure = FailureContext.Closed(code, "fatal $code")
            assertNull(
                "code $code must short-circuit",
                backoff.nextDelayMillis(1, failure),
            )
            // Even on the very first attempt — fatal is fatal regardless of count.
            assertNull(backoff.nextDelayMillis(100, failure))
        }
    }

    @Test
    fun `default predicate retries transient close codes`() {
        val backoff = ExponentialBackoffWithJitter(jitterFactor = 0.0)
        // 1000 normal, 1001 going away, 1011 server error, 1012/1013/1014 retry hints
        for (code in listOf(1000, 1001, 1011, 1012, 1013, 1014)) {
            val failure = FailureContext.Closed(code, "transient $code")
            assertNotNull(
                "code $code must retry",
                backoff.nextDelayMillis(1, failure),
            )
        }
    }

    @Test
    fun `default predicate retries app-defined 4xxx close codes`() {
        // 4000-4999 is the application-defined range; the strategy can't
        // know which are fatal, so the default treats them as transient.
        // Apps that need different behavior compose their own predicate.
        val backoff = ExponentialBackoffWithJitter(jitterFactor = 0.0)
        for (code in listOf(4000, 4001, 4500, 4999)) {
            val failure = FailureContext.Closed(code, "app code $code")
            assertNotNull(
                "code $code must retry by default",
                backoff.nextDelayMillis(1, failure),
            )
        }
    }

    @Test
    fun `default predicate gives up on fatal HTTP codes`() {
        val backoff = ExponentialBackoffWithJitter(jitterFactor = 0.0)
        for (code in ExponentialBackoffWithJitter.FATAL_HTTP_CODES) {
            val failure = FailureContext.Failure(IOException("upgrade $code"), httpCode = code)
            assertNull(
                "HTTP $code must short-circuit",
                backoff.nextDelayMillis(1, failure),
            )
        }
    }

    @Test
    fun `default predicate retries transient HTTP codes`() {
        val backoff = ExponentialBackoffWithJitter(jitterFactor = 0.0)
        // 429 (rate limit) and 5xx server errors should backoff-retry, not give up.
        for (code in listOf(429, 500, 502, 503, 504)) {
            val failure = FailureContext.Failure(IOException("upgrade $code"), httpCode = code)
            assertNotNull(
                "HTTP $code must retry",
                backoff.nextDelayMillis(1, failure),
            )
        }
    }

    @Test
    fun `default predicate retries pre-upgrade failures with no http code`() {
        // DNS resolution failure, TCP RST, TLS handshake failure — no HTTP
        // response was received, so httpCode is null. These are transient.
        val backoff = ExponentialBackoffWithJitter(jitterFactor = 0.0)
        val failure = FailureContext.Failure(IOException("DNS lookup failed"), httpCode = null)
        assertNotNull(backoff.nextDelayMillis(1, failure))
    }

    @Test
    fun `custom shouldReconnect predicate can add app-specific fatal codes`() {
        // Compose: treat app code 4001 ("kicked by admin") as fatal, but
        // delegate everything else to the default.
        val backoff = ExponentialBackoffWithJitter(
            jitterFactor = 0.0,
            shouldReconnect = { failure ->
                if (failure is FailureContext.Closed && failure.code == 4001) false
                else ExponentialBackoffWithJitter.DEFAULT_SHOULD_RECONNECT(failure)
            },
        )
        assertNull(backoff.nextDelayMillis(1, FailureContext.Closed(4001, "kicked")))
        // 4002 still retries (not in our custom fatal set, not in defaults).
        assertNotNull(backoff.nextDelayMillis(1, FailureContext.Closed(4002, "other")))
        // Default fatal codes still rejected.
        assertNull(backoff.nextDelayMillis(1, FailureContext.Closed(1008, "policy")))
    }

    @Test
    fun `custom shouldReconnect can opt out of all defaults and retry everything`() {
        // Useful for tests / demos that want the old "retry forever" behavior.
        val backoff = ExponentialBackoffWithJitter(
            jitterFactor = 0.0,
            shouldReconnect = { true },
        )
        assertNotNull(backoff.nextDelayMillis(1, FailureContext.Closed(1008, "policy")))
        assertNotNull(
            backoff.nextDelayMillis(
                1,
                FailureContext.Failure(IOException("auth failed"), httpCode = 401),
            ),
        )
    }
}
