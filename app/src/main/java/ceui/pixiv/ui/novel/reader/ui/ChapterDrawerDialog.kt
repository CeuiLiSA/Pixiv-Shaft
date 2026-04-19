package ceui.pixiv.ui.novel.reader.ui

import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ceui.pixiv.ui.novel.reader.paginate.ChapterOutlineEntry

/**
 * Slides in from the left to show the chapter outline. Tap an entry to jump;
 * tap the backdrop or swipe left to dismiss. Chapter list + callbacks are
 * provided by the host Fragment via [configure] right before [show].
 */
class ChapterDrawerDialog : DialogFragment() {

    private var chapters: List<ChapterOutlineEntry> = emptyList()
    private var currentSourceStart: Int = 0
    private var onSelected: ((ChapterOutlineEntry) -> Unit)? = null

    fun configure(
        chapters: List<ChapterOutlineEntry>,
        currentSourceStart: Int,
        onSelected: (ChapterOutlineEntry) -> Unit,
    ) {
        this.chapters = chapters
        this.currentSourceStart = currentSourceStart
        this.onSelected = onSelected
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        return Dialog(requireContext(), android.R.style.Theme_Translucent_NoTitleBar).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
        }
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
        }

        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((16 * density).toInt(), (20 * density).toInt(), (16 * density).toInt(), (16 * density).toInt())
        }
        val title = TextView(ctx).apply {
            text = "目录"
            setTextColor(Color.BLACK)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT).apply { weight = 1f }
        }
        val count = TextView(ctx).apply {
            text = "${chapters.size} 章"
            setTextColor(0xFF888888.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        }
        header.addView(title)
        header.addView(count)
        root.addView(header)

        val divider = View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
            setBackgroundColor(0x1F000000)
        }
        root.addView(divider)

        val list = RecyclerView(ctx).apply {
            layoutManager = LinearLayoutManager(ctx)
            adapter = ChapterAdapter(chapters, currentSourceStart) { entry ->
                onSelected?.invoke(entry)
                dismissAllowingStateLoss()
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
            ).apply { weight = 1f }
        }
        root.addView(list)

        return root
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.apply {
            setBackgroundDrawable(ColorDrawable(0x66000000))
            setLayout(
                (requireContext().resources.displayMetrics.widthPixels * 0.8f).toInt(),
                WindowManager.LayoutParams.MATCH_PARENT,
            )
            setGravity(Gravity.START)
            setWindowAnimations(android.R.style.Animation_Translucent)
        }
    }

    private class ChapterAdapter(
        private val entries: List<ChapterOutlineEntry>,
        private val currentSourceStart: Int,
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
            val isCurrent = isCurrentChapter(position)
            tv.setTextColor(if (isCurrent) 0xFF5B6EFF.toInt() else 0xFF222222.toInt())
            tv.setTypeface(tv.typeface, if (isCurrent) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
            tv.setOnClickListener { onClick(entry) }
        }

        private fun isCurrentChapter(index: Int): Boolean {
            val here = entries[index]
            val next = entries.getOrNull(index + 1)
            return currentSourceStart >= here.sourceStart && (next == null || currentSourceStart < next.sourceStart)
        }

        class VH(itemView: View) : RecyclerView.ViewHolder(itemView)
    }

    companion object {
        const val TAG = "ChapterDrawerDialog"
    }
}
