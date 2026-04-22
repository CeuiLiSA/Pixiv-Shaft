package ceui.pixiv.ui.novel

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.lisa.activities.TemplateActivity
import ceui.lisa.activities.UActivity
import ceui.lisa.activities.VActivity
import ceui.lisa.core.Container
import ceui.lisa.core.PageData
import ceui.lisa.databinding.FragmentPixivListBinding
import ceui.lisa.models.IllustsBean
import ceui.lisa.utils.Params
import ceui.lisa.utils.V3Palette
import ceui.loxia.Client
import ceui.loxia.ObjectPool
import ceui.pixiv.ui.common.CommonAdapter
import ceui.pixiv.ui.common.ListMode
import ceui.pixiv.ui.common.NovelMultiSelectReceiver
import ceui.pixiv.ui.common.PixivFragment
import ceui.pixiv.ui.common.constructVM
import ceui.pixiv.ui.common.setUpRefreshState
import ceui.pixiv.ui.common.viewBinding
import ceui.pixiv.ui.task.BatchDownloadNovelsTask
import ceui.pixiv.ui.task.FailedNovel
import ceui.pixiv.ui.task.FetchAllTask
import ceui.pixiv.ui.task.PixivTaskType
import ceui.pixiv.utils.setOnClick
import com.hjq.toast.ToastUtils
import kotlinx.coroutines.launch
import java.util.UUID

class NovelSeriesFragment : PixivFragment(R.layout.fragment_pixiv_list), NovelMultiSelectReceiver {

    private val binding by viewBinding(FragmentPixivListBinding::bind)
    private val seriesId: Long by lazy { arguments?.getLong(ARG_SERIES_ID, 0L) ?: 0L }
    private val viewModel by constructVM({ seriesId }) { id ->
        NovelSeriesViewModel(id)
    }

    // Two independent bottom views — we swap between them based on
    // isMultiSelect. Kept as fields so the observer can just flip
    // visibility instead of re-inflating on every emission.
    private var singleDownloadBtn: TextView? = null
    private var multiSelectBar: View? = null
    private var multiSelectDownloadBtn: TextView? = null
    private var multiSelectSelectAllBtn: TextView? = null
    private var topToggleBtn: ImageView? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setUpRefreshState(binding, viewModel, ListMode.VERTICAL)
        val density = resources.displayMetrics.density
        binding.listView.clipToPadding = false
        // 用户反馈：模糊封面图作为背景反而干扰前景文字阅读。改为 v3_bg（白天/夜间自动适配）。
        binding.pageBackground.setBackgroundColor(
            androidx.core.content.ContextCompat.getColor(requireContext(), R.color.v3_bg),
        )
        binding.toolbarLayout.root.visibility = View.GONE

        // 醒目的合集下载按钮——老版本放在 toolbarLayout 的 more 菜单里，但 toolbarLayout
        // 被整体 GONE 了，用户根本看不到入口。挂到 bottomCovered 里做成一个 fab-ish 的
        // 条状按钮，所有系列页都能一眼看到。
        addDownloadAllButton()
        addMultiSelectActionBar()
        addMultiSelectTopToggle()

