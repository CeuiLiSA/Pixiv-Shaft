package ceui.lisa.helper

import android.app.Activity
import androidx.activity.OnBackPressedCallback
import ceui.lisa.activities.MainActivity
import ceui.lisa.activities.RankActivity
import ceui.lisa.activities.Shaft
import ceui.lisa.activities.TemplateActivity
import ceui.lisa.utils.Settings
import ceui.pixiv.muzei.MuzeiSettingsActivity
import com.blankj.utilcode.util.Utils
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

/**
 * 「预测性返回」逐页开关的行为契约。
 *
 * 整个方案压在两个设计论断上,这里把它们钉成断言:
 * 1) 默认(全部页面都没被关)时抑制回调必须是 disabled 的 —— PlazaNavigationTest 有 5 处
 *    assertFalse(hasEnabledCallbacks()),默认不能出现任何 enabled 回调;
 * 2) 页面级拦截(草稿保护/退出确认/选择态返回)后注册、优先级更高,必须仍然赢过这个全局回调。
 *
 * robolectric.properties 把 application 设成 android.app.Application,所以这里不跑
 * Shaft.onCreate,没有全局副作用;Shaft.sSettings 与挂载时机都由测试自己控制。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 35])
class PredictiveBackSuppressorTest {

    private val suppressor = PredictiveBackSuppressor()
    private var original: Settings? = null

    @Before
    fun setUp() {
        Utils.init(RuntimeEnvironment.getApplication())
        original = Shaft.sSettings
        // 干净默认值:被关掉的页面集合为空 = 全部维持预测返回
        Shaft.sSettings = Settings()
    }

    @After
    fun tearDown() {
        Shaft.sSettings = original
        // 注册表必须显式清空:WeakHashMap 的 value 反过来强引用 key(见类 KDoc),
        // 测试里漏掉一次 onActivityDestroyed 就会残留,让 installedCount() 变成
        // "看方法执行顺序"的脆弱断言。
        installedMap().clear()
    }

    private fun <T : Activity> install(activity: T): T {
        suppressor.onActivityPreCreated(activity, null)
        return activity
    }

    private fun installedMap(): MutableMap<Activity, OnBackPressedCallback> =
        ReflectionHelpers.getStaticField(PredictiveBackSuppressor::class.java, "INSTALLED")

    private fun installedCount(): Int = installedMap().size

    /** 把指定页面改成走传统返回,并像设置页那样立刻同步到已存在的 Activity。 */
    private fun setPredictiveBackDisabled(vararg classes: Class<*>) {
        val disabled = LinkedHashSet<String>()
        classes.forEach { disabled.add(it.name) }
        Shaft.sSettings.predictiveBackDisabledActivities = disabled
        PredictiveBackSuppressor.syncAll()
    }

    @Test
    fun `predictive back on by default leaves no enabled callback`() {
        val activity = install(Robolectric.buildActivity(TemplateActivity::class.java).get())

        assertFalse(
            "默认不能有任何 enabled 回调,否则全 app 的预测式返回会被掐死",
            activity.onBackPressedDispatcher.hasEnabledCallbacks(),
        )
        assertEquals("回调应已挂载(只是 disabled),开关切换才能即时生效", 1, installedCount())
        suppressor.onActivityDestroyed(activity)
    }

    @Test
    fun `disabling a page enables the suppressor and finishes it`() {
        val activity = install(Robolectric.buildActivity(TemplateActivity::class.java).get())

        setPredictiveBackDisabled(TemplateActivity::class.java)

        assertTrue(
            "开关关闭后必须有 enabled 回调,系统才会放弃预测动画",
            activity.onBackPressedDispatcher.hasEnabledCallbacks(),
        )
        activity.onBackPressedDispatcher.onBackPressed()
        assertTrue("没有页面级拦截时,应由全局回调结束当前页", activity.isFinishing)
        suppressor.onActivityDestroyed(activity)
    }

    @Test
    fun `re-enabling a page restores the predictive path`() {
        val activity = install(Robolectric.buildActivity(TemplateActivity::class.java).get())

        setPredictiveBackDisabled(TemplateActivity::class.java)
        assertTrue(activity.onBackPressedDispatcher.hasEnabledCallbacks())

        setPredictiveBackDisabled()
        assertFalse(
            "切回开启后必须立刻恢复预测返回,不必重建 Activity",
            activity.onBackPressedDispatcher.hasEnabledCallbacks(),
        )
        suppressor.onActivityDestroyed(activity)
    }

    @Test
    fun `disabling one page leaves the other pages on predictive back`() {
        val template = install(Robolectric.buildActivity(TemplateActivity::class.java).get())
        val main = install(Robolectric.buildActivity(MainActivity::class.java).get())

        setPredictiveBackDisabled(RankActivity::class.java)
        assertFalse(
            "只关了排行榜,通用页面必须继续走预测返回",
            template.onBackPressedDispatcher.hasEnabledCallbacks(),
        )
        assertFalse(main.onBackPressedDispatcher.hasEnabledCallbacks())

        setPredictiveBackDisabled(TemplateActivity::class.java)
        assertTrue(
            "现在关掉通用页面,它才该被接管",
            template.onBackPressedDispatcher.hasEnabledCallbacks(),
        )
        suppressor.onActivityDestroyed(template)
        suppressor.onActivityDestroyed(main)
    }

    @Test
    fun `page level interceptor still wins over the suppressor`() {
        val activity = install(Robolectric.buildActivity(TemplateActivity::class.java).get())

        setPredictiveBackDisabled(TemplateActivity::class.java)

        var pageHandled = false
        activity.onBackPressedDispatcher.addCallback(object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                pageHandled = true
            }
        })
        activity.onBackPressedDispatcher.onBackPressed()

        assertTrue("后注册的页面级拦截优先级更高,必须先拿到返回", pageHandled)
        assertFalse("页面级拦截处理过了就不该结束 Activity", activity.isFinishing)
        suppressor.onActivityDestroyed(activity)
    }

    @Test
    fun `installing twice does not register a second callback`() {
        val activity = install(Robolectric.buildActivity(TemplateActivity::class.java).get())
        suppressor.onActivityPreCreated(activity, null)
        assertEquals("同一 Activity 重复挂载必须幂等", 1, installedCount())
        suppressor.onActivityDestroyed(activity)
    }

    @Test
    fun `activities without the manifest opt-in are never touched`() {
        // MuzeiSettingsActivity 继承自 TemplateActivity 但没声明该属性 —— 白名单是 Class
        // 精确匹配而不是 instanceof,它就不该被牵连(把全部受控页都关掉也一样)。
        val activity = install(Robolectric.buildActivity(MuzeiSettingsActivity::class.java).get())

        setPredictiveBackDisabled(*PredictiveBackSuppressor.TARGET_ORDER.toTypedArray())

        assertFalse(
            "没声明 enableOnBackInvokedCallback 的 Activity 不该被牵连",
            activity.onBackPressedDispatcher.hasEnabledCallbacks(),
        )
        assertEquals(0, installedCount())
    }

    @Test
    fun `main activity is controllable because it declares the opt-in`() {
        // MainActivity 已补上 manifest 声明(抽屉那套自绘跟手动画需要 started/progressed),
        // 所以它和另外 5 个一样属于「系统预测返回」可控集。
        val activity = install(Robolectric.buildActivity(MainActivity::class.java).get())

        setPredictiveBackDisabled(MainActivity::class.java)

        assertTrue(
            "MainActivity 已显式声明,应可被逐页控制",
            activity.onBackPressedDispatcher.hasEnabledCallbacks(),
        )
        suppressor.onActivityDestroyed(activity)
    }

    @Test
    fun `destroying an activity drops it from the registry`() {
        val activity = install(Robolectric.buildActivity(TemplateActivity::class.java).get())
        assertEquals(1, installedCount())

        suppressor.onActivityDestroyed(activity)
        assertEquals(
            "必须显式移除:WeakHashMap 的 value 反过来强引用 key,不删就永久泄漏",
            0, installedCount(),
        )
    }
}