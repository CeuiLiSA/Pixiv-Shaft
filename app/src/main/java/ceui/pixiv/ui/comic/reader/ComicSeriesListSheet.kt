package ceui.pixiv.ui.comic.reader

import android.content.Intent
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
import ceui.lisa.activities.TemplateActivity
import ceui.lisa.databinding.ItemReaderSeriesRowBinding
import ceui.lisa.databinding.SheetReaderSeriesBinding
import ceui.lisa.utils.Params
import ceui.loxia.Illust
import ceui.loxia.SeriesCache
import ceui.pixiv.ui.novel.reader.ui.ReaderSheetUi
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import ceui.pixiv.ui.navigation.TemplateRoute

class ComicSeriesListViewModel(
    private val seriesId: Long,
) : ViewModel() {

    sealed class State {
        object Idle : State()
        object Loading : State()
        // total = 系列总话数(series_work_count)，firstIllustId = 第1话 id。漫画系列一般
        // 「最新在前」降序，靠 first_illust 反查方向后把列表标成真实话号。
        data class Loaded(val illusts: List<Illust>, val total: Int, val firstIllustId: Long?) : State()
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
                    // 走 SeriesCache：翻页找相邻话时已经拉过就直接命中，不再重复整条系列拉一遍。
                    val entry = SeriesCache.loadIllustSeries(seriesId, MAX_PAGES)
                    Triple(entry.items, entry.total, entry.firstEpisodeId)
                }
            }
            result.fold(
                onSuccess = { (illusts, total, firstIllustId) ->
                    _state.value = State.Loaded(illusts, total, firstIllustId)
                },
                onFailure = { ex ->
                    Timber.e(ex, "ComicSeriesListViewModel load failed series=$seriesId")
                    _state.value = State.Error(ex.message ?: ex.javaClass.simpleName)
                },
            )
        }
    }

    companion object {
        private const val MAX_PAGES = 10

        fun factory(seriesId: Long): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return ComicSeriesListViewModel(seriesId) as T
            }
        }
    }
}

class ComicSeriesListSheet : BottomSheetDialogFragment() {

    // edgeToEdge:让 window 画到导航栏底下,内容背景才能延伸进底部 safe area。
    override fun getTheme(): Int = R.style.ThemeOverlay_App_BottomSheetDialog_EdgeToEdge

    private var _binding: SheetReaderSeriesBinding? = null
    private val binding get() = _binding!!

    private val seriesId: Long by lazy { requireArguments().getLong(ARG_SERIES_ID) }
    private val currentIllustId: Long by lazy { requireArguments().getLong(ARG_CURRENT_ILLUST_ID) }
    private val seriesTitle: String? by lazy { requireArguments().getString(ARG_SERIES_TITLE) }

    private val viewModel: ComicSeriesListViewModel by lazy {
        ViewModelProvider(this, ComicSeriesListViewModel.factory(seriesId))[ComicSeriesListViewModel::class.java]
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
                is ComicSeriesListViewModel.State.Idle,
                is ComicSeriesListViewModel.State.Loading -> {
                    binding.loading.isVisible = true
                    binding.empty.isVisible = false
                    binding.list.isVisible = false
                    binding.count.isVisible = false
                }
                is ComicSeriesListViewModel.State.Loaded -> {
                    if (state.illusts.isEmpty()) {
                        showEmpty(getString(R.string.series_sheet_empty))
                    } else {
                        showList(state.illusts, state.total, state.firstIllustId)
                    }
                }
                is ComicSeriesListViewModel.State.Error -> {
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

    private fun showList(illusts: List<Illust>, total: Int, firstIllustId: Long?) {
        binding.loading.isVisible = false
        binding.empty.isVisible = false
        binding.count.text = getString(R.string.series_sheet_count, illusts.size)
        binding.count.isVisible = true
        val currentIndex = illusts.indexOfFirst { it.id == currentIllustId }
        val accent = ceui.lisa.utils.Common.resolveThemeAttribute(requireContext(), androidx.appcompat.R.attr.colorPrimary)
        // 方向判定：漫画系列默认「最新在前」降序，列表首个若正好是第1话才当升序。
        val descending = firstIllustId == null || illusts.isEmpty() ||
                illusts.first().id != firstIllustId
        val effectiveTotal = if (total > 0) total else illusts.size
        binding.list.isVisible = true
        binding.list.layoutManager = LinearLayoutManager(requireContext())
        binding.list.adapter = SeriesAdapter(illusts, currentIndex, accent, descending, effectiveTotal) { illust ->
            val intent = Intent(requireContext(), TemplateActivity::class.java).apply {
                putExtra(TemplateActivity.EXTRA_FRAGMENT, TemplateRoute.COMIC_READER.key)
                putExtra(Params.ILLUST_ID, illust.id)
            }
            startActivity(intent)
            activity?.finish()
        }
        if (currentIndex >= 0) {
            binding.list.post {
                (binding.list.layoutManager as? LinearLayoutManager)
                    ?.scrollToPositionWithOffset(currentIndex, 0)
            }
        }
    }

    private class SeriesAdapter(
        private val illusts: List<Illust>,
        private val currentIndex: Int,
        private val accent: Int,
        private val descending: Boolean,
        private val total: Int,
        private val onClick: (Illust) -> Unit,
    ) : RecyclerView.Adapter<SeriesAdapter.VH>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val binding = ItemReaderSeriesRowBinding.inflate(
                LayoutInflater.from(parent.context), parent, false,
            )
            return VH(binding)
        }

        override fun getItemCount(): Int = illusts.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val illust = illusts[position]
            val ctx = holder.itemView.context
            val isCurrent = position == currentIndex
            val textPrimary = ContextCompat.getColor(ctx, R.color.v3_text_1)
            val textSecondary = ContextCompat.getColor(ctx, R.color.v3_text_3)
            // 降序列表标真实话号：第 0 个 = 最新 = 第 total 话；升序退回 position+1。
            holder.binding.index.text = if (descending && total > 0) {
                "${(total - position).coerceAtLeast(1)}"
            } else {
                "${position + 1}"
            }
            holder.binding.seriesTitle.text = illust.title.orEmpty()
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
                if (!isCurrent) onClick(illust)
            }
        }

        class VH(val binding: ItemReaderSeriesRowBinding) : RecyclerView.ViewHolder(binding.root)
    }

    companion object {
        const val TAG = "ComicSeriesListSheet"
        private const val ARG_SERIES_ID = "series_id"
        private const val ARG_CURRENT_ILLUST_ID = "current_illust_id"
        private const val ARG_SERIES_TITLE = "series_title"

        fun newInstance(
            seriesId: Long,
            currentIllustId: Long,
            seriesTitle: String? = null,
        ) = ComicSeriesListSheet().apply {
            arguments = Bundle().apply {
                putLong(ARG_SERIES_ID, seriesId)
                putLong(ARG_CURRENT_ILLUST_ID, currentIllustId)
                putString(ARG_SERIES_TITLE, seriesTitle)
            }
        }
    }
}
