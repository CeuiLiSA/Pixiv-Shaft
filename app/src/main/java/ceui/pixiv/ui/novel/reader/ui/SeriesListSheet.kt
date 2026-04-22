package ceui.pixiv.ui.novel.reader.ui

import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ceui.lisa.R
import ceui.loxia.Client
import ceui.loxia.Novel
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * 系列单篇切换 bottom sheet。复用 [ChapterListSheet] 的 [ReaderSheetUi] / [ReaderSheetPalette]
 * 骨架，颜色全部走 V3 token，白天 / 夜间自动适配。
 *
 * 行为：
 *   - 拉同一系列的全部单篇（`/v2/novel/series` 分页拉到尾，最多 5 页 = 150 篇兜底）
 *   - 高亮当前正在读的那一篇
 *   - 点击其它篇 → finish 当前 reader activity 并启动新的
 */
class SeriesListSheet : BottomSheetDialogFragment() {

    private var seriesId: Long = 0L
    private var currentNovelId: Long = 0L
    private var seriesTitle: String? = null
    private var onSelected: ((Novel) -> Unit)? = null

    private var listView: RecyclerView? = null
    private var loadingView: View? = null
    private var emptyView: TextView? = null
    private var countView: TextView? = null

    fun configure(
        seriesId: Long,
        currentNovelId: Long,
        seriesTitle: String? = null,
        onSelected: (Novel) -> Unit,
    ) {
        this.seriesId = seriesId
        this.currentNovelId = currentNovelId
        this.seriesTitle = seriesTitle
        this.onSelected = onSelected
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val ctx = requireContext()
        val palette = ReaderSheetPalette.from(ctx)
        val title = seriesTitle?.takeIf { it.isNotBlank() }
            ?: getString(R.string.series_sheet_title)
        val root = ReaderSheetUi.scaffold(
            ctx,
            palette,
            title = title,
            countLabel = null, // 等加载完真实条数再写
        )
        // scaffold 标题行的最右边没有 count 占位，单独再追加一个小的、可后置更新的计数 TextView。
        // 偷懒：把它挂在 list 区域上方的一个空 row 里。
        val density = ctx.resources.displayMetrics.density
        val countRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setPadding((16 * density).toInt(), (4 * density).toInt(), (16 * density).toInt(), (4 * density).toInt())
        }
        countView = TextView(ctx).apply {
            setTextColor(palette.textSecondary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            isVisible = false
        }
        countRow.addView(countView)
        root.addView(countRow)

        val contentArea = android.widget.FrameLayout(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
            ).apply { weight = 1f }
        }
        loadingView = buildLoading(ctx, palette).also { contentArea.addView(it) }
        emptyView = buildEmpty(ctx, palette).also { contentArea.addView(it); it.isVisible = false }
        val list = RecyclerView(ctx).apply {
            layoutManager = LinearLayoutManager(ctx)
            isVisible = false
        }
        contentArea.addView(list)
        listView = list
        root.addView(contentArea)

