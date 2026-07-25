package ceui.pixiv.ui.user

import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.isVisible
import androidx.viewbinding.ViewBinding
import ceui.lisa.R
import ceui.lisa.databinding.CellRequestPlanBinding
import ceui.lisa.utils.GlideUrlChild
import ceui.lisa.activities.TemplateActivity
import ceui.lisa.utils.Params
import ceui.lisa.utils.V3Palette
import ceui.loxia.Client
import ceui.pixiv.feeds.FeedFragment
import ceui.pixiv.feeds.FeedItem
import ceui.pixiv.feeds.FeedPage
import ceui.pixiv.feeds.FeedRenderer
import ceui.pixiv.feeds.FeedSource
import ceui.pixiv.feeds.feedRenderer
import ceui.pixiv.feeds.feedViewModels
import ceui.pixiv.utils.clearGlideOnRecycle
import ceui.pixiv.utils.setOnClick
import com.bumptech.glide.Glide
import com.google.android.flexbox.FlexboxLayout
import java.text.NumberFormat

/**
 * 画师主页「约稿中」Tab —— 仅在 user/detail 的 is_accept_request=true 时由 [UserActivityV3] 插入。
 *
 * 列表走 feeds 框架:GET /v1/user/request-plans 单页返回(无 next_url),用普通 [FeedSource]
 * 一次性映射,不需要 KListShow / PixivFeedSource 的翻页协议。卡片是 MD3-Expressive 封面卡
 * (见 cell_request_plan.xml + [requestPlanRenderer]),点击进站内 [RequestPlanDetailFragment] 详情页。
 *
 * 懒加载(autoLoad=false):作为 ViewPager2 tab 被相邻页预取时不请求,真正可见(onResume)才拉。
 */
class RequestPlanFeedFragment : FeedFragment() {

    private val userId: Int by lazy(LazyThreadSafetyMode.NONE) {
        requireArguments().getInt(Params.USER_ID)
    }

    private lateinit var palette: V3Palette

    // 内嵌 UserActivityV3 tab(无底栏),列表底部补手势条 inset,末尾卡片不被导航栏挡住
    override val applyBottomSafeInset: Boolean = true

    override val feedViewModel by feedViewModels(autoLoad = false) {
        // 零捕获约定:userId 先取成局部 Long,不把 Fragment 钉进长命 VM
        val uid = userId.toLong()
        FeedSource<String> { _ ->
            val resp = Client.appApi.getUserRequestPlans(uid)
            // resp.user 是该画师本人(约稿方案的作者),带给详情页展示作者行
            val author = resp.user
            FeedPage(
                items = resp.request_plans.orEmpty().map { RequestPlanFeedItem(it, author) },
                nextCursor = null, // 单页,永远到底
            )
        }
    }

    override fun onCreateRenderers(): List<FeedRenderer<out FeedItem, out ViewBinding>> {
        // Renderer 只活到 view 销毁(每次 onViewCreated 重建),可安全捕获 Fragment / palette
        palette = V3Palette.from(requireContext())
        return listOf(requestPlanRenderer())
    }

