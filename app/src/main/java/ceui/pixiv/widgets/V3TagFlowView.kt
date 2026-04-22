package ceui.pixiv.widgets

import android.content.Context
import android.content.Intent
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.content.res.AppCompatResources
import ceui.lisa.R
import ceui.lisa.activities.SearchActivity
import ceui.lisa.models.TagsBean
import ceui.lisa.utils.Params
import ceui.lisa.utils.V3Palette
import ceui.loxia.Tag
import ceui.pixiv.utils.ppppx
import com.google.android.flexbox.AlignItems
import com.google.android.flexbox.FlexboxLayout

/**
 * V3 风格标签流 — 胶囊形背景 + `# name  译名` 格式 + 点击跳 SearchActivity。
 * 自带 signature dedupe，同一组 tags 多次 setTags 不会重建 view。
 *
 * 三个 API 按数据源选：
 * - [setTags]：loxia 的 [Tag]（小说）
 * - [setJavaTags]：lisa 的 [TagsBean]（插画）
 * - [setTagNames]：纯字符串列表（搜索页输入框的 chip 输入）
 *
 * flexWrap 走 XML 原生属性：`app:flexWrap="wrap"` 或 `"nowrap"`。
 */
class V3TagFlowView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FlexboxLayout(context, attrs, defStyleAttr) {

    private val palette by lazy { V3Palette.from(context) }
    private var lastSignature: String? = null
    private var lastPairs: List<Pair<String, String?>> = emptyList()

    /** Which SearchActivity tab to land on — 0 = illust, 1 = novel. */
    var searchIndex: Int = 0

    /**
     * When non-null, chip taps invoke this instead of opening SearchActivity.
     * Useful for e.g. tag-input in SearchActivity itself (tap = remove chip).
     */
    var onTagClick: ((name: String) -> Unit)? = null

    /** Show a trailing × icon on each chip — for editable/removable chip rows. */
    var showRemoveIcon: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                // Force re-render with the last known tag list so flipping this
                // property after a setTags*() call reflects immediately.
                lastSignature = null
                if (lastPairs.isNotEmpty()) renderPairs(lastPairs)
            }
        }

    init {
        alignItems = AlignItems.FLEX_START
        // flexWrap 由 XML / caller 决定；不在 init 里强塞，避免 `app:flexWrap="nowrap"` 被覆盖。
    }

    fun setTags(tags: List<Tag>) {
        renderPairs(tags.map { (it.name ?: "") to it.translated_name })
    }

    fun setJavaTags(tags: List<TagsBean>) {
        renderPairs(tags.map { (it.name ?: "") to it.translated_name })
    }

    /** Render chips from plain strings (no translated name). */
    fun setTagNames(names: List<String>) {
        renderPairs(names.map { it to null })
    }

    private fun renderPairs(pairs: List<Pair<String, String?>>) {
        lastPairs = pairs
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

        val closeIconSize = if (showRemoveIcon) 14.ppppx else 0
        // 单行模式（NOWRAP）下不需要 bottom margin，也不给最后一个 chip 留 end margin——
        // 它只会把输入框顶得离内容偏远。
        val isSingleRow = flexWrap == com.google.android.flexbox.FlexWrap.NOWRAP
        val bottomGap = if (isSingleRow) 0 else 8.ppppx
        val lastIndex = pairs.size - 1
        pairs.forEachIndexed { idx, (name, translated) ->
            val endGap = when {
                !isSingleRow -> 8.ppppx
                idx == lastIndex -> 0
                else -> 8.ppppx
            }
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
                // Trailing × icon for editable chip rows.
                if (showRemoveIcon) {
                    val close = AppCompatResources
                        .getDrawable(context, R.drawable.ic_close_black_24dp)
                        ?.mutate()
                    close?.setBounds(0, 0, closeIconSize, closeIconSize)
                    close?.setTint(palette.textTag)
                    setCompoundDrawablesRelative(null, null, close, null)
                    compoundDrawablePadding = 4.ppppx
                }
                // Shrink end padding when the × occupies space; otherwise the chip looks lopsided.
                val endPadding = if (showRemoveIcon) 10.ppppx else 14.ppppx
                setPaddingRelative(14.ppppx, 7.ppppx, endPadding, 7.ppppx)
                layoutParams = LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply {
                    setMargins(0, 0, endGap, bottomGap)
                    // flexbox 默认 flexShrink=1，配合 flexWrap=nowrap 会把一行塞不下的 chip
                    // 按比例压窄，导致中文 chip 被挤成竖排多行。保持 chip 自然宽度，超出
                    // 交给外层 HorizontalScrollView 滚动。
                    flexShrink = 0f
                }
                setOnClickListener {
                    val custom = onTagClick
                    if (custom != null) {
                        custom.invoke(name)
                    } else {
                        val intent = Intent(context, SearchActivity::class.java).apply {
                            putExtra(Params.KEY_WORD, name)
                            putExtra(Params.INDEX, searchIndex)
                        }
                        context.startActivity(intent)
                    }
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
