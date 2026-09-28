package ceui.lisa.utils

import com.blankj.utilcode.util.Utils
import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 「大图拖动退出」三个灵敏度阈值 +「放大大图后禁用拖动退出」开关的取值契约。
 *
 * 盯两件事：
 *  1. 默认值就是出厂手感，且必须与 `DragDismissLayout.DEFAULT_*` 对齐
 *     （另一侧的一致性断言在 `DragDismissLayoutSensitivityTest` 里）；
 *  2. Gson 反序列化老备份时新字段是 0 / false，getter 必须把越界值钳回默认，
 *     否则老用户一升级就被一个 0 阈值把页面拖坏。
 *
 * 走 Robolectric 而不是纯 JVM：`Settings` 的 static 字段要 blankj `PathUtils` 取外部路径，
 * 没有 Android 环境时类初始化直接抛 ExceptionInInitializerError。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "mdpi")
class ViewerDismissSettingsTest {

    @Before
    fun setUp() {
        Utils.init(RuntimeEnvironment.getApplication())
    }

    @Test
    fun `默认值即出厂手感`() {
        val settings = Settings()

        assertEquals(Settings.VIEWER_DISMISS_DISTANCE_DEFAULT, settings.viewerDismissDistance, 0f)
        assertEquals(Settings.VIEWER_DISMISS_VELOCITY_DEFAULT, settings.viewerDismissVelocity, 0f)
        assertEquals(Settings.VIEWER_DISMISS_SCALE_SHRINK_DEFAULT, settings.viewerDismissScaleShrink, 0f)
        assertFalse(settings.isViewerDismissOnlyAtMinScale)
    }

    @Test
    fun `合法值原样存取`() {
        val settings = Settings()
        settings.viewerDismissDistance = 0.30f
        settings.viewerDismissVelocity = 2500f
        settings.viewerDismissScaleShrink = 0.45f
        settings.isViewerDismissOnlyAtMinScale = true

        assertEquals(0.30f, settings.viewerDismissDistance, 0f)
        assertEquals(2500f, settings.viewerDismissVelocity, 0f)
        assertEquals(0.45f, settings.viewerDismissScaleShrink, 0f)
        assertTrue(settings.isViewerDismissOnlyAtMinScale)
    }

    @Test
    fun `上下限本身合法，不该被当成越界`() {
        val settings = Settings()

        settings.viewerDismissDistance = Settings.VIEWER_DISMISS_DISTANCE_MIN
        assertEquals(Settings.VIEWER_DISMISS_DISTANCE_MIN, settings.viewerDismissDistance, 0f)
        settings.viewerDismissDistance = Settings.VIEWER_DISMISS_DISTANCE_MAX
        assertEquals(Settings.VIEWER_DISMISS_DISTANCE_MAX, settings.viewerDismissDistance, 0f)

        settings.viewerDismissVelocity = Settings.VIEWER_DISMISS_VELOCITY_MIN
        assertEquals(Settings.VIEWER_DISMISS_VELOCITY_MIN, settings.viewerDismissVelocity, 0f)
        settings.viewerDismissVelocity = Settings.VIEWER_DISMISS_VELOCITY_MAX
        assertEquals(Settings.VIEWER_DISMISS_VELOCITY_MAX, settings.viewerDismissVelocity, 0f)

        settings.viewerDismissScaleShrink = Settings.VIEWER_DISMISS_SCALE_SHRINK_MIN
        assertEquals(Settings.VIEWER_DISMISS_SCALE_SHRINK_MIN, settings.viewerDismissScaleShrink, 0f)
        settings.viewerDismissScaleShrink = Settings.VIEWER_DISMISS_SCALE_SHRINK_MAX
        assertEquals(Settings.VIEWER_DISMISS_SCALE_SHRINK_MAX, settings.viewerDismissScaleShrink, 0f)
    }