    private fun requestPlanRenderer() = feedRenderer<RequestPlanFeedItem, CellRequestPlanBinding>(
        inflate = CellRequestPlanBinding::inflate,
        create = { cell ->
            val card = cell.binding.cardRoot
            val dp = card.resources.displayMetrics.density
            // 卡底 = V3 settings-card 同款(圆角填充 + 12% 主题色 hairline);clipToOutline 让封面
            // 大图的上圆角跟着裁。用普通 View 而非 MaterialCardView 以兼容 QMUI/AppCompat 宿主主题。
            card.background = palette.settingsCardBg(28f * dp, (1 * dp).toInt())
            card.clipToOutline = true
            cell.binding.price.background = palette.pillPrimary(999f * dp)
            cell.binding.price.setTextColor(palette.floatingPillContent)
            cell.binding.badgeAdult.background = GradientDrawable().apply {
                cornerRadius = 999f * dp
                setColor(ADULT_BADGE_COLOR)
            }
            cell.binding.desc.setTextColor(palette.textSecondary)
            card.setOnClick { openPlan(cell.item) }
        },
        recycle = { cell -> cell.binding.cover.clearGlideOnRecycle() },
    ) { cell ->
        val plan = cell.item.plan
        val dp = cell.binding.root.resources.displayMetrics.density

        Glide.with(cell.binding.cover)
            .load(plan.image_urls?.cardOrCover()?.let { GlideUrlChild(it) })
            .placeholder(R.color.light_bg)
            .into(cell.binding.cover)

        cell.binding.title.text = plan.title?.display().orEmpty()
        cell.binding.price.text = getString(
            R.string.request_price_format,
            NumberFormat.getInstance().format(plan.standard_price),
        )

        val desc = plan.description?.display().orEmpty()
        cell.binding.desc.isVisible = desc.isNotEmpty()
        cell.binding.desc.text = desc

        cell.binding.badgeAdult.isVisible = plan.accept_flags?.adult == true

        val group = cell.binding.chipGroup
        group.removeAllViews()
        val flags = plan.accept_flags
        // tonal(填充)= 可画的内容类型;outline(描边)= 附加条件
        if (flags?.illust == true) group.addChip(getString(R.string.request_flag_illust), tonal = true, dp)
        if (flags?.manga == true) group.addChip(getString(R.string.request_flag_manga), tonal = true, dp)
        if (flags?.ugoira == true) group.addChip(getString(R.string.request_flag_ugoira), tonal = true, dp)
        if (flags?.novel == true) group.addChip(getString(R.string.request_flag_novel), tonal = true, dp)
        if (flags?.anonymous == true) group.addChip(getString(R.string.request_flag_anonymous), tonal = false, dp)
        if (plan.ai_type == AI_TYPE_GENERATED) group.addChip(getString(R.string.request_flag_ai), tonal = false, dp)
        group.isVisible = group.childCount > 0
    }

    /** 往 flexbox 追加一个胶囊 chip,tonal=填充(内容类型) / outline=描边(附加条件)。 */
    private fun FlexboxLayout.addChip(text: String, tonal: Boolean, dp: Float) {
        val chip = TextView(context).apply {
            this.text = text
            textSize = 11.5f
            includeFontPadding = false
            letterSpacing = 0.01f
            // 内容类型用 SemiBold(600) 更醒目,附加条件用 Medium(500) 退居其后
            typeface = ResourcesCompat.getFont(
                context,
                if (tonal) R.font.montserrat_semi_bold else R.font.montserrat_medium,
            )
            val padH = (12 * dp).toInt()
            val padV = (5 * dp).toInt()
            setPadding(padH, padV, padH, padV)
            if (tonal) {
                background = GradientDrawable().apply {
                    cornerRadius = 999f * dp
                    setColor(palette.alpha15)
                }
                setTextColor(palette.textAccent)
            } else {
                background = palette.pillSecondary(999f * dp, (1 * dp).toInt())
                setTextColor(palette.textSecondary)
            }
        }
        val lp = FlexboxLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            marginEnd = (8 * dp).toInt()
            topMargin = (5 * dp).toInt()
        }
        addView(chip, lp)
    }

    /** 点击卡片 → 站内 MD3-E 详情页(不再跳网页),经 TemplateActivity 路由并透传方案 + 作者。 */
    private fun openPlan(item: RequestPlanFeedItem) {
        val intent = Intent(requireContext(), TemplateActivity::class.java)
        intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "约稿方案详情")
        intent.putExtra(Params.CONTENT, item.plan)
        intent.putExtra(Params.USER_MODEL, item.user)
        startActivity(intent)
    }

    companion object {
        private const val AI_TYPE_GENERATED = 2
        private val ADULT_BADGE_COLOR = 0xFFE0405E.toInt()

        fun newInstance(userId: Int): RequestPlanFeedFragment =
            RequestPlanFeedFragment().apply {
                arguments = Bundle().apply { putInt(Params.USER_ID, userId) }
            }
    }
}

/** 约稿方案条目;内容相等性由 [RequestPlan] data class 决定,身份用方案 id。[user] 是方案作者(该画师)。 */
data class RequestPlanFeedItem(
    val plan: RequestPlan,
    val user: ceui.loxia.User? = null,
) : FeedItem {
    override val feedKey: Any get() = plan.id
}
