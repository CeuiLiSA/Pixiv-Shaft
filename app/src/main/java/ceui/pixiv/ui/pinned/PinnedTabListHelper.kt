package ceui.pixiv.ui.pinned

import android.content.res.ColorStateList
import android.graphics.Color
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ceui.lisa.R
import ceui.lisa.databinding.FragmentPinnedTabListBinding
import ceui.pixiv.witstudio.theme.V3Palette
import ceui.pixiv.witstudio.theme.dp
import ceui.pixiv.witstudio.theme.pressScale
import ceui.pixiv.witstudio.theme.ripple
import ceui.pixiv.witstudio.theme.shape
import ceui.pixiv.witstudio.theme.v3Font

/**
 * 「我置顶的内容」三个子 Tab 共享的 V3 列表装配与空态渲染。
 *
 * - 关闭 `itemAnimator`，过滤与数据更新直接就位，避免整卡淡入/位移闪烁；
 * - 统一 16dp 留白 / 12dp 卡距 / 720dp 宽屏居中；
 * - 列表底部自动承接 systemBars 与键盘安全区（`clipToPadding = false`）；
 * - 空态区分「列表本为空」与「搜索无匹配」，搜索无匹配时提供实色胶囊「清除搜索」。
 */
internal object PinnedTabListHelper {

    private const val EMPTY_IMAGE_ALPHA = 153 // 60%，对齐 feeds 框架空态插画口径

    fun setup(
        binding: FragmentPinnedTabListBinding,
        adapter: RecyclerView.Adapter<*>,
        onRefresh: () -> Unit,
        onClearSearch: () -> Unit,
    ) {
        val context = binding.root.context
        val palette = V3Palette.from(context)

        binding.refreshLayout.setColorSchemeColors(palette.textAccent)
        binding.refreshLayout.setProgressBackgroundColorSchemeColor(palette.cardFill)
        binding.refreshLayout.setOnRefreshListener(onRefresh)

        binding.recyclerView.apply {
            layoutManager = LinearLayoutManager(context)
            this.adapter = adapter
            itemAnimator = null
            addItemDecoration(PinnedListDecoration(context))
            val baseBottom = context.dp(12)
            ViewCompat.setOnApplyWindowInsetsListener(this) { v, windowInsets ->
                val bottom = windowInsets.getInsets(
                    WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime(),
                ).bottom
                v.updatePadding(bottom = baseBottom + bottom)
                windowInsets
            }
            ViewCompat.requestApplyInsets(this)
        }

        binding.emptyImage.imageTintList = ColorStateList.valueOf(
            ColorUtils.setAlphaComponent(palette.textAccent, EMPTY_IMAGE_ALPHA),
        )
        binding.emptyText.typeface = context.v3Font(400)

        binding.emptyAction.apply {
            text = context.getString(R.string.pinned_search_clear)
            typeface = context.v3Font(600)
            setTextColor(palette.onPrimary)
            background = context.ripple(palette.pillPrimary(999f), shape(999f, Color.WHITE))
            pressScale()
            setOnClickListener { onClearSearch() }
        }
    }

    fun renderEmptyState(
        binding: FragmentPinnedTabListBinding,
        hasLoaded: Boolean,
        isEmpty: Boolean,
        query: String,
        defaultEmptyRes: Int,
    ) {
        val showEmpty = hasLoaded && isEmpty
        binding.emptyContainer.isVisible = showEmpty
        if (!showEmpty) return
        val context = binding.root.context
        val trimmed = query.trim()
        val filtering = trimmed.isNotEmpty()
        binding.emptyText.text = if (filtering) {
            context.getString(R.string.pinned_search_empty, trimmed)
        } else {
            context.getString(defaultEmptyRes)
        }
        binding.emptyAction.isVisible = filtering
    }
}
