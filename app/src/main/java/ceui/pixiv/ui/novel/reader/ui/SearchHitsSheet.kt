package ceui.pixiv.ui.novel.reader.ui

import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ceui.lisa.R
import ceui.lisa.databinding.ItemReaderSearchHitRowBinding
import ceui.lisa.databinding.SheetReaderSearchHitsBinding
import ceui.pixiv.ui.novel.reader.NovelReaderV3ViewModel
import ceui.pixiv.ui.novel.reader.model.SearchHit
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

interface SearchHitSheetCallback {
    fun onSearchHitSelected(hit: SearchHit, index: Int)
}

class SearchHitsSheet : BottomSheetDialogFragment() {

    private var _binding: SheetReaderSearchHitsBinding? = null
    private val binding get() = _binding!!

    private val query: String by lazy {
        requireArguments().getString(ARG_QUERY, "")
    }

    private val readerViewModel: NovelReaderV3ViewModel by lazy {
        ViewModelProvider(requireParentFragment())[NovelReaderV3ViewModel::class.java]
    }

    private var listView: RecyclerView? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = SheetReaderSearchHitsBinding.inflate(inflater, container, false)
        binding.title.text = getString(R.string.search_hits_title)

        val result = readerViewModel.searchResult.value ?: NovelReaderV3ViewModel.SearchResult.EMPTY
        val hits = result.hits
        val currentIndex = result.currentIndex

        binding.count.text = getString(R.string.search_hits_count, hits.size)
        val surfaceSubtle = ContextCompat.getColor(requireContext(), R.color.v3_surface_1)
        binding.list.layoutManager = LinearLayoutManager(requireContext())
        binding.list.adapter = Adapter(hits, query, currentIndex, surfaceSubtle, parentFragment as? SearchHitSheetCallback) {
            dismissAllowingStateLoss()
        }
        listView = binding.list
        return binding.root
    }

    override fun onStart() {
        super.onStart()
        ReaderSheetUi.applyExpandedHeight(this)
        val result = readerViewModel.searchResult.value
        val currentIndex = result?.currentIndex ?: -1
        val hits = result?.hits.orEmpty()
        if (currentIndex in hits.indices) {
            listView?.post {
                val lm = listView?.layoutManager as? LinearLayoutManager ?: return@post
                lm.scrollToPositionWithOffset(currentIndex, 0)
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
        listView = null
    }

    private class Adapter(
        private val hits: List<SearchHit>,
        private val query: String,
        private val currentIndex: Int,
        private val surfaceSubtle: Int,
        private val callback: SearchHitSheetCallback?,
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
                callback?.onSearchHitSelected(hit, position)
                dismiss()
            }
        }

        class VH(val binding: ItemReaderSearchHitRowBinding) : RecyclerView.ViewHolder(binding.root)
    }

    companion object {
        const val TAG = "SearchHitsSheet"
        private const val ARG_QUERY = "query"
        private const val COLOR_HIT_HIGHLIGHT = 0xAAFF9800.toInt()

        fun newInstance(query: String) = SearchHitsSheet().apply {
            arguments = Bundle().apply {
                putString(ARG_QUERY, query)
            }
        }
    }
}
