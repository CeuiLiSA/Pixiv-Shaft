package ceui.pixiv.ui.muted

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.view.isVisible
import androidx.fragment.app.FragmentManager
import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.lisa.activities.TemplateActivity
import ceui.lisa.database.AppDatabase
import ceui.lisa.databinding.SheetMuteTagBinding
import ceui.lisa.helper.IllustNovelFilter
import ceui.lisa.models.TagsBean
import ceui.lisa.models.UserBean
import ceui.lisa.utils.Common
import ceui.lisa.utils.GlideUtil
import ceui.lisa.utils.Params
import ceui.lisa.utils.PixivOperate
import ceui.pixiv.witstudio.theme.V3Palette
import ceui.pixiv.utils.makeSheetTransparentAndFillNavBar
import ceui.pixiv.utils.ppppx
import ceui.pixiv.utils.screenHeight
import com.bumptech.glide.Glide
import com.google.android.material.R as MaterialR
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.color.MaterialColors
import kotlin.math.roundToInt

/**
 * 「屏蔽设定」MD3-Expressive bottom sheet —— 取代 legacy `ceui.lisa.dialogs.MuteDialog`
 *（居中弹窗 + zhy TagFlowLayout + 三颗 Borderless 按钮）。
 *
 * 视觉与 [ceui.pixiv.ui.bookmark.SelectTagBottomSheet] 同源：拖拽把手 + headline/supporting
 * 双行标题 + tonal 次操作 + 底部 filled 主操作。强调色全部由 [V3Palette] 在运行时 tint，
 * 跟随用户在设置里选的主题色（root 挂的 Material3 主题会把 colorPrimary 顶成 MD3 基线紫）。
 *
 * ## 标签胶囊的三态
 * | 状态 | 表现 |
 * |---|---|
 * | 未屏蔽 | 描边胶囊，无图标 |
 * | 已屏蔽 | 强调色实底 + 强调色描边 + 勾图标 |
 * | 已屏蔽但未生效 | 同上，但换 eye-off 图标 + 虚线描边 + 文字降一档，并缀「未生效」 |
 *
 * 三态之间只换色和图标，圆角始终是胶囊。状态切换即时生效，没有过渡动画——
 * 勾选是个开关，不是一段表演。
 *
 * ## 语义：真正的开关，不是只进不出
 * legacy 版把「已屏蔽」的标签预选中，但取消勾选再确定**什么也不会发生**——只有新增会写库。
 * 一个长得像开关、却只单向生效的控件是在骗人。本版保存时按差集写：
 * 新勾上的 → [PixivOperate.muteTag]，被取消勾选的 → [PixivOperate.unMuteTag]；
 * 保持原样的一个都不动（重新 mute 会把「未生效」的记录重置成生效，那是数据损失）。
 *
 * ## 两个 section（issue #1015）
 * 「按标签屏蔽」+「按作者屏蔽」。两段是**同一种勾选语义、同一颗保存按钮、同一个计数**，
 * 作者那一行落到 `tag_mute_table` 的 `type=MUTE_USER`（[PixivOperate.muteUser]）——所以它是
 * 一个 section 而不是二级弹窗或者新菜单项：用户要做的事没变，变的只是屏蔽的维度。
 *
 * 但**控件形状是两样的**：标签是胶囊（从一堆同类里挑几个），作者是 MD3 的 list item
 *（头像 + 名字 + 开关）。一个具体的人该有脸、有名字、有一个明确的开关，把他压成一枚
 * 只有文字的胶囊，既认不出是谁，也让人以为作者和标签是同一类东西。
 *
 * 这里**只有本地屏蔽，没有 pixiv 官方的「拉黑」**（[ceui.pixiv.ui.user.PixivBlockOperate]）。
 * 拉黑要发网络请求、会失败、且一般用户全站只有 1 个额度，塞进「勾一下→保存」这套即时可逆的
 * 开关语义里必然骗人；它继续留在画师主页的菜单里。
 */
class MuteTagSheet : BottomSheetDialogFragment() {

