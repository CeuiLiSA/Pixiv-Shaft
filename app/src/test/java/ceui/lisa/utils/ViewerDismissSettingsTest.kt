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
 *  2. 旧备份缺 key 时读出默认值；磁盘上的越界值（手改备份、以后收窄可调范围）
 *     由 getter 回落默认，而不是夹到边界。
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
     * 老版本导出的备份里没有 `viewerDismiss*` 字段。`Settings` 有无参构造，Gson 会调用它，
     * 字段初始化器照跑，缺的 key 就是默认值。这条同时钉住「无参构造不能删」：删了 Gson 改走
     * Unsafe，缩放反馈会变成 0——而 0 在合法范围内，getter 兜不住，老用户会丢掉缩小反馈。
     */
    @Test
    fun `老备份缺 key 时读出默认值`() {
        val restored = Gson().fromJson("""{"themeIndex":1}""", Settings::class.java)

        assertEquals(Settings.VIEWER_DISMISS_DISTANCE_DEFAULT, restored.viewerDismissDistance, 0f)
        assertEquals(Settings.VIEWER_DISMISS_VELOCITY_DEFAULT, restored.viewerDismissVelocity, 0f)
        assertEquals(Settings.VIEWER_DISMISS_SCALE_SHRINK_DEFAULT, restored.viewerDismissScaleShrink, 0f)
        assertFalse(restored.isViewerDismissOnlyAtMinScale)
    }

    /** 磁盘上的越界值（手改备份 / 以后收窄范围后的残留）读出来回落默认，不夹到边界。 */
    @Test
    fun `磁盘上的越界值读出来回落默认`() {
        val restored = Gson().fromJson(
            """{"viewerDismissDistance":0.9,"viewerDismissVelocity":50,"viewerDismissScaleShrink":-1}""",
            Settings::class.java,
        )

        assertEquals(Settings.VIEWER_DISMISS_DISTANCE_DEFAULT, restored.viewerDismissDistance, 0f)
        assertEquals(Settings.VIEWER_DISMISS_VELOCITY_DEFAULT, restored.viewerDismissVelocity, 0f)
        assertEquals(Settings.VIEWER_DISMISS_SCALE_SHRINK_DEFAULT, restored.viewerDismissScaleShrink, 0f)
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
