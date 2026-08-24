package ceui.pixiv.shaftapi

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ShaftHmacInstrumentedTest {

    @Test
    fun nativeSignerLoadsSelfTestsAndSignsUtf8Deterministically() {
        // Instrumentation verification injects a disposable build key through the environment.
        // No key value is stored in test bytecode or source.
        assertTrue("native signer should load and pass its RFC 4231 self-test", ShaftHmac.isConfigured)

        val payload = "shaft|签名|\uD83D\uDD10"
        val first = ShaftHmac.signHex(payload)
        val second = ShaftHmac.signHex(payload)

        assertEquals(64, first.length)
        assertTrue(first.matches(Regex("[0-9a-f]{64}")))
        assertEquals(first, second)
    }
}