    // edgeToEdge：让 window 画到导航栏底下，root 的圆角背景才能延伸进底部 safe area。
    override fun getTheme(): Int = R.style.ThemeOverlay_App_BottomSheetDialog_EdgeToEdge

    private var _binding: SheetMuteTagBinding? = null
    private val binding get() = _binding!!

    private val palette by lazy { V3Palette.from(requireContext()) }

    @Suppress("UNCHECKED_CAST")
    private val tags: List<TagsBean> by lazy {
        (arguments?.getSerializable(Params.CONTENT) as? ArrayList<TagsBean>).orEmpty()
    }

    /** 打开这一刻已在屏蔽表里的标签名（含「未生效」的）。保存时与 [selected] 做差集。 */
    private val originallyMuted = mutableSetOf<String>()

    /** 已屏蔽但开关关掉的标签名 —— 只影响显示，不影响差集判定。 */
    private val ineffective = mutableSetOf<String>()

    /** 当前勾选集合，初值 = [originallyMuted]。 */
    private val selected = mutableSetOf<String>()

    /** 这件作品的作者；拿不到（legacy 入口可能没传）则整个「按作者屏蔽」section 不出现。 */
    private val author: UserBean? by lazy {
        arguments?.getSerializable(KEY_AUTHOR) as? UserBean
    }

    /** 作者维度的「原状态 / 当前勾选」，等价于标签那两个集合，只是就一个对象所以是布尔。 */
    private var authorOriginallyMuted = false
    private var authorSelected = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = SheetMuteTagBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        readMutedState()
        applyAccent()

        binding.btnCancel.setOnClickListener { dismissAllowingStateLoss() }
        binding.btnSave.setOnClickListener { save() }
        binding.btnHistory.setOnClickListener {
            startActivity(Intent(requireContext(), TemplateActivity::class.java).apply {
                putExtra(TemplateActivity.EXTRA_FRAGMENT, MUTED_TAGS_ROUTE)
            })
            dismissAllowingStateLoss()
        }

