package ceui.pixiv.ui.novel.reader.ui

import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ceui.lisa.R
import ceui.lisa.databinding.ItemReaderSeriesRowBinding
import ceui.lisa.databinding.SheetReaderSeriesBinding
import ceui.loxia.Client
import ceui.loxia.Novel
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

class SeriesListSheet : BottomSheetDialogFragment() {

    private var seriesId: Long = 0L
    private var currentNovelId: Long = 0L
    private var seriesTitle: String? = null
    private var onSelected: ((Novel) -> Unit)? = null

    private var _binding: SheetReaderSeriesBinding? = null
    private val binding get() = _binding!!

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
        _binding = SheetReaderSeriesBinding.inflate(inflater, container, false)
        binding.title.text = seriesTitle?.takeIf { it.isNotBlank() }
            ?: getString(R.string.series_sheet_title)

        if (seriesId == 0L) {
            showEmpty(getString(R.string.series_sheet_empty))
        } else {
            loadSeries()
        }
        return binding.root
    }

    override fun onStart() {
        super.onStart()
        ReaderSheetUi.applyExpandedHeight(this)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun showEmpty(msg: String) {
        binding.loading.isVisible = false
        binding.list.isVisible = false
        binding.empty.text = msg
        binding.empty.isVisible = true
        binding.count.isVisible = false
    }

    private fun showList(novels: List<Novel>) {
        binding.loading.isVisible = false
        binding.empty.isVisible = false
        binding.count.text = getString(R.string.series_sheet_count, novels.size)
        binding.count.isVisible = true
        val currentIndex = novels.indexOfFirst { it.id == currentNovelId }
        val accent = ceui.lisa.utils.Common.resolveThemeAttribute(requireContext(), androidx.appcompat.R.attr.colorPrimary)
        binding.list.isVisible = true
        binding.list.layoutManager = LinearLayoutManager(requireContext())
        binding.list.adapter = SeriesAdapter(novels, currentIndex, accent) { novel ->
            onSelected?.invoke(novel)
            dismissAllowingStateLoss()
        }
        if (currentIndex >= 0) {
            binding.list.post {
                (binding.list.layoutManager as? LinearLayoutManager)
                    ?.scrollToPositionWithOffset(currentIndex, 0)
            }
        }
    }

    private fun loadSeries() {
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
                        showList(novels)
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
        private val accent: Int,
        private val onClick: (Novel) -> Unit,
    ) : RecyclerView.Adapter<SeriesAdapter.VH>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val binding = ItemReaderSeriesRowBinding.inflate(
                LayoutInflater.from(parent.context), parent, false,
            )
            return VH(binding)
        }

        override fun getItemCount(): Int = novels.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val novel = novels[position]
            val ctx = holder.itemView.context
            val isCurrent = position == currentIndex
            val textPrimary = ContextCompat.getColor(ctx, R.color.v3_text_1)
            val textSecondary = ContextCompat.getColor(ctx, R.color.v3_text_3)
            holder.binding.index.text = "${position + 1}"
            holder.binding.seriesTitle.text = novel.title.orEmpty()
            holder.binding.seriesTitle.setTypeface(
                holder.binding.seriesTitle.typeface,
                if (isCurrent) Typeface.BOLD else Typeface.NORMAL,
            )
            holder.binding.seriesTitle.setTextColor(if (isCurrent) accent else textPrimary)
            holder.binding.index.setTextColor(if (isCurrent) accent else textSecondary)
            holder.binding.currentBadge.isVisible = isCurrent
            if (isCurrent) {
                val density = ctx.resources.displayMetrics.density
                holder.binding.currentBadge.background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 999f
                    setColor(accent)
                }
            }
            holder.itemView.setOnClickListener {
                if (!isCurrent) onClick(novel)
            }
        }

        class VH(val binding: ItemReaderSeriesRowBinding) : RecyclerView.ViewHolder(binding.root)
    }

    companion object {
        const val TAG = "SeriesListSheet"
        private const val MAX_PAGES = 5
    }
}
