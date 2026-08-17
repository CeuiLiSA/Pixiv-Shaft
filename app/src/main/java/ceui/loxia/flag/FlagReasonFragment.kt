package ceui.loxia.flag

import android.app.Activity
import android.content.Intent
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.core.graphics.ColorUtils
import androidx.recyclerview.widget.RecyclerView
import androidx.viewbinding.ViewBinding
import ceui.lisa.R
import ceui.lisa.activities.TemplateActivity
import ceui.lisa.databinding.CellFlagReasonHeaderBinding
import ceui.lisa.databinding.CellFlagTopicRowBinding
import ceui.lisa.databinding.FragmentToolbarFeedBinding
import ceui.lisa.fragments.SettingsCatalog
import ceui.pixiv.witstudio.theme.V3Palette
import ceui.loxia.Client
import ceui.loxia.IllustReportTopic
import ceui.pixiv.feeds.FeedFragment
import ceui.pixiv.feeds.FeedItem
import ceui.pixiv.feeds.FeedPage
import ceui.pixiv.feeds.FeedRenderer
import ceui.pixiv.feeds.FeedSource
import ceui.pixiv.feeds.feedRenderer
import ceui.pixiv.feeds.feedViewModels
import ceui.pixiv.ui.common.setUpToolbar
import ceui.pixiv.ui.common.viewBinding
import ceui.pixiv.utils.ppppx
import ceui.pixiv.utils.setOnClick
import ceui.pixiv.witstudio.theme.WitRowStyle

/**
 * 举报第一步：选违规类型（feeds 框架版）。违规类型列表由服务端 /v1/illust/report/topic-list
 * 动态下发（条数不固定，官方客户端也是这么做的——旧版写死 4 个本地字符串，服务端早已不认，
 * 一律 403）。
 *
 * 网络请求从 Fragment 里搬进了 [FeedSource]（数据归 VM 长期持有，Fragment 只声明「怎么画」），
 * 刷新 / 空态 / 错误重试 / 旋转存活全部交给框架——不再自己 launch 协程、手 inflate、手管
 * loadingView / errorView。数据一次性全量返回（`nextCursor = null`，无分页），因此不接
 * [FeedSource.loadFromCache]。
 *
 * MD3-E 分段视觉（圆底 hero + top/mid/bottom/single 圆角行）照旧：hero 做成一个 fullSpan
 * header 条目，每行的圆角背景在无分页、整表已知时于数据源里按位置预算好塞进 [FlagTopicFeedItem]。
 */
class FlagReasonFragment : FeedFragment(R.layout.fragment_toolbar_feed) {

    private val binding by viewBinding(FragmentToolbarFeedBinding::bind)

    // flagObjectId 是插画/用户等真实业务 ID，必须走 Long——历史上这里通过 Navigation Safe Args
    // 读（nav_graph 声明 argType="long"），但 newInstance 用 putInt 存，Bundle 类型不匹配时
    // Android 不抛异常、只静默返回默认值 0，导致举报功能提交的 illust_id 恒为 0。
    private val flagObjectId: Long by lazy {
        requireArguments().getLong(FlagDescFragment.FlagObjectIdKey)
    }
    private val flagObjectType: Int by lazy {
        requireArguments().getInt(FlagDescFragment.FlagObjectTypeKey)
    }

