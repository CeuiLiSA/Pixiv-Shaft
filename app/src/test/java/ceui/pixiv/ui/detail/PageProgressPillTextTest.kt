package ceui.pixiv.ui.detail

import android.os.Bundle
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.lisa.databinding.FragmentArtworkV3Binding
import ceui.lisa.fragments.FragmentIllust
import ceui.lisa.utils.Settings
import com.blankj.utilcode.util.Utils
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

/**
 * 页码读数必须写在**布局之外**。
 *
 * 写 text 会 requestLayout；若这次写入落在布局过程中（列表排版回调的 OnGlobalLayoutListener，
 * 或待定滚动在 RecyclerView.onLayout() 里消费时派发的 onScrolled），框架会把它并进第二趟布局、
 * 或直接吞掉，浮标的已测量宽高就停在**上一段文字**的尺寸上；而它是 wrap_content、没有
 * singleLine / maxLines，新文字装不下就会折到第二行，第二行落在浮标盒子之外被裁 —— 表现成
 * 「2 /」、分母不见了，而且要等下一次真正的排版才复原（有时一直不复原）。
 *
 * 这里钉住修好的契约：上屏的浮标一律推迟到布局之外再写（setText 的 requestLayout 会被正常
 * 受理，浮标按新文字重新量一次、自己长大）；没上屏的没有排版在跑，当场写。
 *
 * V3 还要多一条：**读数段的显隐也一起推迟**。胶囊行是 wrap_content，「收起」先被点亮、行按
 * 「只有收起」量过之后读数段才第一次露出来（进页即展开就是这情形），若这次 GONE→VISIBLE 的
 * requestLayout 落在布局里被吞，读数段就永远停在 0 宽 —— 屏幕上什么都看不到，且文字没再变就
 * 再也不会触发排版。V2 的浮标是独立一枚，没有这个组合，仍是当场写。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 35], application = PageProgressPillTextTest.TestApplication::class)
class PageProgressPillTextTest {

    class TestApplication : Shaft() {
        override fun onCreate() = Unit
    }

    private lateinit var host: FragmentActivity

    @Before
    fun setUp() {
        val app = RuntimeEnvironment.getApplication()
        Utils.init(app)
        Shaft.sSettings = Settings()
        ReflectionHelpers.setStaticField(Shaft::class.java, "sContext", app)
        host = Robolectric.buildActivity(FragmentActivity::class.java).setup().get()
        host.setTheme(R.style.AppTheme)
    }

    @After
    fun tearDown() {
        host.finish()
    }

    @Test
    fun `attached v3 pill defers the reading until the next frame`() {
        val chrome = FragmentArtworkV3Binding.inflate(LayoutInflater.from(host))
        host.setContentView(chrome.root)
        shadowOf(Looper.getMainLooper()).idle()
        val pill = chrome.pageProgressPill
        assumeTrue("Robolectric 没把内容挂上窗口", pill.isAttachedToWindow)
        // 布局里默认 gone；这里验的是「已经露出来的浮标换读数」
        pill.isVisible = true
        shadowOf(Looper.getMainLooper()).idle()
        val writes = countWrites(pill)

        apply(ArtworkV3Fragment().apply { arguments = Bundle() }, pill, "3 / 3")

        // 当场写 = requestLayout-during-layout，盒子会停在旧尺寸上：必须推迟。
        assertEquals("上屏的浮标不能在布局里当场写", 0, writes.value)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, writes.value)
        assertEquals("3 / 3", pill.text?.toString())
    }

    @Test
    fun `attached classic pill defers the reading until the next frame`() {
        // 经典详情页那份布局在 Robolectric 的 sdk 24 下装不起来（fragment_illust.xml 里挂着
        // GLSurfaceView 的子类，android-all 缺 javax.microedition.khronos.opengles.GL10），而这里
        // 要验的是 FragmentIllust 那份 applyPageProgressText 的契约 —— 给一枚真上屏的普通
        // TextView 就够，与浮标来自哪份布局无关。
        val pill = attachedPill()
        assumeTrue("Robolectric 没把内容挂上窗口", pill.isAttachedToWindow)
        val writes = countWrites(pill)

        applyText(FragmentIllust(), pill, "3 / 3")

        assertEquals("上屏的浮标不能在布局里当场写", 0, writes.value)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, writes.value)
        assertEquals("3 / 3", pill.text?.toString())
    }

    /**
     * 已上屏的读数段：**文字与显隐一起**推迟到下一帧。
     *
     * 只推迟文字不够（进页即展开时读数永远不出现的那条）：胶囊行是 wrap_content，读数段由
     * GONE 变 VISIBLE 时若这次 requestLayout 落在布局里被吞，这一段就停在 0 宽 —— 屏幕上什么都
     * 没有，而且文字没再变就再也不会触发排版，滑多远都不复原。两个字段一起改、一起推迟，
     * 读数段露出来时才会带着新文字被重新量一次。
     */
    @Test
    fun `attached hidden reading defers text and visibility together`() {
        val chrome = FragmentArtworkV3Binding.inflate(LayoutInflater.from(host))
        host.setContentView(chrome.root)
        shadowOf(Looper.getMainLooper()).idle()
        val v3Pill = chrome.pageProgressPill
        assumeTrue("Robolectric 没把内容挂上窗口", v3Pill.isAttachedToWindow)
        assertFalse(v3Pill.isVisible)
        val writes = countWrites(v3Pill)

        apply(ArtworkV3Fragment().apply { arguments = Bundle() }, v3Pill, "3 / 3")

        assertFalse("还隐藏着的读数段也不能在布局里当场露出来", v3Pill.isVisible)
        assertEquals(0, writes.value)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, writes.value)
        assertEquals("3 / 3", v3Pill.text?.toString())
        assertTrue("下一帧才把读数段露出来", v3Pill.isVisible)
    }

    /**
     * 经典详情页的浮标是独立一枚、没有「收起」同行预热过，仍是当场写 —— 那条契约这次没动，
     * 在这里钉住，免得跟着 V3 一起被改掉。
     */
    @Test
    fun `classic hidden pill is still written straight away`() {
        val classicPill = attachedPill().apply { isVisible = false }
        applyText(FragmentIllust(), classicPill, "3 / 3")
        assertEquals("3 / 3", classicPill.text?.toString())
    }

    @Test
    fun `pill that never reached the screen is written straight away`() {
        val chrome = FragmentArtworkV3Binding.inflate(LayoutInflater.from(host))
        val pill = chrome.pageProgressPill
        assertFalse(pill.isAttachedToWindow)
        val writes = countWrites(pill)

        apply(ArtworkV3Fragment().apply { arguments = Bundle() }, pill, "3 / 3")

        assertEquals(1, writes.value)
        assertEquals("3 / 3", pill.text?.toString())
    }

    @Test
    fun `unchanged reading is not written again`() {
        val chrome = FragmentArtworkV3Binding.inflate(LayoutInflater.from(host))
        val pill = chrome.pageProgressPill
        pill.text = "3 / 3"
        val writes = countWrites(pill)

        apply(ArtworkV3Fragment().apply { arguments = Bundle() }, pill, "3 / 3")
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(0, writes.value)
    }

    /** 一枚真挂上窗口的普通 TextView：验的是「写 text 的时机」，与浮标来自哪份布局无关。 */
    private fun attachedPill(): TextView {
        val root = FrameLayout(host)
        val pill = TextView(host)
        root.addView(pill)
        host.setContentView(root)
        shadowOf(Looper.getMainLooper()).idle()
        return pill
    }

    /** V3：文字与显隐一起写（null = 收起读数段）。 */
    private fun apply(fragment: Fragment, pill: TextView, text: String?) =
        invoke(fragment, "applyPageProgressPill", pill, text)

    /** V2：只有文字，契约未变。 */
    private fun applyText(fragment: Fragment, pill: TextView, text: String) =
        invoke(fragment, "applyPageProgressText", pill, text)

    private fun invoke(fragment: Fragment, method: String, pill: TextView, text: String?) {
        ReflectionHelpers.callInstanceMethod<Void>(
            fragment,
            method,
            ReflectionHelpers.ClassParameter.from(TextView::class.java, pill),
            ReflectionHelpers.ClassParameter.from(String::class.java, text),
        )
    }

    private class Writes(var value: Int = 0)

    private fun countWrites(pill: TextView): Writes {
        val counter = Writes()
        pill.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                counter.value++
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        return counter
    }
}