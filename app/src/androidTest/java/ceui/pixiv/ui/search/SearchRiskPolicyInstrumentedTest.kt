package ceui.pixiv.ui.search

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SearchRiskPolicyInstrumentedTest {

    @Test
    fun decryptsAndMatchesOnAndroidRuntime() {
        SearchRiskPolicy.warmUp()

        assertTrue(SearchRiskPolicy.isWarmedUp())
        assertTrue(
            SearchRiskPolicy.shouldWithhold(
                text(0x4E60, 0x8FD1, 0x5E73),
            ),
        )
    }

    private fun text(vararg codePoints: Int): String = buildString {
        codePoints.forEach(::appendCodePoint)
    }
}
