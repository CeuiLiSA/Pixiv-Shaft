package ceui.pixiv.ui.novel.reader.ui

import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
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

/**
 * 回调接口 — 父 Fragment 实现此接口以接收系列列表中的选中事件。
 */
interface SeriesNavCallback {
    fun onSeriesNovelSelected(novel: Novel)
}

class SeriesListViewModel(
    private val seriesId: Long,
) : ViewModel() {

    sealed class State {
        object Idle : State()
        object Loading : State()
        data class Loaded(val novels: List<Novel>) : State()
        data class Error(val message: String) : State()
    }

    private val _state = MutableLiveData<State>(State.Idle)
    val state: LiveData<State> = _state

    fun load() {
        if (_state.value !is State.Idle) return
        _state.value = State.Loading
        viewModelScope.launch {
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
            result.fold(
                onSuccess = { novels ->
                    _state.value = State.Loaded(novels)
                },
                onFailure = { ex ->
                    Timber.e(ex, "SeriesListViewModel load failed series=$seriesId")
                    _state.value = State.Error(ex.message ?: ex.javaClass.simpleName)
                },
            )
        }
    }

    companion object {
        private const val MAX_PAGES = 5

        fun factory(seriesId: Long): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return SeriesListViewModel(seriesId) as T
            }
        }
    }
}

class SeriesListSheet : BottomSheetDialogFragment() {

    private var _binding: SheetReaderSeriesBinding? = null
    private val binding get() = _binding!!

    private val seriesId: Long by lazy { requireArguments().getLong(ARG_SERIES_ID) }
    private val currentNovelId: Long by lazy { requireArguments().getLong(ARG_CURRENT_NOVEL_ID) }
    private val seriesTitle: String? by lazy { requireArguments().getString(ARG_SERIES_TITLE) }

    private val viewModel: SeriesListViewModel by lazy {
        ViewModelProvider(this, SeriesListViewModel.factory(seriesId))[SeriesListViewModel::class.java]
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
            viewModel.load()
        }

        viewModel.state.observe(viewLifecycleOwner) { state ->
            when (state) {
                is SeriesListViewModel.State.Idle,
                is SeriesListViewModel.State.Loading -> {
                    binding.loading.isVisible = true
                    binding.empty.isVisible = false
                    binding.list.isVisible = false
                    binding.count.isVisible = false
                }
                is SeriesListViewModel.State.Loaded -> {
                    if (state.novels.isEmpty()) {
                        showEmpty(getString(R.string.series_sheet_empty))
                    } else {
                        showList(state.novels)
                    }
                }
                is SeriesListViewModel.State.Error -> {
                    showEmpty(getString(R.string.series_sheet_load_failed, state.message))
                }
            }
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
            (parentFragment as? SeriesNavCallback)?.onSeriesNovelSelected(novel)
            dismissAllowingStateLoss()
        }
        if (currentIndex >= 0) {
            binding.list.post {
                (binding.list.layoutManager as? LinearLayoutManager)
                    ?.scrollToPositionWithOffset(currentIndex, 0)
            }
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
        private const val ARG_SERIES_ID = "series_id"
        private const val ARG_CURRENT_NOVEL_ID = "current_novel_id"
        private const val ARG_SERIES_TITLE = "series_title"

        fun newInstance(
            seriesId: Long,
            currentNovelId: Long,
            seriesTitle: String? = null,
        ) = SeriesListSheet().apply {
            arguments = Bundle().apply {
                putLong(ARG_SERIES_ID, seriesId)
                putLong(ARG_CURRENT_NOVEL_ID, currentNovelId)
                putString(ARG_SERIES_TITLE, seriesTitle)
            }
        }
    }
}
