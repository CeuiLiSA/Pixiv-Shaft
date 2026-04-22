package ceui.pixiv.ui.novel.reader.ui

import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ceui.pixiv.ui.novel.reader.paginate.ChapterOutlineEntry
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

/**
 * 章节目录 bottom sheet。点击章节跳转并关闭 sheet；打开时滚动到当前章节。
 *
 * 颜色全部走 V3 token（v3_bg / text00 / text20 / v3_border_2 / colorPrimary），
 * 白天 / 夜间自动适配；与 SeriesListSheet 共用样式约定，详见 ReaderSheetPalette。
 */
class ChapterListSheet : BottomSheetDialogFragment() {

    private var chapters: List<ChapterOutlineEntry> = emptyList()
    private var currentSourceStart: Int = 0
    private var onSelected: ((ChapterOutlineEntry) -> Unit)? = null

    private var listView: RecyclerView? = null
    private var currentIndex: Int = -1

    fun configure(
        chapters: List<ChapterOutlineEntry>,
        currentSourceStart: Int,
        onSelected: (ChapterOutlineEntry) -> Unit,
    ) {
        this.chapters = chapters
        this.currentSourceStart = currentSourceStart
        this.onSelected = onSelected
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val ctx = requireContext()
        val palette = ReaderSheetPalette.from(ctx)
        val root = ReaderSheetUi.scaffold(
            ctx,
            palette,
            title = getString(ceui.lisa.R.string.chapters_title),
            countLabel = getString(ceui.lisa.R.string.chapters_count, chapters.size),
        )

        currentIndex = resolveCurrentIndex()
        val list = RecyclerView(ctx).apply {
            layoutManager = LinearLayoutManager(ctx)
            adapter = ChapterAdapter(chapters, currentIndex, palette) { entry ->
                onSelected?.invoke(entry)
                dismissAllowingStateLoss()
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
            ).apply { weight = 1f }
        }
        root.addView(list)
        listView = list
        return root
    }

    override fun onStart() {
        super.onStart()
        ReaderSheetUi.applyExpandedHeight(this)
        listView?.post {
            val lm = listView?.layoutManager as? LinearLayoutManager ?: return@post
            if (currentIndex >= 0) lm.scrollToPositionWithOffset(currentIndex, 0)
        }
    }

    private fun resolveCurrentIndex(): Int {
        if (chapters.isEmpty()) return -1
        for (i in chapters.indices) {
            val here = chapters[i]
            val next = chapters.getOrNull(i + 1)
            if (currentSourceStart >= here.sourceStart && (next == null || currentSourceStart < next.sourceStart)) {
                return i
            }
        }
        return 0
    }

    private class ChapterAdapter(
        private val entries: List<ChapterOutlineEntry>,
        private val currentIndex: Int,
        private val palette: ReaderSheetPalette,
        private val onClick: (ChapterOutlineEntry) -> Unit,
    ) : RecyclerView.Adapter<ChapterAdapter.VH>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val ctx = parent.context
            val density = ctx.resources.displayMetrics.density
            val view = TextView(ctx).apply {
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    (52 * density).toInt(),
                )
                setPadding((20 * density).toInt(), 0, (16 * density).toInt(), 0)
                gravity = Gravity.CENTER_VERTICAL
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                applySelectableBackground()
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            return VH(view)
        }

        override fun getItemCount(): Int = entries.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val entry = entries[position]
            val tv = holder.itemView as TextView
            tv.text = entry.title
            val isCurrent = position == currentIndex
            tv.setTextColor(if (isCurrent) palette.accent else palette.textPrimary)
            tv.setTypeface(tv.typeface, if (isCurrent) Typeface.BOLD else Typeface.NORMAL)
            tv.setOnClickListener { onClick(entry) }
        }

        class VH(itemView: View) : RecyclerView.ViewHolder(itemView)
    }

    companion object {
        const val TAG = "ChapterListSheet"
    }
}

