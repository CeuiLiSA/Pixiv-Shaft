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
import ceui.loxia.Novel
import ceui.pixiv.cache.SeriesCache
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
        // total = 系列总话数(content_count)，firstNovelId = 第1话 id，用于判定列表方向后
        // 标真实话号（小说系列一般升序，但不写死，靠 first_novel 反查兜底）。
        data class Loaded(val novels: List<Novel>, val total: Int, val firstNovelId: Long?) : State()
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
                    // 走 SeriesCache：阅读器翻页找相邻话时已经拉过就直接命中，不再重复拉整条系列。
                    val entry = SeriesCache.loadNovelSeries(seriesId)
                    Triple(entry.items, entry.total, entry.firstEpisodeId)
                }
            }
            result.fold(
                onSuccess = { (novels, total, firstNovelId) ->
                    _state.value = State.Loaded(novels, total, firstNovelId)
                },
                onFailure = { ex ->
                    Timber.e(ex, "SeriesListViewModel load failed series=$seriesId")
                    _state.value = State.Error(ex.message ?: ex.javaClass.simpleName)
                },
            )
        }
    }

    companion object {
        fun factory(seriesId: Long): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return SeriesListViewModel(seriesId) as T
            }
        }
    }
}

class SeriesListSheet : BottomSheetDialogFragment() {

    // edgeToEdge:让 window 画到导航栏底下,内容背景才能延伸进底部 safe area。
    override fun getTheme(): Int = R.style.ThemeOverlay_App_BottomSheetDialog_EdgeToEdge

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
                        showList(state.novels, state.total, state.firstNovelId)
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

    private fun showList(novels: List<Novel>, total: Int, firstNovelId: Long?) {
        binding.loading.isVisible = false
        binding.empty.isVisible = false
        binding.count.text = getString(R.string.series_sheet_count, novels.size)
        binding.count.isVisible = true
        val currentIndex = novels.indexOfFirst { it.id == currentNovelId }
        val accent = ceui.lisa.utils.Common.resolveThemeAttribute(requireContext(), androidx.appcompat.R.attr.colorPrimary)
        // 方向判定：列表首个若不是第1话即降序（最新在前）。小说系列一般升序，此时
        // descending=false，行为等同旧的 position+1，不回归。
        val descending = firstNovelId != null && novels.isNotEmpty() &&
                novels.first().id != firstNovelId
        val effectiveTotal = if (total > 0) total else novels.size
        binding.list.isVisible = true
        binding.list.layoutManager = LinearLayoutManager(requireContext())
        binding.list.adapter = SeriesAdapter(novels, currentIndex, accent, descending, effectiveTotal) { novel ->
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
        private val descending: Boolean,
        private val total: Int,
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
            holder.binding.index.text = if (descending && total > 0) {
                "${(total - position).coerceAtLeast(1)}"
            } else {
                "${position + 1}"
            }
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