        // Edge-to-edge safe area: TemplateActivity draws behind the status bar
        // / display cutout, so both the floating multi-select toggle and the
        // list's first holder need to clear systemBars.top. The list also
        // needs extra room for the 44dp toggle + 8dp margin overlay.
        // Remove the toolbar's insets listener first — setUpToolbar sets one
        // on binding.toolbarLayout.root that calls content.updatePadding(0,0,0,bottom),
        // resetting our top padding to 0. The toolbar is GONE anyway.
        ViewCompat.setOnApplyWindowInsetsListener(binding.toolbarLayout.root, null)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.listView.updatePadding(
                top = bars.top + (56 * density).toInt(),
                bottom = bars.bottom + (72 * density).toInt()
            )
            binding.bottomCovered.updatePadding(bottom = bars.bottom)
            topToggleBtn?.let { tb ->
                val lp = tb.layoutParams as ConstraintLayout.LayoutParams
                lp.topMargin = bars.top + (8 * density).toInt()
                tb.layoutParams = lp
            }
            insets
        }
        ViewCompat.requestApplyInsets(binding.root)

        // Observe multi-select state: swap bottom UI. Card visual state is
        // driven by the holders list (ViewModel re-emits holders with updated
        // isMultiSelectMode / isSelected), so the adapter handles it via DiffUtil.
        viewModel.isMultiSelect.observe(viewLifecycleOwner) { enabled ->
            applyMultiSelectVisibility(enabled)
        }
        viewModel.selectedIds.observe(viewLifecycleOwner) { selected ->
            val count = selected.size
            multiSelectDownloadBtn?.text = getString(R.string.download_selected_count, count)
            val allIds = viewModel.allNovelIds()
            val allSelected = allIds.isNotEmpty() && selected.containsAll(allIds)
            multiSelectSelectAllBtn?.text = getString(
                if (allSelected) R.string.deselect_all else R.string.select_all
            )
        }
    }

    private fun addDownloadAllButton() {
        val density = resources.displayMetrics.density
        val btn = TextView(requireContext()).apply {
            text = getString(R.string.download_all_artworks)
            setTextColor(Color.WHITE)
            textSize = 15f
            gravity = Gravity.CENTER
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 28 * density
                setColor(0xFF2196F3.toInt())
            }
            elevation = 4 * density
            val h = (48 * density).toInt()
            val mx = (20 * density).toInt()
            val my = (12 * density).toInt()
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, h,
            ).apply { setMargins(mx, my, mx, my) }
            setOnClick { launchDownloadAll() }
        }
        binding.bottomCovered.isVisible = true
        binding.bottomCovered.addView(btn)
        singleDownloadBtn = btn
    }

    /**
     * Bottom action row visible only during multi-select mode. Left half
     * is the "全选 / 取消全选" toggle; right half is "下载选中 (N)". Kept
     * visually close to addDownloadAllButton so the transition looks like
     * a UI mode swap rather than a layout jump.
     */
    private fun addMultiSelectActionBar() {
        val density = resources.displayMetrics.density
        val palette = V3Palette.from(requireContext())

        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            val mx = (20 * density).toInt()
            val my = (12 * density).toInt()
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (48 * density).toInt(),
            ).apply { setMargins(mx, my, mx, my) }
            visibility = View.GONE
        }

        val selectAll = TextView(requireContext()).apply {
            text = getString(R.string.select_all)
            setTextColor(palette.textAccent)
            textSize = 15f
            gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
            background = palette.pillSecondary(28 * density, (1 * density).toInt())
            layoutParams = LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1f
            ).apply { marginEnd = (10 * density).toInt() }
            setOnClick { onClickSelectAllToggle() }
        }

        val download = TextView(requireContext()).apply {
            text = getString(R.string.download_selected_count, 0)
            setTextColor(Color.WHITE)
            textSize = 15f
            gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
            background = palette.pillPrimary(28 * density)
            elevation = 4 * density
            layoutParams = LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1.4f
            )
            setOnClick { launchBatchDownloadSelected() }
        }

        row.addView(selectAll)
        row.addView(download)

        binding.bottomCovered.isVisible = true
        binding.bottomCovered.addView(row)

        multiSelectBar = row
        multiSelectSelectAllBtn = selectAll
        multiSelectDownloadBtn = download
    }

    /**
     * Top-right floating toggle that flips multi-select mode. Rendered as
     * an overlay on the root ConstraintLayout to mirror NovelTextFragment's
     * top-actions pattern. Tinted via V3Palette so day/night both look
     * right.
     */
    private fun addMultiSelectTopToggle() {
        val density = resources.displayMetrics.density
        val palette = V3Palette.from(requireContext())

        val toggle = ImageView(requireContext()).apply {
            // A generic "checklist" vector isn't guaranteed in the project,
            // so reuse ic_check_circle for ON and ic_checkbox_off for OFF.
            setImageResource(R.drawable.ic_checkbox_off)
            setColorFilter(palette.textTag)
            val size = (44 * density).toInt()
            val pad = (10 * density).toInt()
            setPadding(pad, pad, pad, pad)
            background = palette.pillSecondary(
                999f * density, (1 * density).toInt()
            )
            setOnClick {
                val now = viewModel.isMultiSelect.value == true
                viewModel.setMultiSelectMode(!now)
            }
            // Anchor top-end; topMargin is updated via the root WindowInsets
            // listener in onViewCreated so it matches safe-area / cutout.
            val lp = ConstraintLayout.LayoutParams(size, size).apply {
                topToTop = ConstraintLayout.LayoutParams.PARENT_ID
                endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
                topMargin = (8 * density).toInt()
                marginEnd = (12 * density).toInt()
            }
            layoutParams = lp
        }

        val rootLayout = binding.root as ConstraintLayout
        rootLayout.addView(toggle)
        topToggleBtn = toggle
    }

    private fun applyMultiSelectVisibility(enabled: Boolean) {
        singleDownloadBtn?.isVisible = !enabled
        multiSelectBar?.isVisible = enabled
        val palette = V3Palette.from(requireContext())
        topToggleBtn?.let { tb ->
            if (enabled) {
                tb.setImageResource(R.drawable.ic_check_circle_black_24dp)
                tb.clearColorFilter()
            } else {
                tb.setImageResource(R.drawable.ic_checkbox_off)
                tb.setColorFilter(palette.textTag)
            }
        }
    }

    private fun onClickSelectAllToggle() {
        val selected = viewModel.selectedIds.value.orEmpty()
        val allIds = viewModel.allNovelIds()
        if (allIds.isEmpty()) return
        if (selected.containsAll(allIds)) {
            viewModel.clearSelection()
        } else {
            viewModel.selectAll()
        }
    }

    private fun launchBatchDownloadSelected() {
        val novels = viewModel.selectedNovels()
        if (novels.isEmpty()) {
            ToastUtils.show(getString(R.string.batch_download_no_selection))
            return
        }
        BatchDownloadNovelsTask(
            activity = requireActivity(),
            novels = novels,
            onFinished = { failures -> onBatchDownloadFinished(failures) },
        )
    }

    private fun onBatchDownloadFinished(failures: List<FailedNovel>) {
        if (!isAdded) return
        if (failures.isEmpty()) {
            ToastUtils.show(getString(R.string.batch_download_all_ok))
            // Exit multi-select on full success — matches the user's mental
            // model ("I'm done, clean up").
            viewModel.setMultiSelectMode(false)
            return
        }
        val msg = failures.joinToString(separator = "\n") { fn ->
            getString(
                R.string.batch_download_failure_line,
                fn.novel.title.orEmpty(),
                fn.reason.orEmpty(),
            )
        }
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.batch_download_some_failed, failures.size))
            .setMessage(msg)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun launchDownloadAll() {
        FetchAllTask(
            requireActivity(),
            taskFullName = "下载系列小说全部作品-${seriesId}",
            PixivTaskType.DownloadSeriesNovels,
        ) {
            Client.appApi.getNovelSeries(seriesId)
        }
    }

    // ── NovelMultiSelectReceiver ────────────────────────────────────
    override fun isNovelMultiSelectMode(): Boolean {
        return viewModel.isMultiSelect.value == true
    }

    override fun isNovelSelected(novelId: Long): Boolean {
        return viewModel.selectedIds.value?.contains(novelId) == true
    }

    override fun onToggleNovelSelection(novelId: Long) {
        viewModel.toggleSelection(novelId)
    }

    override fun onClickUser(id: Long) {
        val intent = Intent(requireContext(), UActivity::class.java).apply {
            putExtra(Params.USER_ID, id.toInt())
        }
        startActivity(intent)
    }

    override fun onClickNovel(novelId: Long) {
        val intent = Intent(requireContext(), TemplateActivity::class.java).apply {
            putExtra(TemplateActivity.EXTRA_FRAGMENT, "小说详情")
            putExtra(Params.NOVEL_ID, novelId)
        }
        startActivity(intent)
    }

    override fun onClickIllust(illustId: Long) {
        viewLifecycleOwner.lifecycleScope.launch {
            val illust = runCatching { Client.appApi.getIllust(illustId).illust }
                .getOrNull() ?: return@launch
            val gson = Shaft.sGson
            val bean = gson.fromJson(gson.toJson(illust), IllustsBean::class.java)
            val uuid = UUID.randomUUID().toString()
            val pageData = PageData(uuid, null, listOf(bean))
            Container.get().addPageToMap(pageData)
            val intent = Intent(requireContext(), VActivity::class.java).apply {
                putExtra(Params.POSITION, 0)
                putExtra(Params.PAGE_UUID, uuid)
            }
            startActivity(intent)
        }
    }

    companion object {
        const val ARG_SERIES_ID = "series_id"

        fun newInstance(seriesId: Long): NovelSeriesFragment = NovelSeriesFragment().apply {
            arguments = Bundle().apply { putLong(ARG_SERIES_ID, seriesId) }
        }
    }
}
