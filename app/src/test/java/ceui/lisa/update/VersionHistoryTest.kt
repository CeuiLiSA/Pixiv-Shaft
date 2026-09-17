package ceui.lisa.update

import android.app.Application
import android.view.View
import android.widget.TextView
import android.view.ViewGroup
import androidx.fragment.app.FragmentActivity
import ceui.lisa.R
import ceui.pixiv.witstudio.theme.dp
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 35], application = Application::class)
class VersionHistoryTest {

    private fun release(tag: String, body: String? = "note", size: Long = 45_000_000L) =
        GitHubRelease(
            tagName = tag,
            name = tag,
            body = body,
            publishedAt = "2026-09-07T03:00:00Z",
            htmlUrl = "https://example.invalid/$tag",
            assets = listOf(GitHubAsset("PixShaft_$tag.apk", size, "https://example.invalid/apk", null)),
        )

    @Test
    fun `summary offers the update and the list marks current and latest`() = runBlocking {
        val page = VersionHistorySource(
            currentVersion = "4.9.1",
            fromStore = false,
            fetch = { listOf(release("v4.9.3"), release("v4.9.2"), release("v4.9.1")) },
        ).load(null)

        // 只有一页：feeds 不该再往后翻。
        assertNull(page.nextCursor)
        val summary = page.items.first() as VersionSummaryItem
        assertEquals("4.9.1", summary.currentVersion)
        assertEquals("v4.9.3", summary.update?.tagName)

        val releases = page.items.drop(1).map { it as ReleaseItem }
        assertEquals(listOf("v4.9.3", "v4.9.2", "v4.9.1"), releases.map { it.release.tagName })
        assertEquals(listOf(true, false, false), releases.map { it.isLatest })
        assertEquals(listOf(false, false, true), releases.map { it.isCurrent })
    }

    @Test
    fun `no update when already on the newest build`() = runBlocking {
        val page = VersionHistorySource(
            currentVersion = "4.9.3",
            fromStore = false,
            fetch = { listOf(release("v4.9.3"), release("v4.9.2")) },
        ).load(null)
        assertNull((page.items.first() as VersionSummaryItem).update)
    }

    /** 商店渠道装的包不能用这里的 apk 覆盖安装，汇总卡因此不给更新动作。 */
    @Test
    fun `store channel never offers the github apk`() = runBlocking {
        val page = VersionHistorySource(
            currentVersion = "4.9.1",
            fromStore = true,
            fetch = { listOf(release("v4.9.3")) },
        ).load(null)
        assertNull((page.items.first() as VersionSummaryItem).update)
    }

    /** 空列表原样返回，空态归框架；不要在这里塞一张只有当前版本的汇总卡。 */
    @Test
    fun `an empty release list stays empty so the feed shows its empty state`() = runBlocking {
        val page = VersionHistorySource(currentVersion = "4.9.3", fromStore = false, fetch = { emptyList() })
            .load(null)
        assertTrue(page.items.isEmpty())
    }

    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Test
    fun `long notes collapse behind a visible toggle and short ones do not`() {
        val controller = Robolectric.buildActivity(FragmentActivity::class.java)
        val ctx = controller.get()
        ctx.setTheme(R.style.AppTheme)
        controller.setup()
        try {
            val markwon = markwonFor(ctx)
            val card = ReleaseCardView(ctx)
            val width = minOf(ctx.resources.displayMetrics.widthPixels, ctx.dp(720)) - ctx.dp(40)
            fun layout() {
                card.measure(
                    View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                )
                card.layout(0, 0, width, card.measuredHeight)
            }

            val long = release("v4.9.3", body = "新功能\n\n" + "- 一条足够长的更新说明条目\n".repeat(12))
            var toggled: ReleaseItem? = null
            card.bind(ReleaseItem(long, isCurrent = true, isLatest = true), markwon) { toggled = it }
            layout()
            // Markwon 给正文装了 MovementMethod，正文也会变成 clickable，只能按文案认按钮。
            val expandLabel = ctx.getString(R.string.version_history_expand)
            val collapseLabel = ctx.getString(R.string.version_history_collapse)
            val toggle = texts(card).single { it.text == expandLabel || it.text == collapseLabel }
            assertTrue("展开按钮要能看见", toggle.isVisible())
            // 折叠态下更新说明不许整篇铺开。
            val collapsedHeight = card.measuredHeight

            toggle.performClick()
            assertEquals("v4.9.3", toggled?.release?.tagName)

            card.bind(ReleaseItem(long, isCurrent = true, isLatest = true, expanded = true), markwon) {}
            layout()
            assertTrue("展开后应该更高", card.measuredHeight > collapsedHeight)
            assertTrue(toggle.isVisible())
            assertEquals(collapseLabel, toggle.text.toString())

            // 说明只有两行时不该出现一颗按了没反应的按钮。
            card.bind(ReleaseItem(release("v4.9.2", body = "只改了一处小问题"), false, false), markwon) {}
            layout()
            assertFalse(toggle.isVisible())
        } finally {
            controller.pause().stop().destroy()
        }
    }

    private fun View.isVisible() = visibility == View.VISIBLE

    private fun texts(view: View): List<TextView> =
        (if (view is TextView) listOf(view) else emptyList()) +
            if (view is ViewGroup) (0 until view.childCount).flatMap { texts(view.getChildAt(it)) }
            else emptyList()
}
