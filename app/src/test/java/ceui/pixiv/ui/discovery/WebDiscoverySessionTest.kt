package ceui.pixiv.ui.discovery

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebDiscoverySessionTest {
    @Test fun `only the current app account can supply recommendation bookmark states`() {
        assertTrue(WebDiscoverySession.matchesAccount("PHPSESSID=123_session", 123))
        assertFalse(WebDiscoverySession.matchesAccount("PHPSESSID=456_session", 123))
        assertFalse(WebDiscoverySession.matchesAccount("PHPSESSID=123_session", 0))
    }

    @Test fun `missing anonymous and malformed sessions are rejected`() {
        for (cookie in listOf(null, "", "PHPSESSID=abcdef0123456789",
            "PHPSESSID=123_", "otherPHPSESSID=123_session", "PHPSESSID=999999999999999999999_session")) {
            assertFalse(cookie, WebDiscoverySession.matchesAccount(cookie, 123))
        }
    }

    @Test fun `duplicate cookies use the same logged in session as the request interceptor`() {
        assertTrue(WebDiscoverySession.matchesAccount(
            "PHPSESSID=anonymous; other=value; PHPSESSID=123_session", 123))
        assertTrue(WebDiscoverySession.matchesAccount(
            "PHPSESSID=123_session; PHPSESSID=anonymous", 123))
        assertFalse(WebDiscoverySession.matchesAccount(
            "PHPSESSID=123_old; PHPSESSID=456_new", 123))
    }
}
