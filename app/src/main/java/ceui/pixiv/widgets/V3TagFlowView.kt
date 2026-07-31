package ceui.pixiv.widgets

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.text.InputType
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.content.res.AppCompatResources
import ceui.lisa.R
import ceui.lisa.activities.SearchActivity
import ceui.lisa.activities.Shaft
import ceui.lisa.models.TagsBean
import ceui.lisa.utils.ClipBoardUtils
import ceui.lisa.utils.Params
import ceui.lisa.utils.PixivOperate
import ceui.lisa.utils.SearchTypeUtil
import ceui.lisa.utils.V3Palette
import ceui.loxia.Tag
import ceui.pixiv.ui.synonym.SynonymOperate
import ceui.pixiv.utils.ppppx
import com.google.android.flexbox.AlignItems
import com.google.android.flexbox.FlexboxLayout
import com.qmuiteam.qmui.widget.dialog.QMUIDialog

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

    /**
     * Optional long-press handler. When non-null, long-pressing a chip invokes
     * this callback and consumes the gesture (no regular click fires).
     */
    var onTagLongClick: ((name: String) -> Unit)? = null

    /**
     * 当 host 能提供「固定 tag」语义时（详情页知道当前 illust）赋值。
     * 接收 (tag 名, 译名, 新的 pinned 状态)；view 内部已查过 DB 当前状态并切换。
     * 非空时长按菜单会多一条「固定/取消固定」项。
     */
    var onPinTag: ((name: String, translated: String?, newPinned: Boolean) -> Unit)? = null

    /** Show a trailing × icon on each chip — for editable/removable chip rows. */
    var showRemoveIcon: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                // Force re-render with the last known tag list so flipping this
                // property after a setTags*() call reflects immediately.
                lastSignature = null
                renderPairs(lastPairs)
            }
        }

    /**
     * 紧凑模式 — 更小的字号 / 内边距 / 间距。用于把整套 chip 塞进列表卡片
     * （如小说卡片）时「再小一号」，与详情页大 chip 区分。视觉仍走同一 palette，
     * 自动适配日夜 + 主题色。
     */
    var compact: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                lastSignature = null
                renderPairs(lastPairs)
            }
        }

    /**
     * 是否给每个 chip 加 `# ` 前缀。默认 true（与详情页 / 搜索页一致）。列表卡片可关掉，
     * 只显示纯 tag 文本。仅影响本 view 实例，不动其他调用方。
     */
    var showHashPrefix: Boolean = true
        set(value) {
            if (field != value) {
                field = value
                lastSignature = null
                renderPairs(lastPairs)
            }
        }

    /**
     * 最多展示的 tag 数；超出部分折叠成一个「+N」溢出块（参考小说 V3 系列详情页 item）。
     * 默认 -1 = 不限、不折叠，保持其他调用方原样。设为正数时同时解除 flexbox 的 maxLine
     * 行数裁剪，避免最后一个 chip 被硬切，溢出改由「+N」块表达。
     */
    var maxTags: Int = -1
        set(value) {
            if (field != value) {
                field = value
                lastSignature = null
                renderPairs(lastPairs)
            }
        }

    /**
     * 「+N」溢出块的点击回调。非空时溢出块可点击（带 touch scale），用于画师主页
     * tag 筛选条的展开/收起（PR #947）；保持 null 则溢出块维持原样 —— 纯展示、
     * 不可点击、不吃事件（列表卡片里点它应该继续穿透给卡片本身）。
     * 是否挂监听在渲染时决定，所以要在 setTags*() 之前赋值。
     */
    var onOverflowClick: (() -> Unit)? = null

    /**
     * 溢出动作块的替代文本。[maxTags] 未触发折叠（无溢出）而本值非空时，末尾仍渲染
     * 一个与「+N」同款式的动作块显示该文本 —— 展开态的「收起」就是这么来的。
     * 触发折叠时忽略本值，始终显示「+N」。null（默认）= 无溢出就不渲染任何块。
     */
    var overflowActionText: String? = null
        set(value) {
            if (field != value) {
                field = value
                lastSignature = null
                renderPairs(lastPairs)
            }
        }

    private var _editor: EditText? = null

    /**
     * 编辑模式（[showRemoveIcon]=true）下末尾嵌入的输入框。外部直接挂 listener / 读写
     * text。非编辑模式访问返回 null。
     */
    val editor: EditText?
        get() = if (showRemoveIcon) ensureEditor() else null

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
        val prevCount = lastPairs.size
        lastPairs = pairs
        val sig = buildString {
            pairs.forEach { (n, t) ->
                append(n); append('|'); append(t ?: ""); append(';')
            }
        }
        if (sig == lastSignature && childCount > 0) return
        lastSignature = sig
        val grew = pairs.size > prevCount

        removeAllViews()
        val density = context.resources.displayMetrics.density
        val tagBgState = palette.tagLockedBg(999f * density).constantState

        // 紧凑模式整体降一档：字号 / 内边距 / 间距都收窄，便于嵌进列表卡片。
        val chipTextSize = if (compact) 11.5f else 13f
        val hPad = if (compact) 10.ppppx else 14.ppppx
        val vPad = if (compact) 4.ppppx else 7.ppppx
        val gap = if (compact) 6.ppppx else 8.ppppx
        val closeIconSize = if (showRemoveIcon) 14.ppppx else 0
        // 单行模式（NOWRAP）下不需要 bottom margin，也不给最后一个 chip 留 end margin——
        // 它只会把输入框顶得离内容偏远。
        val isSingleRow = flexWrap == com.google.android.flexbox.FlexWrap.NOWRAP
        val bottomGap = if (isSingleRow) 0 else gap
        // tag 上限 + 「+N」折叠（参考小说 V3 系列详情页 item）。maxTags<=0 时不限、不折叠，
        // 保持其他调用方原样。折叠模式下解除 flexbox 的 maxLine 行裁剪，避免末尾 chip 被硬切，
        // 超出的数量统一由「+N」块表达。
        val overflowCount = if (maxTags in 1 until pairs.size) pairs.size - maxTags else 0
        val visiblePairs = if (overflowCount > 0) pairs.take(maxTags) else pairs
        if (maxTags > 0 && maxLine != -1) {
            maxLine = -1
        }
        val lastIndex = visiblePairs.size - 1
        visiblePairs.forEachIndexed { idx, (name, translated) ->
            val endGap = when {
                !isSingleRow -> gap
                idx == lastIndex -> 0
                else -> gap
            }
            val tv = TextView(context).apply {
                text = buildString {
                    if (showHashPrefix) append("# ")
                    append(name)
                    if (!translated.isNullOrBlank()) {
                        append("  "); append(translated)
                    }
                }
                textSize = chipTextSize
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
                val endPadding = if (showRemoveIcon) (hPad - 4.ppppx) else hPad
                setPaddingRelative(hPad, vPad, endPadding, vPad)
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
                // 长按回调晚读：渲染时 onTagLongClick 可能尚未 set（SearchActivity 里先
                // refreshChipsUI 再 setOnTagLongClick），所以监听器无条件挂、回调在长按
                // 触发时再取——和 onTagClick 同套路。
                setOnLongClickListener {
                    val handler = onTagLongClick
                    if (handler != null) {
                        handler.invoke(name)
                    } else {
                        showTagActionMenu(name, translated)
                    }
                    true
                }
            }
            applyTouchScale(tv, 0.94f)
            addView(tv)
        }

        // 「+N」折叠块：显示折叠了多少个 tag，弱化配色（textSecondary），与小说 V3 系列
        // 详情页 item 一致。默认不可点击、不吃事件（列表卡片里点它要穿透给卡片）；
        // [onOverflowClick] 非空的调用方（画师主页 tag 筛选条）把它变成展开/收起开关，
        // 无溢出时若 [overflowActionText] 非空则改显示该文本（展开态的「收起」）。
        val actionText = if (overflowCount > 0) "+$overflowCount" else overflowActionText
        if (actionText != null) {
            val more = TextView(context).apply {
                text = actionText
                textSize = chipTextSize
                setTextColor(palette.textSecondary)
                background = tagBgState?.newDrawable()?.mutate()
                setPaddingRelative(hPad, vPad, hPad, vPad)
                layoutParams = LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply {
                    setMargins(0, 0, gap, bottomGap)
                    flexShrink = 0f
                }
                if (onOverflowClick != null) {
                    // 回调晚读，与 onTagClick 同套路；是否挂监听仍看渲染时是否非空，
                    // 免得给纯展示调用方平白挂上可点击语义。
                    setOnClickListener { onOverflowClick?.invoke() }
                }
            }
            if (onOverflowClick != null) {
                applyTouchScale(more, 0.94f)
            }
            addView(more)
        }

        if (showRemoveIcon) {
            val ed = ensureEditor()
            (ed.parent as? ViewGroup)?.removeView(ed)
            addView(ed)
        }

        // 新追加 chip 时把外层 HSV 滚到末尾，不让新 commit 的 chip 躲到屏幕外。
        if (grew) {
            (parent as? HorizontalScrollView)?.let { hsv ->
                hsv.post { hsv.fullScroll(View.FOCUS_RIGHT) }
            }
        }
    }

    private fun ensureEditor(): EditText {
        _editor?.let { return it }
        val ed = EditText(context).apply {
            background = null
            setTextColor(palette.textTag)
            setHintTextColor(palette.textTag and 0x66FFFFFF.toInt())
            textSize = 15f
            setPadding(4.ppppx, 6.ppppx, 8.ppppx, 6.ppppx)
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            inputType = InputType.TYPE_CLASS_TEXT
            isSingleLine = true
            minWidth = 96.ppppx
            layoutParams = LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                flexShrink = 0f
                // 当 chip 们没撑满 HSV 时，让 editor 吃掉剩余空间，避免出现「只有 1 个 tag，
                // 右边输入框只剩 48dp」的尴尬。chip 溢出时 flexGrow 不生效，HSV 正常横滚。
                flexGrow = 1f
            }
        }
        _editor = ed
        return ed
    }

    private fun showTagActionMenu(name: String, translated: String?) {
        val hasTranslation = !translated.isNullOrBlank()
        // 顺序：原文 / 译文（可选）/ 固定（host 提供 onPinTag 才有）/ 添加为同义词 / 屏蔽
        val labels = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()
        labels.add(context.getString(R.string.v3_tag_menu_copy_original))
        actions.add { copyToClipboard(name) }
        if (hasTranslation) {
            labels.add(context.getString(R.string.v3_tag_menu_copy_translation))
            actions.add { copyToClipboard(translated!!) }
        }
        val pinHandler = onPinTag
        if (pinHandler != null) {
            // 跟 FragmentIllust 长按 dialog 用同一对 string_442/443
            val existing = PixivOperate.getSearchHistory(name, SearchTypeUtil.SEARCH_TYPE_DB_KEYWORD)
            val pinned = existing != null && existing.isPinned
            labels.add(
                context.getString(if (pinned) R.string.string_443 else R.string.string_442)
            )
            actions.add { pinHandler.invoke(name, translated, !pinned) }
        }
        // 同义词词典（issue #904）：长按标签加入词典，备注自动填译文。
        // 功能总开关默认关闭，关闭时菜单与本功能存在之前完全一致。
        if (Shaft.sSettings.isSynonymDictEnabled) {
            labels.add(context.getString(R.string.synonym_add_as_synonym))
            actions.add {
                SynonymOperate.showAddAsSynonymDialog(context, name, translated)
            }
        }
        labels.add(context.getString(R.string.v3_tag_menu_mute))
        actions.add { muteTag(name, translated) }

        QMUIDialog.MenuDialogBuilder(context)
            .addItems(labels.toTypedArray()) { dialog, which ->
                actions[which].invoke()
                dialog.dismiss()
            }
            .show()
    }

    private fun copyToClipboard(text: String) {
        if (ClipBoardUtils.setPrimaryClip(context, ClipData.newPlainText("pixiv-tag", text))) {
            Toast.makeText(context, R.string.has_copyed, Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, R.string.msg_copy_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun muteTag(name: String, translated: String?) {
        val bean = TagsBean().apply {
            this.name = name
            this.translated_name = translated
        }
        PixivOperate.muteTag(bean)
        Toast.makeText(context, R.string.string_382, Toast.LENGTH_SHORT).show()
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
