package ceui.pixiv.ui.common

import android.app.Application
import androidx.fragment.app.FragmentActivity
import ceui.pixiv.feeds.FeedViewModel
import ceui.pixiv.ui.novel.reader.export.ExportFormat
import ceui.pixiv.ui.novel.reader.ui.ExportFormatCallback
import ceui.pixiv.ui.novel.reader.ui.ExportSheet
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE, application = Application::class)
class NovelExportRoutingTest {
    // Same inheritance as NovelTextFragment: a detail export overrides the list callback.
    class DetailHost : NovelFeedFragment() {
        override val feedViewModel: FeedViewModel<String> get() = error("No feed needed")
        val detailExports = mutableListOf<ExportFormat>()
        override fun onExportFormatChosen(format: ExportFormat) {
            detailExports += format
        }
    }

    @Test fun `card response must not fall through to the detail novel`() {
        val controller = Robolectric.buildActivity(FragmentActivity::class.java).create()
        val host = DetailHost()
        controller.get().supportFragmentManager.beginTransaction().add(host, "detail").commitNow()
        // A restored sheet without a pending request must be ignored, never export the detail.
        (host as ExportFormatCallback).onExportFormatChosen(ExportFormat.Epub, "NovelCardExportSheet")
        assertEquals(emptyList<ExportFormat>(), host.detailExports)
        controller.destroy()
    }

    @Test fun `detail sheet still reaches the overridden detail export`() {
        val host = DetailHost()
        (host as ExportFormatCallback).onExportFormatChosen(ExportFormat.Txt, ExportSheet.TAG)
        assertEquals(listOf(ExportFormat.Txt), host.detailExports)
    }

    @Test fun `existing sheet hosts retain their original callback`() {
        val received = mutableListOf<ExportFormat>()
        val host = object : ExportFormatCallback {
            override fun onExportFormatChosen(format: ExportFormat) { received += format }
        }
        host.onExportFormatChosen(ExportFormat.Pdf, ExportSheet.TAG)
        assertEquals(listOf(ExportFormat.Pdf), received)
    }
}
