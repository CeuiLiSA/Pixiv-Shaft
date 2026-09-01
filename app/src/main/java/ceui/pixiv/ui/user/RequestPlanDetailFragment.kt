package ceui.pixiv.ui.user

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import ceui.lisa.R
import ceui.lisa.activities.UserActivityV3
import ceui.lisa.activities.followUser
import ceui.lisa.activities.unfollowUser
import ceui.lisa.databinding.FragmentRequestPlanDetailBinding
import ceui.lisa.databinding.SectionV3ArtistBinding
import ceui.lisa.utils.Common
import ceui.lisa.utils.GlideUtil
import ceui.lisa.utils.GlideUrlChild
import ceui.lisa.utils.Params
import ceui.pixiv.witstudio.theme.V3Palette
import ceui.pixiv.cache.ObjectPool
import ceui.pixiv.widgets.ProgressTextButton
import ceui.loxia.User
import ceui.pixiv.actions.PixivActions
import ceui.pixiv.ui.common.viewBinding
import ceui.pixiv.utils.setOnClick
import com.bumptech.glide.Glide
import com.google.android.flexbox.FlexboxLayout
import java.text.NumberFormat

/**
 * 约稿方案详情页(MD3-Expressive)。不折叠展示 [RequestPlan] 全部字段:
 * 封面 hero → 价格 → 可接受类型(6 项全列,含未开启项)→ 标题(译文+原文+语言)→
 * 说明(译文+原文全文)→ 元信息(方案 ID + AI 类型)。
 * 卡底/描边/强调色由 [V3Palette] 注入跟随 app 主题色;正文用 v3_text_* 适配日夜。
 * 由 [RequestPlanFeedFragment] 点击卡片经 TemplateActivity 路由「约稿方案详情」进入。
 */
class RequestPlanDetailFragment : Fragment(R.layout.fragment_request_plan_detail) {

    private val binding by viewBinding(FragmentRequestPlanDetailBinding::bind)

    private val plan: RequestPlan by lazy(LazyThreadSafetyMode.NONE) {
        @Suppress("DEPRECATION")
        requireArguments().getSerializable(ARG_PLAN) as RequestPlan
    }

    private val author: User? by lazy(LazyThreadSafetyMode.NONE) {
        @Suppress("DEPRECATION")
        requireArguments().getSerializable(ARG_USER) as? User
    }

