package ceui.pixiv.ui.novel.reader.ui

import android.graphics.Color
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
import ceui.pixiv.ui.novel.reader.export.ExportFormat
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

/**
 * BottomSheet that lets the user pick an export format. Rows describe the
 * format's tradeoffs in one line so they can choose without digging into docs.
 */
class ExportSheet : BottomSheetDialogFragment() {

    private var onFormatChosen: ((ExportFormat) -> Unit)? = null

    fun configure(onFormatChosen: (ExportFormat) -> Unit) {
        this.onFormatChosen = onFormatChosen
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val ctx = requireContext()
        val density = ctx.resources.displayMetrics.density

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(0, (12 * density).toInt(), 0, (24 * density).toInt())
        }

        val handle = FrameLayout(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (18 * density).toInt())
        }
        val indicator = View(ctx).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 2 * density
                setColor(0x33000000)
            }
            layoutParams = FrameLayout.LayoutParams((40 * density).toInt(), (4 * density).toInt(), Gravity.CENTER)
        }
        handle.addView(indicator)
        root.addView(handle)

        val title = TextView(ctx).apply {
            text = "导出格式"
            setTextColor(Color.BLACK)
            setTypeface(typeface, Typeface.BOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            setPadding((16 * density).toInt(), 0, (16 * density).toInt(), (8 * density).toInt())
        }
        root.addView(title)

        val divider = View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
            setBackgroundColor(0x1F000000)
        }
        root.addView(divider)

        val rows = listOf(
            Triple(ExportFormat.Txt, "最精简，适合粘贴到任何地方", "🅣"),
            Triple(ExportFormat.Markdown, "保留标题与段落，图片用链接引用", "🅜"),
            Triple(ExportFormat.Epub, "标准电子书，含嵌入图片，可在阅读器打开", "📖"),
            Triple(ExportFormat.Pdf, "A4 纸张，方便打印或归档（暂不含图片）", "📄"),
        )
        for ((format, desc, emoji) in rows) {
            root.addView(buildRow(ctx, format, desc, emoji, density))
        }
        return root
    }

    private fun buildRow(
        ctx: android.content.Context,
        format: ExportFormat,
        desc: String,
        emoji: String,
        density: Float,
    ): View {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            setPadding((16 * density).toInt(), (14 * density).toInt(), (16 * density).toInt(), (14 * density).toInt())
            applySelectableBackground()
        }
        val icon = TextView(ctx).apply {
            text = emoji
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { marginEnd = (16 * density).toInt() }
        }
        val textBlock = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT).apply { weight = 1f }
        }
        val title = TextView(ctx).apply {
            text = format.displayName
            setTextColor(Color.BLACK)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTypeface(typeface, Typeface.BOLD)
        }
        val subtitle = TextView(ctx).apply {
            text = desc
            setTextColor(0xFF888888.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        }
        textBlock.addView(title)
        textBlock.addView(subtitle)
        row.addView(icon)
        row.addView(textBlock)
        row.setOnClickListener {
            onFormatChosen?.invoke(format)
            dismissAllowingStateLoss()
        }
        return row
    }

    companion object {
        const val TAG = "ExportSheet"
    }
}
