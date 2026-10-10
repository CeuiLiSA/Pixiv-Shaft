package ceui.pixiv.ui.novel.reader.settings

import android.app.Application
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.lifecycle.LiveData
import ceui.lisa.activities.Shaft
import ceui.pixiv.ui.novel.reader.settings.ReaderParagraphSpacingMigrationTest.MemoryMMKV
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

/**
 * 阅读设置随备份还原、字体文件不随（#1060）：选中的下载字体装好后，翻页模式靠 Snapshot 判重，
 * Snapshot 必须变，否则正文一直停在回退的系统字体上。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class,
    shadows = [MemoryMMKV::class], instrumentedPackages = ["com.tencent.mmkv"])
class ReaderFontInstalledSnapshotTest {
    @get:Rule val executor = InstantTaskExecutorRule()

    @Before fun setUp() {
        MemoryMMKV.values.clear()
        // ReaderSettings survives Robolectric sandbox reuse; do not inherit another test's pending post.
        ReflectionHelpers.setField(ReaderSettings.changes, "mPendingData",
            ReflectionHelpers.getStaticField<Any>(LiveData::class.java, "NOT_SET"))
        ReflectionHelpers.setStaticField(Shaft::class.java, "sContext", RuntimeEnvironment.getApplication())
    }

    @Test fun `installing the selected font changes the snapshot`() {
        ReaderSettings.fontId = ReaderWebFont.NOTO_SERIF_SC.id
        val before = ReaderSettings.snapshot()
        ReaderSettings.onFontInstalled(ReaderWebFont.NOTO_SERIF_SC.id)
        assertNotEquals(before, ReaderSettings.snapshot())
    }

    @Test fun `installing another font leaves the snapshot alone`() {
        ReaderSettings.fontId = ReaderWebFont.NOTO_SERIF_SC.id
        val before = ReaderSettings.snapshot()
        ReaderSettings.onFontInstalled(ReaderWebFont.KLEE_ONE.id)
        assertEquals(before, ReaderSettings.snapshot())
    }
}
