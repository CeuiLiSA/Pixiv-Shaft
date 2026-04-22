package ceui.pixiv.ui.novel.reader.ui

import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ceui.lisa.R
import ceui.pixiv.ui.novel.reader.model.SearchHit
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

/**
 * 搜索结果 bottom sheet。与 [ChapterListSheet] / [SeriesListSheet] 共用
 * [ReaderSheetUi] / [ReaderSheetPalette] 骨架，颜色全部走 V3 token，白天/夜间自动适配。
 */
class SearchHitsSheet : BottomSheetDialogFragment() {

    private var hits: List<SearchHit> = emptyList()
    private var query: String = ""
    private var currentIndex: Int = -1
    private var onJumpTo: ((SearchHit, Int) -> Unit)? = null

    private var listView: RecyclerView? = null

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
        val palette = ReaderSheetPalette.from(ctx)
        val root = ReaderSheetUi.scaffold(
            ctx,
            palette,
            title = getString(R.string.search_hits_title),
            countLabel = getString(R.string.search_hits_count, hits.size),
        )

        val list = RecyclerView(ctx).apply {
            layoutManager = LinearLayoutManager(ctx)
            adapter = Adapter(hits, query, currentIndex, palette, onJumpTo) { dismissAllowingStateLoss() }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0,
            ).apply { weight = 1f }
        }
        listView = list
        root.addView(list)
        return root
    }

    override fun onStart() {
        super.onStart()
        ReaderSheetUi.applyExpandedHeight(this)
        if (currentIndex in hits.indices) {
            listView?.post {
                val lm = listView?.layoutManager as? LinearLayoutManager ?: return@post
                lm.scrollToPositionWithOffset(currentIndex, 0)
            }
        }
    }

    private class Adapter(
        private val hits: List<SearchHit>,
        private val query: String,
        private val currentIndex: Int,
        private val palette: ReaderSheetPalette,
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
                setTextColor(palette.textSecondary)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                minWidth = (32 * density).toInt()
            }
            val snippet = TextView(ctx).apply {
                setTextColor(palette.textPrimary)
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
                holder.itemView.setBackgroundColor(palette.surfaceSubtle)
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
        // 沿用 ReaderSearchOverlay 的命中底色（半透明橙），白天/夜间在浅/深底上都视觉合适。
        private const val COLOR_HIT_HIGHLIGHT = 0xAAFF9800.toInt()
    }
}
