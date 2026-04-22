package ceui.pixiv.ui.novel.reader.ui

import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.style.BackgroundColorSpan
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
import ceui.lisa.R
import ceui.pixiv.ui.novel.reader.model.SearchHit
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

class SearchHitsSheet : BottomSheetDialogFragment() {

    private var hits: List<SearchHit> = emptyList()
    private var query: String = ""
    private var currentIndex: Int = -1
    private var onJumpTo: ((SearchHit, Int) -> Unit)? = null

    fun configure(
        hits: List<SearchHit>,
        query: String,
        currentIndex: Int,
        onJumpTo: (SearchHit, Int) -> Unit,
    ) {
        this.hits = hits
        this.query = query
        this.currentIndex = currentIndex
        this.onJumpTo = onJumpTo
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val ctx = requireContext()
        val density = ctx.resources.displayMetrics.density
        val bgColor = ContextCompat.getColor(ctx, R.color.v3_bg)
        val text1 = ContextCompat.getColor(ctx, R.color.v3_text_1)
        val text3 = ContextCompat.getColor(ctx, R.color.v3_text_3)
        val dividerColor = ContextCompat.getColor(ctx, R.color.v3_surface_2)

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bgColor)
            setPadding(0, (12 * density).toInt(), 0, (8 * density).toInt())
        }

        val handle = FrameLayout(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (18 * density).toInt())
        }
        val indicator = View(ctx).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 2 * density
                setColor(ContextCompat.getColor(ctx, R.color.v3_surface_3))
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
        val title = TextView(ctx).apply {
            text = getString(R.string.search_hits_title)
            setTextColor(text1)
            setTypeface(typeface, Typeface.BOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT).apply { weight = 1f }
        }
        val count = TextView(ctx).apply {
            text = getString(R.string.search_hits_count, hits.size)
            setTextColor(text3)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        }
        titleRow.addView(title)
        titleRow.addView(count)
        root.addView(titleRow)

        root.addView(View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
            setBackgroundColor(dividerColor)
        })

        val list = RecyclerView(ctx).apply {
            layoutManager = LinearLayoutManager(ctx)
            adapter = Adapter(hits, query, currentIndex, onJumpTo) { dismissAllowingStateLoss() }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0,
            ).apply { weight = 1f }
        }
        root.addView(list)

        return root
    }

    override fun onStart() {
        super.onStart()
        val dialog = dialog as? BottomSheetDialog ?: return
        val sheet = dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet) ?: return
        val displayHeight = resources.displayMetrics.heightPixels
        val targetHeight = (displayHeight * 0.7f).toInt()
        sheet.layoutParams = sheet.layoutParams.apply { height = targetHeight }
        sheet.setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.v3_bg))
        BottomSheetBehavior.from(sheet).apply {
            peekHeight = targetHeight
            state = BottomSheetBehavior.STATE_EXPANDED
            skipCollapsed = true
        }
        if (currentIndex in hits.indices) {
            view?.post {
                val rv = (view as? LinearLayout)?.getChildAt((view as LinearLayout).childCount - 1) as? RecyclerView
                (rv?.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(currentIndex, 0)
            }
        }
    }

    private class Adapter(
        private val hits: List<SearchHit>,
        private val query: String,
        private val currentIndex: Int,
        private val onJumpTo: ((SearchHit, Int) -> Unit)?,
        private val dismiss: () -> Unit,
    ) : RecyclerView.Adapter<Adapter.VH>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val ctx = parent.context
            val density = ctx.resources.displayMetrics.density
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
                setPadding((16 * density).toInt(), (12 * density).toInt(), (16 * density).toInt(), (12 * density).toInt())
                applySelectableBackground()
            }
            val index = TextView(ctx).apply {
                setTextColor(ContextCompat.getColor(ctx, R.color.v3_text_3))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                minWidth = (32 * density).toInt()
            }
            val snippet = TextView(ctx).apply {
                setTextColor(ContextCompat.getColor(ctx, R.color.v3_text_1))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    weight = 1f
                    marginStart = (8 * density).toInt()
                }
            }
            row.addView(index)
            row.addView(snippet)
            return VH(row, index, snippet)
        }

        override fun getItemCount(): Int = hits.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val hit = hits[position]
            val ctx = holder.itemView.context
            holder.index.text = "${position + 1}"

            val snippetText = hit.snippet
            val spannable = SpannableString(snippetText)
            if (query.isNotEmpty()) {
                var searchFrom = 0
                val lowerSnippet = snippetText.lowercase()
                val lowerQuery = query.lowercase()
                while (true) {
                    val idx = lowerSnippet.indexOf(lowerQuery, searchFrom)
                    if (idx < 0) break
                    spannable.setSpan(
                        BackgroundColorSpan(COLOR_HIT_HIGHLIGHT),
                        idx, idx + query.length,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                    searchFrom = idx + query.length
                }
            }
            holder.snippet.text = spannable

            if (position == currentIndex) {
                holder.itemView.setBackgroundColor(ContextCompat.getColor(ctx, R.color.v3_surface_1))
            } else {
                holder.itemView.background = null
                holder.itemView.applySelectableBackground()
            }

            holder.itemView.setOnClickListener {
                onJumpTo?.invoke(hit, position)
                dismiss()
            }
        }

        class VH(itemView: View, val index: TextView, val snippet: TextView) : RecyclerView.ViewHolder(itemView)
    }

    companion object {
        const val TAG = "SearchHitsSheet"
        private const val COLOR_HIT_HIGHLIGHT = 0xAAFF9800.toInt()
    }
}
