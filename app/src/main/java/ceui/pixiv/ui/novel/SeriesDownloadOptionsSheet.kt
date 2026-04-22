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
 * 系列详情页下载入口的三选一底部 sheet：
 *  - [Action.Picker]        选择下载：进入多选模式，逐章独立下载
 *  - [Action.AllSeparate]   批量下载：全部章节分别下载
 *  - [Action.MergeOne]      合并下载：整个系列合并成一个文件
 *
 * 视觉风格对齐 ChapterListSheet / SeriesListSheet / SearchHitsSheet（V3 白天/夜间
 * 自适配）。ReaderSheetPalette / ReaderSheetUi 是 reader 包的 internal 工具，这里
 * 不能跨包复用，所以直接取 v3_* color 资源重建一次。
 */
class SeriesDownloadOptionsSheet : BottomSheetDialogFragment() {

    enum class Action { Picker, AllSeparate, MergeOne }

    private var onChoose: ((Action) -> Unit)? = null

    fun configure(onChoose: (Action) -> Unit) {
        this.onChoose = onChoose
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val ctx = requireContext()
        val density = ctx.resources.displayMetrics.density

        val bg = ContextCompat.getColor(ctx, R.color.v3_bg)
        val divider = ContextCompat.getColor(ctx, R.color.v3_surface_2)
        val handleColor = ContextCompat.getColor(ctx, R.color.v3_surface_3)
        val textPrimary = ContextCompat.getColor(ctx, R.color.v3_text_1)
        val textSecondary = ContextCompat.getColor(ctx, R.color.v3_text_3)

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            setPadding(0, (12 * density).toInt(), 0, (24 * density).toInt())
        }

        // grab handle
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
                setColor(handleColor)
            }
            layoutParams = FrameLayout.LayoutParams(
                (40 * density).toInt(), (4 * density).toInt(), Gravity.CENTER,
            )
        }
        handle.addView(indicator)
        root.addView(handle)

        val title = TextView(ctx).apply {
            text = getString(R.string.series_download_options_title)
            setTextColor(textPrimary)
            setTypeface(typeface, Typeface.BOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            setPadding(
                (16 * density).toInt(), 0,
                (16 * density).toInt(), (8 * density).toInt(),
            )
        }
        root.addView(title)

        root.addView(View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
            setBackgroundColor(divider)
        })

        val rows = listOf(
            Row(
                Action.Picker,
                getString(R.string.series_download_picker),
                getString(R.string.series_download_picker_desc),
                "🗹", // ballot box with check
            ),
            Row(
                Action.AllSeparate,
                getString(R.string.series_download_all_separate),
                getString(R.string.series_download_all_separate_desc),
                "📦", // package
            ),
            Row(
                Action.MergeOne,
                getString(R.string.series_download_merge_one),
                getString(R.string.series_download_merge_one_desc),
                "📚", // books
            ),
        )
        for (row in rows) {
            root.addView(buildRow(ctx, density, row, textPrimary, textSecondary))
        }
        return root
    }

    private data class Row(
        val action: Action,
        val title: String,
        val desc: String,
        val emoji: String,
    )

    private fun buildRow(
        ctx: android.content.Context,
        density: Float,
        row: Row,
        textPrimary: Int,
        textSecondary: Int,
    ): View {
        val rowView = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            setPadding(
                (16 * density).toInt(), (14 * density).toInt(),
                (16 * density).toInt(), (14 * density).toInt(),
            )
            // selectableItemBackground ripple
            val tv = TypedValue()
            context.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
            if (tv.resourceId != 0) setBackgroundResource(tv.resourceId)
        }
        val icon = TextView(ctx).apply {
            text = row.emoji
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { marginEnd = (16 * density).toInt() }
        }
        val textBlock = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                weight = 1f
            }
        }
        val titleTv = TextView(ctx).apply {
            text = row.title
            setTextColor(textPrimary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTypeface(typeface, Typeface.BOLD)
        }
        val descTv = TextView(ctx).apply {
            text = row.desc
            setTextColor(textSecondary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        }
        textBlock.addView(titleTv)
        textBlock.addView(descTv)
        rowView.addView(icon)
        rowView.addView(textBlock)
        rowView.setOnClickListener {
            onChoose?.invoke(row.action)
            dismissAllowingStateLoss()
        }
        return rowView
    }

    companion object {
        const val TAG = "SeriesDownloadOptionsSheet"
    }
}
