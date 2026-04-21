package ceui.pixiv.widgets

import android.content.Context
import android.content.Intent
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import ceui.lisa.activities.SearchActivity
import ceui.lisa.models.TagsBean
import ceui.lisa.utils.Params
import ceui.lisa.utils.V3Palette
import ceui.loxia.Tag
import ceui.pixiv.utils.ppppx
import com.google.android.flexbox.AlignItems
import com.google.android.flexbox.FlexWrap
import com.google.android.flexbox.FlexboxLayout

/**
 * V3 风格标签流 — 胶囊形背景 + `# name  译名` 格式 + 点击跳 SearchActivity。
 * 自带 signature dedupe，同一组 tags 多次 setTags 不会重建 view。
 *
 * 两个 API 分别接 loxia [Tag]（小说）和 lisa [TagsBean]（插画）。
 */
class V3TagFlowView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FlexboxLayout(context, attrs, defStyleAttr) {

    private val palette by lazy { V3Palette.from(context) }
    private var lastSignature: String? = null

    /** Which SearchActivity tab to land on — 0 = illust, 1 = novel. */
    var searchIndex: Int = 0

    init {
        alignItems = AlignItems.FLEX_START
        flexWrap = FlexWrap.WRAP
    }

    fun setTags(tags: List<Tag>) {
        renderPairs(tags.map { (it.name ?: "") to it.translated_name })
    }

    fun setJavaTags(tags: List<TagsBean>) {
        renderPairs(tags.map { (it.name ?: "") to it.translated_name })
    }

    private fun renderPairs(pairs: List<Pair<String, String?>>) {
        val sig = buildString {
            pairs.forEach { (n, t) ->
                append(n); append('|'); append(t ?: ""); append(';')
            }
        }
        if (sig == lastSignature && childCount > 0) return
        lastSignature = sig

        removeAllViews()
        val density = context.resources.displayMetrics.density
        val tagBgState = palette.tagLockedBg(999f * density).constantState

        pairs.forEach { (name, translated) ->
            val tv = TextView(context).apply {
                text = buildString {
                    append("# "); append(name)
                    if (!translated.isNullOrBlank()) {
                        append("  "); append(translated)
                    }
                }
                textSize = 13f
                setTextColor(palette.textTag)
                background = tagBgState?.newDrawable()?.mutate()
                setPadding(14.ppppx, 7.ppppx, 14.ppppx, 7.ppppx)
                layoutParams = LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { setMargins(0, 0, 8.ppppx, 8.ppppx) }
                setOnClickListener {
                    val intent = Intent(context, SearchActivity::class.java).apply {
                        putExtra(Params.KEY_WORD, name)
                        putExtra(Params.INDEX, searchIndex)
                    }
                    context.startActivity(intent)
                }
            }
            applyTouchScale(tv, 0.94f)
            addView(tv)
        }
    }

    private fun applyTouchScale(view: View, scale: Float) {
        view.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN ->
                    v.animate().scaleX(scale).scaleY(scale).setDuration(200).start()

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    v.animate().scaleX(1f).scaleY(1f).setDuration(200).start()
            }
            false
        }
    }
}