    @Test
    fun `越界值回落默认，而不是夹到最近的边界`() {
        val settings = Settings()

        settings.viewerDismissDistance = Settings.VIEWER_DISMISS_DISTANCE_MIN - 0.01f
        assertEquals(Settings.VIEWER_DISMISS_DISTANCE_DEFAULT, settings.viewerDismissDistance, 0f)
        settings.viewerDismissDistance = Settings.VIEWER_DISMISS_DISTANCE_MAX + 0.01f
        assertEquals(Settings.VIEWER_DISMISS_DISTANCE_DEFAULT, settings.viewerDismissDistance, 0f)

        settings.viewerDismissVelocity = Settings.VIEWER_DISMISS_VELOCITY_MIN - 1f
        assertEquals(Settings.VIEWER_DISMISS_VELOCITY_DEFAULT, settings.viewerDismissVelocity, 0f)
        settings.viewerDismissVelocity = Settings.VIEWER_DISMISS_VELOCITY_MAX + 1f
        assertEquals(Settings.VIEWER_DISMISS_VELOCITY_DEFAULT, settings.viewerDismissVelocity, 0f)

        settings.viewerDismissScaleShrink = -0.01f
        assertEquals(Settings.VIEWER_DISMISS_SCALE_SHRINK_DEFAULT, settings.viewerDismissScaleShrink, 0f)
        settings.viewerDismissScaleShrink = Settings.VIEWER_DISMISS_SCALE_SHRINK_MAX + 0.01f
        assertEquals(Settings.VIEWER_DISMISS_SCALE_SHRINK_DEFAULT, settings.viewerDismissScaleShrink, 0f)
    }

    @Test
    fun `NaN 一律不落库`() {
        val settings = Settings()

        settings.viewerDismissDistance = Float.NaN
        assertEquals(Settings.VIEWER_DISMISS_DISTANCE_DEFAULT, settings.viewerDismissDistance, 0f)
        settings.viewerDismissVelocity = Float.NaN
        assertEquals(Settings.VIEWER_DISMISS_VELOCITY_DEFAULT, settings.viewerDismissVelocity, 0f)
        settings.viewerDismissScaleShrink = Float.NaN
        assertEquals(Settings.VIEWER_DISMISS_SCALE_SHRINK_DEFAULT, settings.viewerDismissScaleShrink, 0f)
    }

    /**
     * 老版本导出的备份里没有 `viewerDismiss*` 三个字段。Gson 用 Unsafe 建对象、不跑字段初始化器，
     * 反序列化后它们是 0——getter 必须把它钳回默认，否则用户一升级就被 0 阈值拖坏。
     */
    @Test
    fun `老备份反序列化后新字段为 0，getter 回落默认`() {
        val restored = Gson().fromJson("""{"themeIndex":1}""", Settings::class.java)

        assertEquals(Settings.VIEWER_DISMISS_DISTANCE_DEFAULT, restored.viewerDismissDistance, 0f)
        assertEquals(Settings.VIEWER_DISMISS_VELOCITY_DEFAULT, restored.viewerDismissVelocity, 0f)
        assertEquals(Settings.VIEWER_DISMISS_SCALE_SHRINK_DEFAULT, restored.viewerDismissScaleShrink, 0f)
        assertFalse(restored.isViewerDismissOnlyAtMinScale)
    }

    /** 调过的值要能原样往返：不能在一次「存 → 读」之后悄悄变回默认。 */
    @Test
    fun `调过的值经 Gson 往返不变`() {
        val settings = Settings()
        settings.viewerDismissDistance = 0.42f
        settings.viewerDismissVelocity = 777f
        settings.viewerDismissScaleShrink = 0.15f
        settings.isViewerDismissOnlyAtMinScale = true

        val restored = Gson().fromJson(Gson().toJson(settings), Settings::class.java)

        assertEquals(0.42f, restored.viewerDismissDistance, 0f)
        assertEquals(777f, restored.viewerDismissVelocity, 0f)
        assertEquals(0.15f, restored.viewerDismissScaleShrink, 0f)
        assertTrue(restored.isViewerDismissOnlyAtMinScale)
    }
}