        if (seriesId == 0L) {
            showEmpty(getString(R.string.series_sheet_empty))
        } else {
            loadSeries(palette)
        }
        return root
    }

    override fun onStart() {
        super.onStart()
        ReaderSheetUi.applyExpandedHeight(this)
    }

    private fun buildLoading(ctx: android.content.Context, palette: ReaderSheetPalette): View {
        val density = ctx.resources.displayMetrics.density
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            addView(ProgressBar(ctx))
            addView(TextView(ctx).apply {
                text = getString(R.string.series_sheet_loading)
                setTextColor(palette.textSecondary)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setPadding(0, (12 * density).toInt(), 0, 0)
            })
        }
    }

    private fun buildEmpty(ctx: android.content.Context, palette: ReaderSheetPalette): TextView {
        val density = ctx.resources.displayMetrics.density
        return TextView(ctx).apply {
            text = getString(R.string.series_sheet_empty)
            setTextColor(palette.textSecondary)
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding((24 * density).toInt(), 0, (24 * density).toInt(), 0)
            layoutParams = android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }
    }

    private fun showEmpty(msg: String) {
        loadingView?.isVisible = false
        listView?.isVisible = false
        emptyView?.text = msg
        emptyView?.isVisible = true
        countView?.isVisible = false
    }

    private fun showList(novels: List<Novel>, palette: ReaderSheetPalette) {
        loadingView?.isVisible = false
        emptyView?.isVisible = false
        countView?.text = getString(R.string.series_sheet_count, novels.size)
        countView?.isVisible = true
        val currentIndex = novels.indexOfFirst { it.id == currentNovelId }
        val list = listView ?: return
        list.isVisible = true
        list.adapter = SeriesAdapter(novels, currentIndex, palette) { novel ->
            onSelected?.invoke(novel)
            dismissAllowingStateLoss()
        }
        if (currentIndex >= 0) {
            list.post { (list.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(currentIndex, 0) }
        }
    }

    private fun loadSeries(palette: ReaderSheetPalette) {
        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val all = mutableListOf<Novel>()
                    var lastOrder: Int? = null
                    for (i in 0 until MAX_PAGES) {
                        val resp = Client.appApi.getNovelSeries(seriesId, lastOrder)
                        resp.novels?.let { all.addAll(it) }
                        if (resp.next_url == null) break
                        lastOrder = all.size
                    }
                    all
                }
            }
            if (!isAdded) return@launch
            result.fold(
                onSuccess = { novels ->
                    if (novels.isEmpty()) {
                        showEmpty(getString(R.string.series_sheet_empty))
                    } else {
                        showList(novels, palette)
                    }
                },
                onFailure = { ex ->
                    Timber.e(ex, "SeriesListSheet load failed series=$seriesId")
                    showEmpty(getString(R.string.series_sheet_load_failed, ex.message ?: ex.javaClass.simpleName))
                },
            )
        }
    }

    private class SeriesAdapter(
        private val novels: List<Novel>,
        private val currentIndex: Int,
        private val palette: ReaderSheetPalette,
        private val onClick: (Novel) -> Unit,
    ) : RecyclerView.Adapter<SeriesAdapter.VH>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val ctx = parent.context
            val density = ctx.resources.displayMetrics.density
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    (60 * density).toInt(),
                )
                setPadding((20 * density).toInt(), 0, (16 * density).toInt(), 0)
                applySelectableBackground()
            }
            val index = TextView(ctx).apply {
                setTextColor(palette.textSecondary)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                minWidth = (32 * density).toInt()
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
            }
            val title = TextView(ctx).apply {
                setTextColor(palette.textPrimary)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    weight = 1f
                    leftMargin = (12 * density).toInt()
                }
            }
            val currentBadge = TextView(ctx).apply {
                text = ctx.getString(R.string.series_sheet_chip_current)
                setTextColor(0xFFFFFFFF.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 999f
                    setColor(palette.accent)
                }
                setPadding((10 * density).toInt(), (3 * density).toInt(), (10 * density).toInt(), (3 * density).toInt())
                isVisible = false
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { leftMargin = (8 * density).toInt() }
            }
            row.addView(index)
            row.addView(title)
            row.addView(currentBadge)
            return VH(row, index, title, currentBadge)
        }

        override fun getItemCount(): Int = novels.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val novel = novels[position]
            val isCurrent = position == currentIndex
            holder.index.text = "${position + 1}"
            holder.title.text = novel.title.orEmpty()
            holder.title.setTypeface(holder.title.typeface, if (isCurrent) Typeface.BOLD else Typeface.NORMAL)
            holder.title.setTextColor(if (isCurrent) palette.accent else palette.textPrimary)
            holder.index.setTextColor(if (isCurrent) palette.accent else palette.textSecondary)
            holder.currentBadge.isVisible = isCurrent
            holder.itemView.setOnClickListener {
                if (!isCurrent) onClick(novel)
            }
        }

        class VH(
            itemView: View,
            val index: TextView,
            val title: TextView,
            val currentBadge: TextView,
        ) : RecyclerView.ViewHolder(itemView)
    }

    companion object {
        const val TAG = "SeriesListSheet"
        private const val MAX_PAGES = 5 // 30/页 × 5 = 150 篇上限，避免极端长系列拖死
    }
}
