package ceui.pixiv.ui.comic.reader

import android.app.Application
import android.os.Parcelable
import android.util.SparseArray
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import androidx.appcompat.view.ContextThemeWrapper
import androidx.appcompat.widget.SwitchCompat
import ceui.lisa.R
import ceui.lisa.databinding.SheetComicReaderSettingsBinding
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * #1117：打开「看图自动横屏」触发旋转后，阅读设置面板里的开关被集体写成关，屏幕又转回竖屏。
 *
 * 面板里 8 行开关都是 `<include layout="@layout/item_reader_setting_switch">`，行内开关共用同一个
 * `@id/switch_control`。视图树的 saved state 是拍平进同一个 SparseArray、按 view id 存的，
 * 同 id 后写覆盖先写；恢复时每个开关又都按这个 id 去查，于是 8 个开关一起拿到最后保存的
 * 那一个值（面板最后一行 row_tap_reversed）。更糟的是 `CompoundButton.onRestoreInstanceState`
 * 走的是 `setChecked()`，而 `setChecked()` 会回调 `OnCheckedChangeListener`，面板的监听器直接
 * 把值写回 [ComicReaderSettings] —— 一次恢复就把所有布尔设置写成同一个值；`autoRotateImage`
 * 被写关之后 `updateImageOrientation()` 立刻 `restoreOrientation()`，画面又转回竖屏。
 *
 * 滑块行共用 `@id/seek_bar`，同理会把三个滑块的进度恢复成同一个值。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 35], application = Application::class)
class ComicReaderSettingsSheetStateTest {

    private fun inflatePanel(): View {
        val context = ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.AppTheme)
        return SheetComicReaderSettingsBinding.inflate(LayoutInflater.from(context)).root
    }

    /**
     * 按 id 收集整棵视图树里的控件。面板里 8 行开关共用 `@id/switch_control`、3 行滑块共用
     * `@id/seek_bar`，所以这里会拿到多个命中 —— 这正是本 bug 的前提；用 id 遍历比逐行取
     * 生成类属性更贴近被验证的事实。返回顺序即布局顺序（开关行、滑块行各自从上到下）。
     */
    private fun viewsById(root: View, id: Int): List<View> {
        val found = mutableListOf<View>()
        fun walk(v: View) {
            if (v.id == id) found += v
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) walk(v.getChildAt(i))
            }
        }
        walk(root)
        return found
    }

    private fun switchRows(panel: View): List<SwitchCompat> =
        viewsById(panel, R.id.switch_control).filterIsInstance<SwitchCompat>().also {
            assertTrue(
                "面板里应有 $SWITCH_ROWS 行共用 @id/switch_control 的开关，实际 ${it.size}",
                it.size == SWITCH_ROWS,
            )
        }

    private fun sliderRows(panel: View): List<SeekBar> =
        viewsById(panel, R.id.seek_bar).filterIsInstance<SeekBar>().also {
            assertTrue(
                "面板里应有 $SLIDER_ROWS 行共用 @id/seek_bar 的滑块，实际 ${it.size}",
                it.size == SLIDER_ROWS,
            )
        }

    private fun save(panel: View): SparseArray<Parcelable> =
        SparseArray<Parcelable>().also { panel.saveHierarchyState(it) }

    @Test
    fun `restoring the panel keeps every switch on its own value`() {
        val opened = inflatePanel()
        val values = SWITCH_VALUES
        switchRows(opened).forEachIndexed { i, s -> s.isChecked = values[i] }
        val saved = save(opened)

        val restored = inflatePanel()
        switchRows(restored).forEachIndexed { i, s -> s.isChecked = values[i] }
        restored.restoreHierarchyState(saved)

        val actual: List<Boolean> = switchRows(restored).map { it.isChecked }
        assertEquals(
            "共用 @id/switch_control 的开关行互相覆盖了 saved state",
            values,
            actual,
        )
    }

    @Test
    fun `restoring the panel keeps every slider on its own value`() {
        val opened = inflatePanel()
        val values = SLIDER_VALUES
        sliderRows(opened).forEachIndexed { i, s -> s.progress = values[i] }
        val saved = save(opened)

        val restored = inflatePanel()
        sliderRows(restored).forEachIndexed { i, s -> s.progress = values[i] }
        restored.restoreHierarchyState(saved)

        val actual: List<Int> = sliderRows(restored).map { it.progress }
        assertEquals(
            "共用 @id/seek_bar 的滑块行互相覆盖了 saved state",
            values,
            actual,
        )
    }

    @Test
    fun `restoring the panel never writes settings back`() {
        val opened = inflatePanel()
        val values = SWITCH_VALUES
        switchRows(opened).forEachIndexed { i, s -> s.isChecked = values[i] }
        val saved = save(opened)

        val restored = inflatePanel()
        val writes = mutableListOf<Pair<Int, Boolean>>()
        switchRows(restored).forEachIndexed { i, s ->
            s.isChecked = values[i]
            // 与 ComicReaderSettingsSheet.bindSwitch 一致：监听器把值写回设置存储。
            s.setOnCheckedChangeListener { _, v -> writes += i to v }
        }
        restored.restoreHierarchyState(saved)

        assertTrue("恢复视图状态不该回调设置监听器，实际回写了 $writes", writes.isEmpty())
    }

    private companion object {
        /** sheet_comic_reader_settings.xml 里 include item_reader_setting_switch / _slider 的行数。 */
        const val SWITCH_ROWS = 8
        const val SLIDER_ROWS = 3

        /** 隔行取不同值：既能暴露「互相覆盖」，也顺带验证收集顺序就是布局顺序。 */
        val SWITCH_VALUES: List<Boolean> = List(SWITCH_ROWS) { it % 2 == 0 }
        val SLIDER_VALUES: List<Int> = List(SLIDER_ROWS) { (it + 1) * 5 }
    }
}
