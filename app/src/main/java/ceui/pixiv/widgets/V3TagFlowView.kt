package ceui.pixiv.widgets

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.text.InputType
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.TextView
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.graphics.toColorInt
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.lifecycleScope
import ceui.lisa.R
import ceui.lisa.activities.SearchActivity
import ceui.lisa.activities.Shaft
import ceui.lisa.database.AppDatabase
import ceui.lisa.models.TagsBean
import ceui.lisa.utils.ClipBoardUtils
import ceui.lisa.utils.Params
import ceui.lisa.utils.PixivOperate
import ceui.lisa.utils.SearchTypeUtil
import ceui.pixiv.witstudio.theme.V3Palette
import ceui.pixiv.ui.settings.CustomThemeColor
import ceui.pixiv.ui.settings.ThemeColorCatalog
import ceui.loxia.Tag
import ceui.pixiv.ui.synonym.SynonymOperate
import ceui.pixiv.ui.translate.translateTag
import ceui.pixiv.utils.ppppx
import com.google.android.flexbox.AlignItems
import com.google.android.flexbox.FlexboxLayout
import com.hjq.toast.Toaster
import ceui.pixiv.witstudio.dialog.WitDialog

/**
 * 标签译文颜色（#1047-5）：跟随主题时与原文同色；否则把所选色（目录预设 / 自定义 hex）
 * 按 [V3Palette.textTag] 同一套压暗/压亮规则处理后再用。供标准标签流和手写小说系列卡共用。
 */
internal fun resolveTagTranslationColor(palette: V3Palette): Int {
    val settings = Shaft.sSettings ?: return palette.textTag
    if (settings.isTagTranslationColorFollowTheme) return palette.textTag
    val index = settings.tagTranslationColorIndex
    val hex = when {
        index == CustomThemeColor.INDEX ->
            CustomThemeColor.normalize(settings.tagTranslationColorCustomHex)
        index in ThemeColorCatalog.entries.indices -> ThemeColorCatalog.hexOf(index)
        else -> null
    } ?: return palette.textTag
    return V3Palette(hex.toColorInt(), palette.isDark).textTag
}