    // 详情页（FlagDescFragment）提交成功后回传 RESULT_OK,级联 finish 本页——把整个举报流程
    // 一次性收掉,落回发起举报的原始页面,而不是留一层空壳返回栈。取代静态可变标志 + onResume
    // 时序猜测（旧写法：跨屏通信全局状态，脆弱且不够显式）。
    private val flagDescLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                requireActivity().finish()
            }
        }

    // 零捕获约定：数据源只碰 Client，不碰 Fragment / View / arguments（这些和「怎么画」无关，
    // 违规类型的业务 ID 是提交那一步才用，见 onClickTopic）。
    override val feedViewModel by feedViewModels<Unit> {
        FeedSource { _ ->
            val topics = Client.appApi.getIllustReportTopicList().topic_list
            val items = buildList<FeedItem> {
                add(FlagReasonHeaderFeedItem)
                topics.forEachIndexed { index, topic ->
                    add(FlagTopicFeedItem(topic, flagRowBackgroundFor(index, topics.size)))
                }
            }
            FeedPage(items, null)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setUpToolbar(binding, feedBinding.feedListView)
        binding.toolbarTitle.text = getString(R.string.violated_rule)
    }

    override fun onListReady(listView: RecyclerView) {
        // 对齐旧版 parent_linear 的 paddingHorizontal 20dp + paddingBottom 28dp。左右间距走
        // ItemDecoration 而不是 RecyclerView padding：setUpToolbar 装的 window-inset 监听会在
        // 布局后对同一个列表 content.updatePadding(0,0,0,insets.bottom)，把左右 padding 一并抹回 0
        //（PrimeTags 等 feeds 页同理都用 decoration 兜横向间距，不碰列表 padding）。分段行靠
        // M3SetRow 的 2dp 上边距连成一张卡，这里绝不能再塞纵向 inset，否则中段行圆角会散架。
        listView.addItemDecoration(FlagRowInsetDecoration(horizontal = 20.ppppx, lastBottom = 28.ppppx))
    }

    override fun onCreateRenderers(): List<FeedRenderer<out FeedItem, out ViewBinding>> {
        return listOf(headerRenderer(), topicRenderer())
    }

    private fun headerRenderer() =
        feedRenderer<FlagReasonHeaderFeedItem, CellFlagReasonHeaderBinding>(
            inflate = CellFlagReasonHeaderBinding::inflate,
            fullSpan = true,
        ) { cell ->
            // bg_m3_icon_circle 的 solid 是静态值(m3_settings_icon_circle),不跟主题色走；
            // 关于页同款图标圆底靠运行时重染,这里照抄同一套配方。
            val palette = V3Palette.from(cell.binding.root.context)
            val iconCircleColor = ColorUtils.blendARGB(
                palette.cardFill, palette.primary, if (palette.isDark) 0.16f else 0.14f
            )
            (cell.binding.heroIconWrap.background.mutate() as? GradientDrawable)
                ?.setColor(iconCircleColor)
        }

    private fun topicRenderer() =
        feedRenderer<FlagTopicFeedItem, CellFlagTopicRowBinding>(
            inflate = CellFlagTopicRowBinding::inflate,
            create = { cell ->
                cell.binding.topicRowRoot.setOnClick { onClickTopic(cell.item.topic) }
            },
        ) { cell ->
            cell.binding.topicTitle.text = cell.item.topic.topic_title.orEmpty()
            cell.binding.topicRowRoot.setBackgroundResource(cell.item.backgroundRes)
            // wit_row_* 的 solid 是静态值(wit_menu_bg),不跟主题色走；关于页同款分段行都靠这行
            // 在运行时把 ripple 里的 GradientDrawable 重染成 V3Palette.cardFill(真正带主题色调的)。
            SettingsCatalog.applyThemedRowBg(cell.binding.topicRowRoot)
        }

    private fun onClickTopic(topic: IllustReportTopic) {
        flagDescLauncher.launch(Intent(requireContext(), TemplateActivity::class.java).apply {
            putExtra(TemplateActivity.EXTRA_FRAGMENT, "填写举报详细信息")
            putExtra(FlagDescFragment.FlagTopicIdKey, topic.topic_id)
            putExtra(FlagDescFragment.FlagTopicTitleKey, topic.topic_title.orEmpty())
            putExtra(FlagDescFragment.FlagObjectIdKey, flagObjectId)
            putExtra(FlagDescFragment.FlagObjectTypeKey, flagObjectType)
        })
    }

    companion object {
        fun newInstance(flagObjectId: Long, flagObjectType: Int): FlagReasonFragment {
            val fragment = FlagReasonFragment()
            fragment.arguments = Bundle().apply {
                putLong(FlagDescFragment.FlagObjectIdKey, flagObjectId)
                putInt(FlagDescFragment.FlagObjectTypeKey, flagObjectType)
            }
            return fragment
        }
    }
}

/** hero 圆底图标：整张列表只有一条、fullSpan 占满整行。 */
object FlagReasonHeaderFeedItem : FeedItem {
    override val feedKey: Any get() = "flag_reason_header"
}

/**
 * 单条违规类型。[backgroundRes] 是 MD3-E 分段圆角背景（top/mid/bottom/single），在无分页、
 * 整表一次到位时由数据源按行位置预算好——DiffUtil 靠 data class 的 equals 覆盖它。
 */
data class FlagTopicFeedItem(
    val topic: IllustReportTopic,
    @DrawableRes val backgroundRes: Int,
) : FeedItem {
    override val feedKey: Any get() = topic.topic_id
}

@DrawableRes
private fun flagRowBackgroundFor(index: Int, count: Int): Int =
    WitRowStyle.rowBackground(index, count)

/**
 * 举报列表的横向留白：每条左右各 [horizontal]（对齐旧版 parent_linear 的 paddingHorizontal），
 * 最后一条底部补 [lastBottom] 呼吸位（对齐旧版 paddingBottom）。刻意不加任何行间纵向 inset——
 * 分段行靠 M3SetRow 的 2dp 上边距连成整卡，多塞一截就把 top/mid/bottom 圆角拆散了。
 */
private class FlagRowInsetDecoration(
    private val horizontal: Int,
    private val lastBottom: Int,
) : RecyclerView.ItemDecoration() {

    override fun getItemOffsets(
        outRect: Rect,
        view: View,
        parent: RecyclerView,
        state: RecyclerView.State,
    ) {
        outRect.left = horizontal
        outRect.right = horizontal
        val position = parent.getChildAdapterPosition(view)
        val count = parent.adapter?.itemCount ?: 0
        if (position != RecyclerView.NO_POSITION && position == count - 1) {
            outRect.bottom = lastBottom
        }
    }
}