        // 没有标签时，section 1 的说明换成「这个作品没有标签」——两句同时出现是自相矛盾的。
        binding.emptyHint.isVisible = tags.isEmpty()
        binding.sectionTagHint.isVisible = tags.isNotEmpty()
        // 只要还有一个维度可勾就得留着保存键：无标签但有作者的作品照样能屏蔽作者。
        binding.btnSave.isVisible = tags.isNotEmpty() || author != null
        buildChips()
        bindAuthorSection()
        updateSummary()
    }

    /**
     * 读一次屏蔽表，按标签名匹配（与 legacy 一致：正则模式的记录也只按 name 比对）。
     * DB 是 `allowMainThreadQueries` 的同步库，条数量级在几百，这里直接读。
     */
    private fun readMutedState() {
        val muted = IllustNovelFilter.getMutedTags()
        val mutedByName = muted.filter { !it.name.isNullOrEmpty() }.associateBy { it.name }
        tags.forEach { tag ->
            val record = mutedByName[tag.name] ?: return@forEach
            originallyMuted.add(tag.name)
            if (!record.isEffective) ineffective.add(tag.name)
        }
        selected.addAll(originallyMuted)

        // 作者维度按 id 查一行就够（type=MUTE_USER）。它没有「未生效」这一态——
        // IllustNovelFilter.judgeUserID 只看记录在不在，所以那一行就是个开 / 关。
        author?.let { user ->
            val authorMuted = AppDatabase.getAppDatabase(Shaft.getContext())
                .searchDao()
                .getUserMuteEntityByID(user.id) != null
            authorOriginallyMuted = authorMuted
            authorSelected = authorMuted
        }
    }

    /**
     * 强调色刷到两颗按钮和两个 section 标题上：保存 = filled 实心（主操作），
     * 屏蔽记录 = tonal 淡底（次操作），section 标题 = 强调色的 MD3 subhead。
     * 用代码 tint 而非主题属性——见类注释。
     */
    private fun applyAccent() {
        binding.btnSave.backgroundTintList = ColorStateList.valueOf(palette.primary)
        binding.btnSave.setTextColor(Color.WHITE)
        binding.btnHistory.backgroundTintList = ColorStateList.valueOf(palette.alpha15)
        binding.btnHistory.setTextColor(palette.textAccent)
        binding.btnCancel.setTextColor(palette.textAccent)
        binding.sectionTagTitle.setTextColor(palette.textAccent)
        binding.sectionAuthorTitle.setTextColor(palette.textAccent)

        // 作者开关：只有选中态染主题色，未选中态走 MD3 的中性 outline / surface token。
        //
        // 刻意不照抄 SelectTagBottomSheet 那颗私密开关（它未选中态的 thumb 用 palette.textSecondary，
        // 暗色下是个亮紫点）：那边 switch 旁边有文字标签、且是个次要选项，这里它是整行**唯一**的
        // 状态指示，一颗紫 thumb 配紫色 track 边会被读成「已经开着了」。
        val sw = binding.authorSwitch
        val outline = MaterialColors.getColor(sw, MaterialR.attr.colorOutline)
        val trackOff = MaterialColors.getColor(sw, MaterialR.attr.colorSurfaceContainerHighest)

        // 头像描边：主题色，和 section 标题同一档（[V3Palette.textAccent]，暗色下会提亮到够看见）。
        // 写在这里而不是 XML：CircleImageView 用 TypedArray.getColor 读 civ_border_color，
        // 主题属性一旦没解析上，它的默认值是**纯黑**——在暗色 sheet 上就成了「看不见的 border」。
        binding.authorAvatar.borderColor = palette.textAccent
        val switchStates = arrayOf(
            intArrayOf(android.R.attr.state_checked),
            intArrayOf(-android.R.attr.state_checked),
        )
        sw.thumbTintList = ColorStateList(switchStates, intArrayOf(Color.WHITE, outline))
        sw.trackTintList = ColorStateList(switchStates, intArrayOf(palette.primary, trackOff))
        sw.trackDecorationTintList = ColorStateList(switchStates, intArrayOf(palette.primary, outline))
    }

    private fun buildChips() {
        val flow = binding.tagFlow
        flow.removeAllViews()
        tags.forEach { flow.addView(createChip(it)) }
    }

    /**
     * 「按作者屏蔽」那一行：头像 + 名字 + 开关，MD3 的 list item，**不是**上面那种胶囊。
     * 胶囊适合「从一堆同类里挑几个」；作者是一个具体的人，该有脸、有名字、有一个明确的开关。
     *
     * 名字空了退回 account、再退回 id——这一行必须能认出是谁。
     */
    private fun bindAuthorSection() {
        val user = author
        binding.sectionAuthor.isVisible = user != null
        if (user == null) return

        binding.authorName.text = listOfNotNull(user.name, user.account)
            .firstOrNull { it.isNotBlank() } ?: user.id.toString()
        // getUrl 对空 URL 返回 null（见其注释），所以缺头像的精简 bean 走 fallback 而不是 error。
        // 切圆交给 CircleImageView，不用 circleCrop——见布局里的注释。
        Glide.with(this)
            .load(GlideUtil.getUrl(user.profile_image_urls?.medium))
            .placeholder(R.drawable.no_profile)
            .fallback(R.drawable.no_profile)
            .error(R.drawable.no_profile)
            .into(binding.authorAvatar)

        binding.authorSwitch.isChecked = authorSelected
        // 开关自己不可点（XML 里 clickable=false），统一由整行驱动：两条路径会切出双击回弹。
        binding.authorRow.setOnClickListener {
            authorSelected = !authorSelected
            binding.authorSwitch.isChecked = authorSelected
            updateSummary()
        }
    }

    private fun createChip(tag: TagsBean): TextView {
        val name = tag.name
        val chip = TextView(requireContext()).apply {
            textSize = 13.5F
            gravity = Gravity.CENTER_VERTICAL
            typeface = Typeface.DEFAULT
            includeFontPadding = false
            minHeight = 38.ppppx
            compoundDrawablePadding = 6.ppppx
            isClickable = true
            isFocusable = true
            background = GradientDrawable().apply { shape = GradientDrawable.RECTANGLE }
            val lp = com.google.android.flexbox.FlexboxLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            lp.marginEnd = 8.ppppx
            lp.bottomMargin = 8.ppppx
            layoutParams = lp
        }
        chip.text = chipLabel(tag)
        renderChip(chip, name in selected, name in ineffective)
        chip.setOnClickListener {
            if (!selected.remove(name)) selected.add(name)
            chip.text = chipLabel(tag)
            renderChip(chip, name in selected, name in ineffective)
            updateSummary()
        }
        return chip
    }

    /**
     * 胶囊文案：`原文` +（有译名时）淡色小一号的译名 +（未生效时）「· 未生效」。
     * 译名和「未生效」都压成 [V3Palette.textSeries] 一档，主视线仍落在原文上。
     */
    private fun chipLabel(tag: TagsBean): CharSequence {
        val name = tag.name
        val builder = SpannableStringBuilder(name)
        val translated = tag.translated_name
        if (!translated.isNullOrEmpty() && translated != name) {
            appendDim(builder, "  $translated", 0.92F)
        }
        if (tag.name in ineffective && tag.name in selected) {
            appendDim(builder, "  ·  ${getString(R.string.mute_sheet_ineffective)}", 0.88F)
        }
        return builder
    }

    private fun appendDim(builder: SpannableStringBuilder, text: String, scale: Float) {
        val start = builder.length
        builder.append(text)
        builder.setSpan(
            ForegroundColorSpan(palette.textSeries),
            start, builder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        builder.setSpan(
            RelativeSizeSpan(scale),
            start, builder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
    }

    /**
     * 把胶囊按当前状态画出来。所有颜色派生自主题强调色，日夜两套由 [V3Palette] 内部处理。
     *
     * 选中就是选中：状态切换即时生效，不做过渡动画、不做形状变形。
     * 未生效的记录用虚线描边区分：实线=正在生效，虚线=挂着但没工作。
     */
    private fun renderChip(chip: TextView, isSelected: Boolean, isIneffective: Boolean) {
        val d = resources.displayMetrics.density
        val bg = chip.background as? GradientDrawable ?: return
        bg.cornerRadius = 999F * d
        bg.setColor(if (isSelected) palette.alpha20 else palette.alpha08)
        val strokeWidth = ((if (isSelected) 1.5F else 1F) * d).roundToInt().coerceAtLeast(1)
        val strokeColor = if (isSelected) palette.alpha50 else palette.alpha15
        if (isSelected && isIneffective) {
            bg.setStroke(strokeWidth, strokeColor, 5F * d, 4F * d)
        } else {
            bg.setStroke(strokeWidth, strokeColor)
        }
        chip.setTextColor(
            when {
                !isSelected -> requireContext().getColor(R.color.v3_text_2)
                isIneffective -> V3Palette.withAlpha(palette.textAccent, 0.62F)
                else -> palette.textAccent
            }
        )

        val vPad = (8 * d).roundToInt()
        chip.setPadding(((if (isSelected) 13 else 15) * d).roundToInt(), vPad, (15 * d).roundToInt(), vPad)
        chip.setCompoundDrawablesRelative(leadingIcon(isSelected, isIneffective), null, null, null)
    }

    /** 勾（生效）/ eye-off（未生效）图标；未选中态不挂图标。 */
    private fun leadingIcon(isSelected: Boolean, isIneffective: Boolean) =
        if (!isSelected) {
            null
        } else {
            val res = if (isIneffective) {
                R.drawable.ic_visibility_off_black_24dp
            } else {
                R.drawable.ic_check_24dp
            }
            AppCompatResources.getDrawable(requireContext(), res)?.mutate()?.apply {
                setBounds(0, 0, 16.ppppx, 16.ppppx)
                setTint(
                    if (isIneffective) {
                        V3Palette.withAlpha(palette.textAccent, 0.62F)
                    } else {
                        palette.textAccent
                    }
                )
            }
        }

    /** 计数横跨两个 section：底部那行说的是「这张 sheet 上一共勾了几个」，不分维度。 */
    private fun updateSummary() {
        val authorSlot = if (author != null) 1 else 0
        val total = tags.size + authorSlot
        binding.selectionSummary.text = if (total == 0) {
            ""
        } else {
            val count = selected.size + (if (authorSelected) 1 else 0)
            getString(R.string.mute_sheet_summary, count, total)
        }
    }

    /**
     * 差集落库：新勾上的 mute，被取消勾选的 unmute，没动的一个都不碰
     *（重新 mute 会把「未生效」记录重置回生效）。作者那一格同理，只是差集退化成一个布尔翻转。
     */
    private fun save() {
        val toMute = tags.filter { it.name in selected && it.name !in originallyMuted }
        val toUnMute = originallyMuted - selected
        val user = author?.takeIf { authorSelected != authorOriginallyMuted }
        if (toMute.isEmpty() && toUnMute.isEmpty() && user == null) {
            dismissAllowingStateLoss()
            return
        }
        toMute.forEach { PixivOperate.muteTag(it) }
        toUnMute.forEach { name ->
            // 删除按主键 (name.hashCode(), MUTE_TAG) 走，只需要名字；toast 交给外面统一发一条。
            PixivOperate.unMuteTag(TagsBean().apply { this.name = name }, false)
        }
        // 同样吞掉自带 toast：一次保存可能既动标签又动作者，连弹三条 toast 是噪音。
        user?.let {
            if (authorSelected) {
                PixivOperate.muteUser(it, false)
            } else {
                PixivOperate.unMuteUser(it, false)
            }
        }
        Common.showToast(getString(R.string.operate_success))
        dismissAllowingStateLoss()
    }

    override fun onStart() {
        super.onStart()
        val dialog = dialog as? BottomSheetDialog ?: return
        dialog.behavior.apply {
            skipCollapsed = true
            maxHeight = (screenHeight * MAX_HEIGHT_FRACTION).roundToInt()
            state = BottomSheetBehavior.STATE_EXPANDED
        }
        // sheet 容器透明 + 内容背景铺进底部 safe area：圆角与配色全由 root 的
        // bg_m3e_sheet_top 负责，否则主题默认 sheet 底会盖在圆角外、暗色模式露白。
        makeSheetTransparentAndFillNavBar()
    }

    companion object {
        const val TAG = "MuteTagSheet"

        /** 标签多时未必占满，给足高度即可；仍留一线底图暗示「这是一张浮层」。 */
        private const val MAX_HEIGHT_FRACTION = 0.82F

        /** [TemplateActivity] 的路由 key，不是 UI 文案。 */
        private const val MUTED_TAGS_ROUTE = "标签屏蔽记录"

        /** 作者 bean 的 argument key（[UserBean] 本身 Serializable，整只带过来即可）。 */
        private const val KEY_AUTHOR = "mute_sheet_author"

        /**
         * 唯一入口。空名字的标签直接滤掉——[PixivOperate.muteTag] 上来就 `name.hashCode()`，
         * 喂 null 会当场 NPE。重复 show 由 TAG 挡掉（长按菜单容易连点）。
         *
         * [author] 决定「按作者屏蔽」那个 section 出不出现；不传就只有标签那一段，
         * 老调用点不改也不会坏。
         */
        @JvmStatic
        @JvmOverloads
        fun show(fm: FragmentManager, tags: List<TagsBean>?, author: UserBean? = null) {
            if (fm.isStateSaved || fm.findFragmentByTag(TAG) != null) return
            val payload = ArrayList(tags.orEmpty().filter { !it.name.isNullOrBlank() })
            // id=0 的作者当没有：mute 记录拿 id 当主键，写进去就是「屏蔽记录」页上一行删不掉的脏数据。
            val validAuthor = author?.takeIf { it.id != 0 }
            MuteTagSheet().apply {
                arguments = Bundle().apply {
                    putSerializable(Params.CONTENT, payload)
                    validAuthor?.let { putSerializable(KEY_AUTHOR, it) }
                }
            }.show(fm, TAG)
        }
    }
}