/**
 * V3 风格标签流 — 胶囊形背景 + `# name  译名` 格式 + 点击跳 SearchActivity。
 * 自带 signature dedupe，同一组 tags 多次 setTags 不会重建 view。
 *
 * 三个 API 按数据源选：
 * - [setTags]：[Tag]（小说）
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
     * 正文点击回调 —— 仅当 [showRemoveIcon] = true 且点击落在非 × 区域时使用。
     * 为 null 时回退到 [onTagClick]，不显示 × 的调用方行为完全不变。
     */
    var onTagBodyClick: ((name: String) -> Unit)? = null

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
     * chip 是否在原文后追加译名。默认 true（详情页 / 搜索页保留「原文 译名」双语）。
     * 列表条目关掉只留原文（#1038：带译名的 chip 一行放不下几个，把列表撑得老高）。
     * 长按菜单里的「复制译名」不受影响——译名数据仍在，只是不上屏。
     */
    var showTranslation: Boolean = true
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
     * 末尾动作块的文本。**非空时优先于「+N」** —— 画师主页筛选条要的是一个恒定的
     * 「高级搜索」入口，而不是"折叠了几个"的计数（折叠数对用户没有信息量：点进去看到的
     * 是画师全量 tag，不止被折叠的那几个）。
     * null（默认）= 回落到原行为：有溢出显示「+N」，无溢出不渲染任何块。
     */
    var overflowActionText: String? = null
        set(value) {
            if (field != value) {
                field = value
                lastSignature = null
                renderPairs(lastPairs)
            }
        }

    /**
     * 末尾动作块的前置图标（drawable res）。仅在 [onOverflowClick] 非空 —— 即动作块真的
     * 可点 —— 时渲染，纯展示的「+N」不带图标。
     * 尺寸显式 setBounds 跟随 chip 字号：矢量图 intrinsic 一般是 24dp，直接挂上去会比
     * 11.5sp 的 chip 文字高一倍。
     */
    var overflowActionIcon: Int? = null
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

    /**
     * × 图标边长（px）。渲染与命中判定共用同一来源，避免两处各写一个 14 各自漂移。
     */
    private val closeIconSize: Int
        get() = if (showRemoveIcon) CLOSE_ICON_DP.ppppx else 0

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
        // 译文色每次渲染算一次（不在 chip 循环里逐个算），并进签名：设置页改完色回来同一组
        // tags 再 setTags 也要重画，不能被 dedupe 吞掉。
        val translationColor = resolveTagTranslationColor(palette)
        val sig = buildString {
            pairs.forEach { (n, t) ->
                append(n); append('|'); append(t ?: ""); append(';')
            }
            append('#'); append(translationColor)
        }
        if (sig == lastSignature && childCount > 0) return
        lastSignature = sig
        val grew = pairs.size > prevCount

        // 编辑器保持挂载，直接保留焦点、选区与输入法 composing 状态（#1118）。
        // 只重建标签；焦点仍在不代表键盘可见，不能在这里强行 showSoftInput。
        for (i in childCount - 1 downTo 0) {
            if (!showRemoveIcon || getChildAt(i) !== _editor) removeViewAt(i)
        }
        val density = context.resources.displayMetrics.density
        val tagBgState = palette.tagLockedBg(999f * density).constantState

        // 紧凑模式整体降一档：字号 / 内边距 / 间距都收窄，便于嵌进列表卡片。
        val chipTextSize = if (compact) 11.5f else 13f
        val hPad = if (compact) 10.ppppx else 14.ppppx
        val vPad = if (compact) 4.ppppx else 7.ppppx
        val gap = if (compact) 6.ppppx else 8.ppppx
        // closeIconSize 走同名属性——渲染与 × 命中判定共用，见 isInRemoveZone
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
            // 触摸落点（chip 本地坐标）：OnTouchListener 记录，OnClickListener 消费。
            // 触摸监听恒返回 false，长按判定链完整保留。
            var lastTouchX = -1f
            val tv = TextView(context).apply {
                val translationSuffix =
                    if (showTranslation && !translated.isNullOrBlank()) "  $translated" else ""
                val fullText = buildString {
                    if (showHashPrefix) append("# ")
                    append(name)
                    append(translationSuffix)
                }
                // #1047-5：原文与译文用 SpannableString 分别上色，避免整段 setTextColor 混在一起。
                text = SpannableString(fullText).apply {
                    val originalEnd = fullText.length - translationSuffix.length
                    setSpan(
                        ForegroundColorSpan(palette.textTag),
                        0, originalEnd,
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                    if (originalEnd < length) {
                        setSpan(
                            ForegroundColorSpan(translationColor),
                            originalEnd, length,
                            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                        )
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
                // 编辑模式（showRemoveIcon）下胶囊分两个热区：尾部 × 删除、正文交给
                // onTagBodyClick（搜索栏里是「还原到输入框编辑」），落点用 DOWN 记录的 x 判定。
                // lastTouchX < 0（performClick / 无障碍点击，没有触摸信息）回退到 onTagClick，
                // 保持既有语义。
                setOnClickListener {
                    val touchX = lastTouchX
                    lastTouchX = -1f
                    val bodyClick = onTagBodyClick
                    val tapOnBody = showRemoveIcon && bodyClick != null && touchX >= 0f
                        && !isInRemoveZone(this, touchX)
                    if (tapOnBody) {
                        bodyClick?.invoke(name)
                    } else {
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
                // 长按回调晚读：渲染时 onTagLongClick 可能尚未 set（SearchActivity 里先
                // refreshChipsUI 再 setOnTagLongClick），所以监听器无条件挂、回调在长按
                // 触发时再取——和 onTagClick 同套路。
                setOnLongClickListener {
                    // 长按已消费这次手势，后续键盘/读屏点击不能复用本次按下的落点。
                    lastTouchX = -1f
                    val handler = onTagLongClick
                    if (handler != null) {
                        handler.invoke(name)
                    } else {
                        showTagActionMenu(name, translated)
                    }
                    true
                }
            }
            applyTouchScale(tv, 0.94f) { x -> lastTouchX = x }
            addView(tv)
        }

        // 末尾动作块。两种角色：
        // - 纯展示「+N」：显示折叠了多少个 tag，弱化配色（textSecondary），与小说 V3 系列
        //   详情页 item 一致；不可点击、不吃事件（列表卡片里点它要穿透给卡片）。
        // - 真动作（[onOverflowClick] 非空，如画师主页的「高级搜索」）：强调色文字 +
        //   可选前置图标 + touch scale，让它读起来是个入口而不是计数。
        // [overflowActionText] 非空时始终用它，见该属性的 KDoc。
        val actionText = overflowActionText ?: if (overflowCount > 0) "+$overflowCount" else null
        if (actionText != null) {
            val isAction = onOverflowClick != null
            val more = TextView(context).apply {
                text = actionText
                textSize = chipTextSize
                setTextColor(if (isAction) palette.textAccent else palette.textSecondary)
                background = tagBgState?.newDrawable()?.mutate()
                setPaddingRelative(hPad, vPad, hPad, vPad)
                layoutParams = LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply {
                    setMargins(0, 0, gap, bottomGap)
                    flexShrink = 0f
                }
                if (isAction) {
                    overflowActionIcon?.let { iconRes ->
                        AppCompatResources.getDrawable(context, iconRes)?.mutate()?.let { icon ->
                            val size = (chipTextSize * density).toInt()
                            icon.setBounds(0, 0, size, size)
                            icon.setTint(palette.textAccent)
                            setCompoundDrawablesRelative(icon, null, null, null)
                            compoundDrawablePadding = 4.ppppx
                        }
                    }
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
            if (ed.parent === this) {
                // 新标签追加在后面，将编辑器移到末尾即可，无需 detach/attach。
                bringChildToFront(ed)
            } else {
                (ed.parent as? ViewGroup)?.removeView(ed)
                addView(ed)
            }
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
        // 顺序：原文 / 译文（可选）/ 翻译 / 固定（host 提供 onPinTag 才有）/ 添加为同义词 / 屏蔽
        val labels = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()
        labels.add(context.getString(R.string.v3_tag_menu_copy_original))
        actions.add { copyToClipboard(name) }
        if (hasTranslation) {
            labels.add(context.getString(R.string.v3_tag_menu_copy_translation))
            actions.add { copyToClipboard(translated!!) }
        }
        // 翻译原文（#1054）：冷门 tag 没译名、或 pixiv 只给英文译名时现翻成 app 内语言。
        // 不按「有没有译名」做条件隐藏——译名是英文的情况判不准，恒显示最省心。
        // 协程挂在宿主 Fragment 的 view lifecycle 上（列表/详情都在 Fragment 里），
        // 拿不到再退到 Activity；两者都没有的场合不会有这个菜单。
        labels.add(context.getString(R.string.string_translate_caption))
        actions.add {
            val owner = findViewTreeLifecycleOwner() ?: (context as? LifecycleOwner)
            if (owner != null) {
                translateTag(context, owner.lifecycleScope, name)
            }
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
        // 已屏蔽的 tag 给「取消屏蔽」而不是再屏蔽一次（issue #1003）——重复 muteTag 的
        // REPLACE 会把「已屏蔽但未生效」的记录重置成生效，且用户无从在此解除屏蔽。
        val alreadyMuted = AppDatabase.getAppDatabase(context).searchDao()
            .getTagMuteEntityByID(name.hashCode()) != null
        if (alreadyMuted) {
            labels.add(context.getString(R.string.v3_tag_menu_unmute))
            actions.add { unMuteTag(name, translated) }
        } else {
            labels.add(context.getString(R.string.v3_tag_menu_mute))
            actions.add { muteTag(name, translated) }
        }

        // 标题写明按中的是哪个 tag（issue #1003：列表卡片的 chip 小，容易误按）。
        // 原文译文都给，QMUI 标题不限行数，过长会换行不会截断。
        WitDialog.MenuDialogBuilder(context)
            .setTitle(buildString {
                append(name)
                if (hasTranslation) {
                    append("  "); append(translated)
                }
            })
            .addItems(labels.toTypedArray()) { dialog, which ->
                actions[which].invoke()
                dialog.dismiss()
            }
            .show()
    }

    private fun copyToClipboard(text: String) {
        if (ClipBoardUtils.setPrimaryClip(context, ClipData.newPlainText("pixiv-tag", text))) {
            Toaster.showShort(R.string.has_copyed)
        } else {
            Toaster.showShort(R.string.msg_copy_failed)
        }
    }

    private fun muteTag(name: String, translated: String?) {
        val bean = TagsBean().apply {
            this.name = name
            this.translated_name = translated
        }
        PixivOperate.muteTag(bean)
        Toaster.showShort(R.string.string_382)
    }

    private fun unMuteTag(name: String, translated: String?) {
        val bean = TagsBean().apply {
            this.name = name
            this.translated_name = translated
        }
        PixivOperate.unMuteTag(bean, false)
        Toaster.showShort(R.string.string_383)
    }

    /**
     * 按压缩放反馈。**恒返回 false** —— 不能消费事件，否则 [View.onTouchEvent] 不再执行，
     * 长按菜单会直接失效。[onDown] 把落点交给调用方（chip 的 × / 正文命中判定）。
     */
    private fun applyTouchScale(view: View, scale: Float, onDown: ((Float) -> Unit)? = null) {
        view.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    onDown?.invoke(event.x)
                    v.animate().scaleX(scale).scaleY(scale).setDuration(200).start()
                }

                MotionEvent.ACTION_UP ->
                    v.animate().scaleX(1f).scaleY(1f).setDuration(200).start()

                // 手势被打断（横滚抢走事件等）：缩放复位，同时把落点清掉，
                // 免得之后一次无障碍 performClick 拿到上一次的残留坐标。
                MotionEvent.ACTION_CANCEL -> {
                    onDown?.invoke(-1f)
                    v.animate().scaleX(1f).scaleY(1f).setDuration(200).start()
                }
            }
            false
        }
    }

    /**
     * 点击落点是否落在尾部 × 区。
     *
     * 布局事实（见 [renderPairs]）：胶囊尾部依次是 `paddingEnd` 内边距、`closeIconSize`
     * 的图标，图标左侧隔着 `compoundDrawablePadding` 才是文字。于是
     * 「宽度 − paddingEnd − (图标 + 图标间距)」正好落在文字右边缘 —— 以它为界向右即 × 区，
     * 不会把任何文字算进去（[REMOVE_HIT_SLOP_DP] 可再向右扩一点余量）。
     * RTL 下图标在左，用 `paddingEnd` + layoutDirection 镜像同一套算法。
     */
    private fun isInRemoveZone(chip: TextView, x: Float): Boolean {
        val padEnd = chip.paddingEnd
        // compoundDrawablePadding 是 TextView 的 API，而 chip 本身就是 TextView，
        // 参数按 TextView 收口，比在 View 上做向下转型干净。
        val iconZone = closeIconSize + chip.compoundDrawablePadding
        // 短标签（如单字 tag）的胶囊宽度本来就只比图标大一点，再用「不超过一半」封顶，
        // 保证正文总留得下一段可点区域。
        val limit = (chip.width / 2 - padEnd).coerceAtLeast(iconZone)
        val zone = (iconZone + REMOVE_HIT_SLOP_DP.ppppx).coerceAtMost(limit)
        return if (chip.layoutDirection == View.LAYOUT_DIRECTION_RTL) {
            x <= padEnd + zone
        } else {
            x >= chip.width - padEnd - zone
        }
    }

    private companion object {
        /** × 图标边长（dp）。 */
        const val CLOSE_ICON_DP = 14

        /**
         * × 命中区在「图标 + 图标间距」之外额外扩展的余量（dp）。
         * 0 = 命中区正好是胶囊右侧不含文字的那一段，正文不会被误判成删除；
         * 调大更好点中，代价是最右侧若干 dp 的文字归入 × 区。
         */
        const val REMOVE_HIT_SLOP_DP = 0
    }
}