/**
 * 共享给 ChapterListSheet / SeriesListSheet / SearchHitsSheet 的颜色 token，
 * 全部从 V3 detail-page 调色板（v3_*）取值，白天/夜间自动适配。sheet 内不再写死颜色。
 *
 * 字段 → token 映射：
 *   background     → v3_bg
 *   surfaceSubtle  → v3_surface_1   （当前命中/选中态的 row 浅底）
 *   divider        → v3_surface_2   （标题栏下方分割线，比 border_2 略明显）
 *   handle         → v3_surface_3   （sheet 顶部 grab handle 颜色）
 *   textPrimary    → v3_text_1
 *   textSecondary  → v3_text_3
 *   accent         → ?colorPrimary  （高亮 + 跳转色）
 */
internal data class ReaderSheetPalette(
    val background: Int,
    val surfaceSubtle: Int,
    val divider: Int,
    val handle: Int,
    val textPrimary: Int,
    val textSecondary: Int,
    val accent: Int,
) {
    companion object {
        fun from(ctx: android.content.Context): ReaderSheetPalette {
            val tv = TypedValue()
            ctx.theme.resolveAttribute(androidx.appcompat.R.attr.colorPrimary, tv, true)
            val accent = tv.data
            return ReaderSheetPalette(
                background = ContextCompat.getColor(ctx, ceui.lisa.R.color.v3_bg),
                surfaceSubtle = ContextCompat.getColor(ctx, ceui.lisa.R.color.v3_surface_1),
                divider = ContextCompat.getColor(ctx, ceui.lisa.R.color.v3_surface_2),
                handle = ContextCompat.getColor(ctx, ceui.lisa.R.color.v3_surface_3),
                textPrimary = ContextCompat.getColor(ctx, ceui.lisa.R.color.v3_text_1),
                textSecondary = ContextCompat.getColor(ctx, ceui.lisa.R.color.v3_text_3),
                accent = accent,
            )
        }
    }
}

/**
 * 共享 sheet 骨架：顶部 grab handle + 标题行 + 分割线。
 * 子 sheet 自己往返回的 LinearLayout 里 add 内容（列表 / loading / empty 等）。
 */
internal object ReaderSheetUi {

    fun scaffold(
        ctx: android.content.Context,
        palette: ReaderSheetPalette,
        title: String,
        countLabel: String? = null,
    ): LinearLayout {
        val density = ctx.resources.displayMetrics.density
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(palette.background)
            setPadding(0, (12 * density).toInt(), 0, (8 * density).toInt())
        }

        val handle = FrameLayout(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (18 * density).toInt())
        }
        val indicator = View(ctx).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 2 * density
                setColor(palette.handle)
            }
            layoutParams = FrameLayout.LayoutParams((40 * density).toInt(), (4 * density).toInt(), Gravity.CENTER)
        }
        handle.addView(indicator)
        root.addView(handle)

        val titleRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((16 * density).toInt(), 0, (16 * density).toInt(), (8 * density).toInt())
        }
        val titleView = TextView(ctx).apply {
            text = title
            setTextColor(palette.textPrimary)
            setTypeface(typeface, Typeface.BOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT).apply { weight = 1f }
        }
        titleRow.addView(titleView)
        if (countLabel != null) {
            val countView = TextView(ctx).apply {
                text = countLabel
                setTextColor(palette.textSecondary)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            }
            titleRow.addView(countView)
        }
        root.addView(titleRow)

        root.addView(View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
            setBackgroundColor(palette.divider)
        })
        return root
    }

    fun applyExpandedHeight(fragment: BottomSheetDialogFragment, fraction: Float = 0.7f) {
        val dialog = fragment.dialog as? BottomSheetDialog ?: return
        val sheet = dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet) ?: return
        val displayHeight = fragment.resources.displayMetrics.heightPixels
        val targetHeight = (displayHeight * fraction).toInt()
        sheet.layoutParams = sheet.layoutParams.apply { height = targetHeight }
        BottomSheetBehavior.from(sheet).apply {
            peekHeight = targetHeight
            state = BottomSheetBehavior.STATE_EXPANDED
            skipCollapsed = true
        }
    }
}
