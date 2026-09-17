package ceui.lisa.update

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.recyclerview.widget.RecyclerView
import androidx.viewbinding.ViewBinding
import ceui.lisa.R
import ceui.pixiv.feeds.FeedFragment
import ceui.pixiv.feeds.FeedItem
import ceui.pixiv.feeds.FeedRenderer
import ceui.pixiv.feeds.FeedSkeletonView
import ceui.pixiv.feeds.feedRenderer
import ceui.pixiv.feeds.feedViewModels
import ceui.pixiv.feeds.updateItems
import ceui.pixiv.ui.v3.setupV3Toolbar
import ceui.pixiv.witstudio.theme.color
import ceui.pixiv.witstudio.theme.dp
import ceui.pixiv.witstudio.theme.lineHeightRatio
import ceui.pixiv.witstudio.theme.v3Font

/**
 * 版本历史，V3「下载 / 历史 / 管理」配方：顶栏 → 紧凑汇总（当前版本 + 唯一主操作）
 * → 一列发布卡。没有运营 Hero，内容密度和稳定行高优先。
 *
 * 跑在 feeds 框架上（数据源 [VersionHistorySource]）：首屏骨架、下拉刷新、空态、
 * 失败重试、断网恢复重试全部归框架，这里只留顶栏、720dp 限宽和卡片长相。
 * 列表不做条目动画（同下载管理与广场的取舍），展开更新说明就地换行数。
 */
class FragmentVersionHistory : FeedFragment(R.layout.fragment_version_history) {

    override val feedViewModel by feedViewModels { VersionHistorySource() }

    override val emptyStateText: CharSequence
        get() = getString(R.string.version_history_empty)

    override fun onCreateRenderers(): List<FeedRenderer<out FeedItem, out ViewBinding>> {
        // 随视图创建一次：Markwon 持有 context，不能挂到比 view 更长的生命周期上。
        val markwon = markwonFor(requireContext())
        return listOf(
            feedRenderer<VersionSummaryItem, VersionSummaryBinding>(
                inflate = { _, parent, _ -> VersionSummaryBinding(VersionSummaryView(parent.context)) },
                changePayload = { _, _ -> Unit },
            ) { cell ->
                cell.binding.root.bind(cell.item) { release ->
                    UpdateBottomSheet.newInstance(release).show(childFragmentManager, TAG_UPDATE_SHEET)
                }
            },
            feedRenderer<ReleaseItem, ReleaseCardBinding>(
                inflate = { _, parent, _ -> ReleaseCardBinding(ReleaseCardView(parent.context)) },
                changePayload = { _, _ -> Unit },
            ) { cell ->
                cell.binding.root.bind(cell.item, markwon, ::toggleExpanded)
            },
        )
    }

    override fun onCreateSkeletonView(layoutManager: RecyclerView.LayoutManager): FeedSkeletonView =
        VersionHistorySkeletonView(requireContext())

    override fun onListReady(listView: RecyclerView) {
        val ctx = requireContext()
        listView.itemAnimator = null
        listView.clipToPadding = false
        listView.setPadding(ctx.dp(20), ctx.dp(16), ctx.dp(20), ctx.dp(24))
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val ctx = requireContext()
        val content = view.findViewById<FrameLayout>(R.id.version_history_content)
        setupV3Toolbar(view, getString(R.string.version_history), content)
        // 宽屏上一列卡片铺满整块屏幕会读成横幅；同广场那几页收到 720dp 居中。
        val column = view.findViewById<LinearLayout>(R.id.version_history_column)
        content.addOnLayoutChangeListener { _, left, _, right, _, _, _, _, _ ->
            val width = minOf(right - left, ctx.dp(720))
            if (column.layoutParams.width != width) {
                column.layoutParams = FrameLayout.LayoutParams(width, -1, Gravity.CENTER_HORIZONTAL)
            }
        }
        feedBinding.feedStateText.apply {
            typeface = ctx.v3Font(400)
            textSize = 14f
            setTextColor(ctx.color(R.color.v3_text_2))
            lineHeightRatio(1.6f)
        }
    }

    private fun toggleExpanded(item: ReleaseItem) {
        feedViewModel.updateItems<ReleaseItem> {
            if (it.release.tagName == item.release.tagName) it.copy(expanded = !it.expanded) else it
        }
    }

    private companion object {
        const val TAG_UPDATE_SHEET = "update_dialog"
    }
}
