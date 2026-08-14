package ceui.pixiv.ui.search

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import ceui.lisa.viewmodel.SearchModel
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class SearchRiskFeedSourceTest {

    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    @Test
    fun `all result sources short circuit a blocked first page locally`() = runTest {
        val model = SearchModel().apply {
            keyword.value = text(0x4E60, 0x8FD1, 0x5E73)
        }

        val pages = listOf(
            SearchIllustFeedSource(model).load(null),
            SearchNovelFeedSource(model).load(null),
            SearchUserFeedSource(model).load(null),
        )

        assertTrue(pages.all { it.items.isEmpty() && it.nextCursor == null })
    }

    private fun text(vararg codePoints: Int): String = buildString {
        codePoints.forEach(::appendCodePoint)
    }
}