    private lateinit var palette: V3Palette

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        palette = V3Palette.from(requireContext())
        applyInsets()
        applyTheme()
        bindCover()
        bindAuthor()
        bindContent()
        binding.backButton.setOnClick { requireActivity().onBackPressedDispatcher.onBackPressed() }
        binding.goRequestButton.setOnClick { openCommission() }
    }

    /**
     * 作者卡:复用作品详情页同一套 section_v3_artist(玻璃圆角卡 + 头像 + 名字/handle + 关注按钮),
     * 绑定逻辑完全对齐 ArtworkV3ViewHolder —— 效果一模一样。关注状态由 ObjectPool LiveData 驱动,
     * 关注/取关后自动重绑切换按钮。
     */
    private fun bindAuthor() {
        val user0 = author
        if (user0 == null) {
            binding.authorRowContainer.isVisible = false
            return
        }
        ObjectPool.update(user0)
        val uid = user0.id
        val ab = SectionV3ArtistBinding.inflate(layoutInflater, binding.authorRowContainer, true)
        applyTouchScale(ab.artistCard)
        ObjectPool.get<User>(uid).observe(viewLifecycleOwner) { user ->
            if (user != null) bindArtist(ab, user)
        }
    }

    /** 对齐 ArtworkV3ViewHolder.bind + bindFollowState:名字/handle/头像/简介 + 单按钮关注-取关切换。 */
    private fun bindArtist(ab: SectionV3ArtistBinding, user: User) {
        ab.artistName.text = user.name
        ab.artistHandle.text = "@${user.account ?: ""}"

        val openUser = View.OnClickListener {
            startActivity(
                Intent(requireContext(), UserActivityV3::class.java)
                    .putExtra(Params.USER_ID, user.id)
            )
        }
        ab.artistCard.setOnClickListener(openUser)
        ab.artistName.setOnClickListener(openUser)
        ab.artistName.setOnLongClickListener {
            Common.copy(requireContext(), user.name.orEmpty()); true
        }
        ab.artistHandle.setOnClickListener(openUser)
        ab.artistHandle.setOnLongClickListener {
            Common.copy(requireContext(), ab.artistHandle.text?.toString().orEmpty()); true
        }
        Glide.with(requireContext())
            .load(GlideUtil.getUrl(user.profile_image_urls?.medium))
            .error(R.drawable.no_profile)
            .into(ab.artistAvatar)

        ab.artistBio.isVisible = !user.comment.isNullOrBlank()
        if (ab.artistBio.isVisible) ab.artistBio.text = user.comment

        if (user.is_followed == true) {
            ab.followBtn.text = getString(R.string.unfollow)
            palette.applyUnfollowBtn(ab.followBtn)
            ab.followBtn.setOnClick { unfollowUser(it as ProgressTextButton, user.id) }
        } else {
            ab.followBtn.text = getString(R.string.follow)
            palette.applyFollowBtn(ab.followBtn)
            ab.followBtn.setTextColor(Color.WHITE)
            ab.followBtn.setOnClick {
                followUser(it as ProgressTextButton, user.id, PixivActions.defaultFollowRestrict())
            }
            ab.followBtn.setOnLongClickListener {
                followUser(ab.followBtn, user.id, Params.TYPE_PRIVATE); true
            }
        }
    }

    private fun applyTouchScale(view: View, scale: Float = 0.97f) {
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

    // EdgeToEdge:返回键避开状态栏;悬浮 CTA 避开手势条;滚动内容底部让出 CTA + 手势条的高度
    private fun applyInsets() {
        val dp = resources.displayMetrics.density
        ViewCompat.setOnApplyWindowInsetsListener(binding.detailRoot) { _, windowInsets ->
            val bars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.backButton.updateLayoutParams<FrameLayout.LayoutParams> {
                topMargin = bars.top + (8 * dp).toInt()
            }
            binding.goRequestButton.updateLayoutParams<FrameLayout.LayoutParams> {
                bottomMargin = bars.bottom + (16 * dp).toInt()
            }
            // 最后一张卡不被悬浮按钮遮住:让出 按钮高(54)+ 上下留白 + 手势条 inset
            binding.scroll.updatePadding(bottom = bars.bottom + (82 * dp).toInt())
            windowInsets
        }
        ViewCompat.requestApplyInsets(binding.detailRoot)
    }

    /** CTA「前往约稿」→ 官网约稿方案页下单(详情信息站内看,真正委托仍走官网)。 */
    private fun openCommission() {
        val url = "https://www.pixiv.net/request/plans/${plan.id}"
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    }

    // 卡底(主题 tint 圆角 + hairline)、分节标签/原文标签强调色、分隔线、返回键底衬
    private fun applyTheme() {
        val dp = resources.displayMetrics.density
        listOf(
            binding.cardPrice, binding.cardFlags, binding.cardTitle,
            binding.cardDesc, binding.cardMeta,
        ).forEach { it.background = palette.settingsCardBg(20f * dp, (1 * dp).toInt()) }

        listOf(
            binding.labelPrice, binding.labelFlags, binding.labelTitle,
            binding.labelDesc, binding.labelMeta,
            binding.titleOrigLang, binding.descOrigLang,
        ).forEach { it.setTextColor(palette.textAccent) }

        binding.descDivider.setBackgroundColor(palette.cardHairline)
        binding.backButton.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0x40000000)
        }

        // 底部 CTA:主题色对角渐变胶囊(primary → 偏移色相,同 app 既有渐变风格,不再是死板纯色);
        // clipToOutline 让 ?selectableItemBackground ripple 裁成圆角
        binding.goRequestButton.background = palette.seriesIconBg(999f * dp)
        binding.goRequestButton.clipToOutline = true
        binding.goRequestButton.foreground = androidx.core.content.ContextCompat.getDrawable(
            requireContext(), resolveSelectableItemBackground(),
        )
    }

    /** 解析 ?attr/selectableItemBackground 为可用作 foreground 的 ripple drawable res id。 */
    private fun resolveSelectableItemBackground(): Int {
        val out = android.util.TypedValue()
        requireContext().theme.resolveAttribute(
            android.R.attr.selectableItemBackground, out, true,
        )
        return out.resourceId
    }

    private fun bindCover() {
        val url = plan.image_urls?.cover?.takeIf { it.isNotBlank() }
            ?: plan.image_urls?.card?.takeIf { it.isNotBlank() }
        Glide.with(binding.cover)
            .load(url?.let { GlideUrlChild(it) })
            .placeholder(R.color.light_bg)
            .into(binding.cover)
        binding.heroTitle.text = plan.title?.display().orEmpty()
    }

    private fun bindContent() {
        // 价格
        binding.priceValue.text = getString(
            R.string.request_price_format,
            NumberFormat.getInstance().format(plan.standard_price),
        )

        // 可接受类型(全 6 项,含未开启)
        bindFlags()

        // 标题:译文 + 原文(+语言)
        val title = plan.title
        val titleTrans = title?.translation?.takeIf { it.isNotBlank() }
        val titleOrig = title?.original?.takeIf { it.isNotBlank() }
        binding.titleTrans.textOrGone(titleTrans)
        bindOriginal(binding.titleOrigLang, binding.titleOrig, titleOrig, title?.original_lang)
        binding.cardTitle.isVisible = titleTrans != null || titleOrig != null

        // 说明:译文 + 原文(+语言)全文
        val desc = plan.description
        val descTrans = desc?.translation?.takeIf { it.isNotBlank() }
        val descOrig = desc?.original?.takeIf { it.isNotBlank() }
        binding.descTrans.textOrGone(descTrans)
        bindOriginal(binding.descOrigLang, binding.descOrig, descOrig, desc?.original_lang)
        // 分隔线只在译文、原文同时存在时才有意义
        binding.descDivider.isVisible = descTrans != null && descOrig != null
        binding.cardDesc.isVisible = descTrans != null || descOrig != null

        // 元信息
        binding.metaId.text = plan.id.toString()
        binding.metaAi.text = getString(
            when (plan.ai_type) {
                AI_TYPE_GENERATED -> R.string.request_ai_generated
                AI_TYPE_NOT_AI -> R.string.request_ai_none
                else -> R.string.request_ai_unknown
            }
        )
    }

    /** 原文块:有原文才显语言标签 + 原文;语言缺失时标签退化为「原文」。 */
    private fun bindOriginal(langTag: TextView, origView: TextView, original: String?, lang: String?) {
        if (original == null) {
            langTag.isVisible = false
            origView.isVisible = false
            return
        }
        langTag.isVisible = true
        langTag.text = lang?.takeIf { it.isNotBlank() }
            ?.let { getString(R.string.request_detail_orig_format, it.uppercase()) }
            ?: getString(R.string.request_detail_orig_plain)
        origView.isVisible = true
        origView.text = original
    }

    private fun bindFlags() {
        val flags = plan.accept_flags
        // (是否开启, 文案);全部展示,未开启也列出(信息完整)
        val entries = listOf(
            (flags?.illust == true) to getString(R.string.request_flag_illust),
            (flags?.manga == true) to getString(R.string.request_flag_manga),
            (flags?.ugoira == true) to getString(R.string.request_flag_ugoira),
            (flags?.novel == true) to getString(R.string.request_flag_novel),
            (flags?.adult == true) to getString(R.string.request_flag_adult),
            (flags?.anonymous == true) to getString(R.string.request_flag_anonymous),
        )
        binding.flagsContainer.removeAllViews()
        entries.forEach { (on, label) -> addFlagPill(label, on) }
    }

    /** 开启=主题 tonal 填充 + ✓;未开启=描边胶囊 + 淡色,一眼分清有无。 */
    private fun addFlagPill(label: String, on: Boolean) {
        val dp = resources.displayMetrics.density
        val tv = TextView(binding.flagsContainer.context).apply {
            text = if (on) "✓ $label" else label
            textSize = 12.5f
            includeFontPadding = false
            typeface = ResourcesCompat.getFont(
                context,
                if (on) R.font.montserrat_semi_bold else R.font.montserrat_medium,
            )
            val padH = (14 * dp).toInt()
            val padV = (7 * dp).toInt()
            setPadding(padH, padV, padH, padV)
            if (on) {
                background = GradientDrawable().apply {
                    cornerRadius = 999f * dp
                    setColor(palette.alpha15)
                }
                setTextColor(palette.textAccent)
            } else {
                background = palette.pillSecondary(999f * dp, (1 * dp).toInt())
                setTextColor(resources.getColor(R.color.v3_text_3, null))
            }
        }
        val lp = FlexboxLayout.LayoutParams(
            FlexboxLayout.LayoutParams.WRAP_CONTENT,
            FlexboxLayout.LayoutParams.WRAP_CONTENT,
        ).apply {
            marginEnd = (10 * dp).toInt()
            topMargin = (10 * dp).toInt()
        }
        binding.flagsContainer.addView(tv, lp)
    }

    private fun TextView.textOrGone(value: String?) {
        if (value.isNullOrBlank()) {
            isVisible = false
        } else {
            text = value
            isVisible = true
        }
    }

    companion object {
        private const val ARG_PLAN = "request_plan"
        private const val ARG_USER = "request_plan_author"
        private const val AI_TYPE_NOT_AI = 1
        private const val AI_TYPE_GENERATED = 2

        @JvmStatic
        fun newInstance(plan: RequestPlan, author: User?): RequestPlanDetailFragment =
            RequestPlanDetailFragment().apply {
                arguments = Bundle().apply {
                    putSerializable(ARG_PLAN, plan)
                    putSerializable(ARG_USER, author)
                }
            }
    }
}
