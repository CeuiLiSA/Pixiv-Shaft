package ceui.pixiv.ui.novel

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
import ceui.lisa.R
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

/**
 * FragmentNovelSeries 的「下载」入口 BottomSheet，提供三选一：
 *
 *  - 选择下载：多选系列 → 每个系列各自一个文件
 *  - 全部下载：所有系列 → 每个系列各自一个文件
 *  - 合并下载：所有系列 → 合并成单个文件
 *
 * 颜色沿用 V3 palette（v3_bg / v3_surface_* / v3_text_*），与
 * ReaderSheetPalette 保持一致的观感（最近 commit 把 Chapter/Series/SearchHits
 * 三个 sheet 统一到 V3 配色，这里也走同一调）。
 */
class CrossSeriesDownloadOptionsSheet : BottomSheetDialogFragment() {

    enum class Option { Pick, All, Merge }

    private var onPicked: ((Option) -> Unit)? = null

    fun configure(onPicked: (Option) -> Unit) {
        this.onPicked = onPicked
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val ctx = requireContext()
        val density = ctx.resources.displayMetrics.density
        val palette = CrossSeriesSheetPalette.from(ctx)

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(palette.background)
            setPadding(0, (12 * density).toInt(), 0, (16 * density).toInt())
        }

        // Grab handle
        val handle = FrameLayout(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (18 * density).toInt(),
            )
        }
        val indicator = View(ctx).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 2 * density
                setColor(palette.handle)
            }
            layoutParams = FrameLayout.LayoutParams(
                (40 * density).toInt(), (4 * density).toInt(), Gravity.CENTER
            )
        }
        handle.addView(indicator)
        root.addView(handle)

        // Title
        val title = TextView(ctx).apply {
            text = getString(R.string.cross_series_download_options_title)
            setTextColor(palette.textPrimary)
            setTypeface(typeface, Typeface.BOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            setPadding(
                (16 * density).toInt(), 0,
                (16 * density).toInt(), (8 * density).toInt(),
            )
        }
        root.addView(title)

        // Divider
        root.addView(View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
            setBackgroundColor(palette.divider)
        })

        // 顺序：从左到右在 UI 需求里是 选择/全部/合并；在垂直列表里沿用同一顺序。
        val rows = listOf(
            Triple(
                Option.Pick,
                getString(R.string.cross_series_download_pick),
                getString(R.string.cross_series_download_pick_desc),
            ),
            Triple(
                Option.All,
                getString(R.string.cross_series_download_all),
                getString(R.string.cross_series_download_all_desc),
            ),
            Triple(
                Option.Merge,
                getString(R.string.cross_series_download_merge),
                getString(R.string.cross_series_download_merge_desc),
            ),
        )
        for ((option, rowTitle, rowDesc) in rows) {
            root.addView(buildRow(ctx, palette, rowTitle, rowDesc, density, option))
        }
        return root
    }

    private fun buildRow(
        ctx: android.content.Context,
        palette: CrossSeriesSheetPalette,
        rowTitle: String,
        rowDesc: String,
        density: Float,
        option: Option,
    ): View {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            setPadding(
                (16 * density).toInt(), (14 * density).toInt(),
                (16 * density).toInt(), (14 * density).toInt(),
            )
            isClickable = true
            isFocusable = true
            // Simple press state drawable without touching system theme attrs
            background = makeRipple(palette)
        }
        val titleView = TextView(ctx).apply {
            text = rowTitle
            setTextColor(palette.textPrimary)
            setTypeface(typeface, Typeface.BOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        }
        val descView = TextView(ctx).apply {
            text = rowDesc
            setTextColor(palette.textSecondary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        }
        row.addView(titleView)
        row.addView(descView)
        row.setOnClickListener {
            onPicked?.invoke(option)
            dismissAllowingStateLoss()
        }
        return row
    }

    private fun makeRipple(palette: CrossSeriesSheetPalette): android.graphics.drawable.Drawable {
        val pressed = GradientDrawable().apply { setColor(palette.surfaceSubtle) }
        val normal = GradientDrawable().apply { setColor(palette.background) }
        val states = android.graphics.drawable.StateListDrawable()
        states.addState(intArrayOf(android.R.attr.state_pressed), pressed)
        states.addState(intArrayOf(), normal)
        return states
    }

    companion object {
        const val TAG = "CrossSeriesDownloadOptionsSheet"
    }
}

/**
 * Copy of ReaderSheetPalette tailored for this sheet — kept separate so we
 * don't reach across into the reader package's `internal` type. Palette
 * tokens mirror the V3 sheet unification pass (chapter / series / search-hits
 * sheets share these colors).
 */
internal data class CrossSeriesSheetPalette(
    val background: Int,
    val surfaceSubtle: Int,
    val divider: Int,
    val handle: Int,
    val textPrimary: Int,
    val textSecondary: Int,
) {
    companion object {
        fun from(ctx: android.content.Context): CrossSeriesSheetPalette {
            return CrossSeriesSheetPalette(
                background = ContextCompat.getColor(ctx, R.color.v3_bg),
                surfaceSubtle = ContextCompat.getColor(ctx, R.color.v3_surface_1),
                divider = ContextCompat.getColor(ctx, R.color.v3_surface_2),
                handle = ContextCompat.getColor(ctx, R.color.v3_surface_3),
                textPrimary = ContextCompat.getColor(ctx, R.color.v3_text_1),
                textSecondary = ContextCompat.getColor(ctx, R.color.v3_text_3),
            )
        }
    }
}
