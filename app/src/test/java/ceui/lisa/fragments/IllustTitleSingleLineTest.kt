package ceui.lisa.fragments

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatViewInflater
import androidx.appcompat.view.ContextThemeWrapper
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.widget.TextViewCompat
import ceui.lisa.R
import ceui.lisa.databinding.FragmentIllustBinding
import ceui.lisa.databinding.SectionV3HeroBinding
import ceui.pixiv.utils.singleLineTitle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 经典详情页标题（`fragment_illust.xml` 的 `@id/title`）单行化回归 —— issue #1200。
 *
 * 症状：标题自带换行时（官方作品 150482199 的标题 = `神子   \n\n八重神子，Yae Miko，原神`），
 * 该控件是 16dp 固定高的单行盒（`maxLines=1` + `autoSizeTextType=uniform(6sp..20sp)`）。
 * AppCompat autosize 的「装得下」判据要求整段文字排进 `maxLines` 行内
 * （`AppCompatTextViewAutoSizeHelper#suggestedSizeFitsInSpace`），含换行的标题在任何
 * 字号下都判为装不下 → 字号被钉死在 `autoSizeMinTextSize=6sp`，再被 `ellipsize="end"`
 * 补一个「…」。修复＝只在这个单行展示面折平标题（`FragmentIllust.setupTitle`）。
 *
 * 注意：这里必须用真的 [AppCompatTextView]。真机上 `<TextView>` 由 AppCompat 的 view
 * inflater 换掉、`app:autoSize*` 才生效；少了这层，autosize 根本不跑，测试会假绿。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class IllustTitleSingleLineTest {

    private val rawTitle = "神子   \n\n八重神子，Yae Miko，原神"

    private fun density(): Float =
        RuntimeEnvironment.getApplication().resources.displayMetrics.density

    private fun themedContext(): Context =
        ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.AppTheme)

    /** 装一层 AppCompat 的 view inflater，等价于真机上 AppCompatActivity 给的那个。 */
    private fun themedInflater(): LayoutInflater {
        val themed = themedContext()
        val inflater = LayoutInflater.from(themed).cloneInContext(themed)
        val appCompat = AppCompatViewInflater()
        inflater.factory2 = object : LayoutInflater.Factory2 {
            override fun onCreateView(
                parent: View?, name: String, context: Context, attrs: AttributeSet
            ): View? = appCompat.createView(parent, name, context, attrs, false, false, true, true)

            override fun onCreateView(
                name: String, context: Context, attrs: AttributeSet
            ): View? = appCompat.createView(null, name, context, attrs, false, false, true, true)
        }
        return inflater
    }

    /**
     * 把经典页标题挂进一个 411dp 宽的容器里量布，宽度/高度都交给布局（XML 里的
     * match_parent + 左右 margin + 16dp 高）自己算，避免在测试里抄一遍尺寸。
     */
    private fun renderClassicTitle(text: String): TextView {
        val tv = FragmentIllustBinding.inflate(themedInflater()).title
        // 布局里的 @id/title 本来挂在 bottom_bar 那个 LinearLayout 里，先摘下来才能换容器。
        (tv.parent as? ViewGroup)?.removeView(tv)
        val parent = FrameLayout(themedContext())
        parent.addView(tv)
        tv.text = text
        val d = density()
        val widthPx = (411 * d).toInt()
        val heightPx = (891 * d).toInt()
        // autosize 在 onLayout 里用 getHeight() 当可用高度：多量布两轮，贴近真实首帧。
        repeat(3) {
            parent.measure(
                View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(heightPx, View.MeasureSpec.EXACTLY)
            )
            parent.layout(0, 0, widthPx, heightPx)
        }
        return tv
    }

    @Test
    fun `经典页标题仍是 autosize 单行盒 布局规格一变这个测试就该重看`() {
        val tv = FragmentIllustBinding.inflate(themedInflater()).title
        assertTrue(
            "XML 里的 <TextView> 必须被换成 AppCompatTextView，否则 app:autoSize* 不生效",
            tv is AppCompatTextView
        )
        assertEquals("maxLines", 1, tv.maxLines)
        val d = density()
        assertEquals("标题盒高（@dimen/sixteen_dp）", 16f, tv.layoutParams.height / d, 0.01f)
        assertEquals(
            "autoSizeMinTextSize",
            6f, TextViewCompat.getAutoSizeMinTextSize(tv) / d, 0.01f
        )
        assertEquals(
            "autoSizeMaxTextSize",
            20f, TextViewCompat.getAutoSizeMaxTextSize(tv) / d, 0.01f
        )
        assertEquals(
            "autoSizeTextType",
            TextViewCompat.AUTO_SIZE_TEXT_TYPE_UNIFORM, TextViewCompat.getAutoSizeTextType(tv)
        )
    }

    @Test
    fun `换行标题原样塞进经典页会被钉死在 6sp 并截断 这就是 issue 1200`() {
        val tv = renderClassicTitle(rawTitle)
        assertEquals(
            "含换行的标题会被 autosize 钉死在 autoSizeMinTextSize，实际 ${tv.textSize / density()}sp",
            6f, tv.textSize / density(), 0.01f
        )
        // 不断言省略号：标题可框选（#1208）后 TextView 走 DynamicLayout，maxLines=1 不再补「…」，
        // 第二行直接被 16dp 盒裁掉——截断本身不变。
        assertTrue("后半段标题整段丢失", tv.layout.getLineEnd(0) < rawTitle.length)
    }

    @Test
    fun `单行化之后字号不再掉到 6sp 且整条标题都排得下`() {
        val flat = rawTitle.singleLineTitle()
        val tv = renderClassicTitle(flat)
        assertEquals("折平后的可用宽度应由布局 margin 决定", 339f, tv.width / density(), 1f)
        assertTrue(
            "折平后不该再被压到最小字号，实际 ${tv.textSize / density()}sp",
            tv.textSize / density() > 6f
        )
        assertEquals("折平后应整条排进第一行", flat.length, tv.layout.getLineEnd(0))
        assertEquals("折平后只有一行，没有被裁掉的第二行", 1, tv.layout.lineCount)
        assertTrue("内容不丢", tv.text.toString().contains("八重神子"))
    }

    /**
     * 决策锁：V3 详情页 hero 标题保持作者换行（与官 app 一致），不许跟着单行化。
     * 换行是 pixiv 标题里的合法内容，官 app 同样排成 3 行、中间空行。
     */
    @Test
    fun `V3 hero 标题保持作者换行`() {
        val hero = SectionV3HeroBinding.inflate(themedInflater()).heroTitle
        hero.text = rawTitle
        val d = density()
        val widthPx = ((411 - 20 - 20) * d).toInt()
        hero.measure(
            View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        hero.layout(0, 0, widthPx, hero.measuredHeight)
        val layout = hero.layout
        assertEquals("V3 hero 不做 maxLines 截断，\\n\\n 排成 3 行", 3, layout.lineCount)
        assertTrue(
            "第二行是空行——作者写的换行，官 app 同样如此，不是 bug",
            rawTitle.subSequence(layout.getLineStart(1), layout.getLineEnd(1)).isBlank()
        )
        assertEquals("V3 hero 字号不被 autosize 动", 23f, hero.textSize / d, 0.01f)
    }
}
