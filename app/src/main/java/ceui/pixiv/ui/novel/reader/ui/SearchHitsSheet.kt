package ceui.pixiv.ui.novel.reader.ui

import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ceui.lisa.R
import ceui.lisa.databinding.ItemReaderSearchHitRowBinding
import ceui.lisa.databinding.SheetReaderSearchHitsBinding
import ceui.pixiv.ui.novel.reader.model.SearchHit
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

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
        val binding = SheetReaderSearchHitsBinding.inflate(inflater, container, false)
        binding.title.text = getString(R.string.search_hits_title)
        binding.count.text = getString(R.string.search_hits_count, hits.size)
        val surfaceSubtle = ContextCompat.getColor(requireContext(), R.color.v3_surface_1)
        binding.list.layoutManager = LinearLayoutManager(requireContext())
        binding.list.adapter = Adapter(hits, query, currentIndex, surfaceSubtle, onJumpTo) { dismissAllowingStateLoss() }
        listView = binding.list
        return binding.root
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
        private val surfaceSubtle: Int,
        private val onJumpTo: ((SearchHit, Int) -> Unit)?,
        private val dismiss: () -> Unit,
    ) : RecyclerView.Adapter<Adapter.VH>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val binding = ItemReaderSearchHitRowBinding.inflate(
                LayoutInflater.from(parent.context), parent, false,
            )
            return VH(binding)
        }

        override fun getItemCount(): Int = hits.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val hit = hits[position]
            holder.binding.index.text = "${position + 1}"

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
            holder.binding.snippet.text = spannable

            if (position == currentIndex) {
                holder.itemView.setBackgroundColor(surfaceSubtle)
            } else {
                holder.itemView.background = null
                holder.itemView.setBackgroundResource(
                    android.util.TypedValue().also {
                        holder.itemView.context.theme.resolveAttribute(
                            android.R.attr.selectableItemBackground, it, true,
                        )
                    }.resourceId,
                )
            }

            holder.itemView.setOnClickListener {
                onJumpTo?.invoke(hit, position)
                dismiss()
            }
        }

        class VH(val binding: ItemReaderSearchHitRowBinding) : RecyclerView.ViewHolder(binding.root)
    }

    companion object {
        const val TAG = "SearchHitsSheet"
        private const val COLOR_HIT_HIGHLIGHT = 0xAAFF9800.toInt()
    }
}
