package ceui.pixiv.ui.novel.reader.export

import android.app.Application
import ceui.lisa.activities.Shaft
import ceui.lisa.utils.Settings
import ceui.pixiv.ui.novel.reader.paginate.ContentParser
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE, application = Application::class)
class NovelExportFormatTest {
    private var previous: Settings? = null

    @Before fun setUp() {
        previous = Shaft.sSettings
        Shaft.sSettings = Settings().apply { isDefaultNovelExportEpubOnImages = true }
    }

    @After fun tearDown() { Shaft.sSettings = previous }

    @Test fun `stale enabled switch cannot override another default or always ask fallback`() {
        val tokens = ContentParser.tokenize("[pixivimage:123]")
        for (name in listOf("", "Markdown", "Epub", "Pdf", "invalid")) {
            Shaft.sSettings.defaultNovelExportFormat = name
            assertFalse(name, NovelExportManager.shouldAutoEpubForDefaultTxt(ExportFormat.Txt, tokens))
        }
    }

    @Test fun `default TXT detects both supported illustration markers`() {
        Shaft.sSettings.defaultNovelExportFormat = "Txt"
        for (body in listOf("[pixivimage:123-2]", "[uploadedimage:456]")) {
            assertTrue(body, NovelExportManager.shouldAutoEpubForDefaultTxt(
                ExportFormat.Txt, ContentParser.tokenize(body),
            ))
        }
    }

    @Test fun `plain text disabled switch and non TXT selections keep their format`() {
        Shaft.sSettings.defaultNovelExportFormat = "Txt"
        assertFalse(NovelExportManager.shouldAutoEpubForDefaultTxt(ExportFormat.Txt, emptyList()))
        assertFalse(NovelExportManager.shouldAutoEpubForDefaultTxt(
            ExportFormat.Txt, ContentParser.tokenize("plain text"),
        ))
        val images = ContentParser.tokenize("[uploadedimage:456]")
        for (format in ExportFormat.entries.filter { it != ExportFormat.Txt }) {
            assertFalse(NovelExportManager.shouldAutoEpubForDefaultTxt(format, images))
        }
        Shaft.sSettings.isDefaultNovelExportEpubOnImages = false
        assertFalse(NovelExportManager.shouldAutoEpubForDefaultTxt(ExportFormat.Txt, images))
    }

    @Test fun `configured formats resolve while always ask and unknown values do not`() {
        for (format in ExportFormat.entries) {
            Shaft.sSettings.defaultNovelExportFormat = format.name
            assertEquals(format, NovelExportManager.resolveConfiguredFormat())
        }
        for (name in listOf("", " ", "unknown")) {
            Shaft.sSettings.defaultNovelExportFormat = name
            assertNull(NovelExportManager.resolveConfiguredFormat())
        }
    }
}
