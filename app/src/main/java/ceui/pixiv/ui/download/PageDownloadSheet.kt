package ceui.pixiv.ui.download

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ceui.lisa.R
import ceui.lisa.activities.BaseActivity
import ceui.lisa.core.DownloadItem
import ceui.lisa.core.ManagerReactive
import ceui.lisa.download.IllustDownload
import ceui.lisa.utils.Common
import ceui.pixiv.api.model.Illust
import ceui.pixiv.cache.ObjectPool
import ceui.pixiv.download.DownloadedPageIndex
import ceui.pixiv.ui.detail.ArtworkThumbsSheet
import ceui.pixiv.widgets.PixivBottomSheet
import com.bumptech.glide.Glide
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** A separate multi-select mode: ordinary page previews keep their tap-to-jump behavior. */
class PageDownloadSheet : PixivBottomSheet(R.layout.sheet_page_download) {
    private lateinit var selection: PageDownloadSelection
    private var downloaded = emptySet<Int>()
    private var inProgress = emptySet<Int>()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val id = requireArguments().getLong(ILLUST_ID)
        // No full artwork/URLs in Bundle. If the process was killed, reopen from the artwork.
        val illust = ObjectPool.get<Illust>(id).value
        if (illust == null || illust.isGif()) {
            Common.showToast(R.string.page_download_unavailable)
            dismiss()
            return
        }
        val total = illust.page_count.coerceAtLeast(1)
        val current = requireArguments().getInt(CURRENT).coerceIn(0, total - 1)
        selection = PageDownloadSelection(total,
            savedInstanceState?.getIntArray(SELECTED)?.asIterable() ?: listOf(current))
        val confirm = view.findViewById<Button>(R.id.confirmPageDownload)
        val all = view.findViewById<Button>(R.id.selectAllPages)
        fun renderSummary() {
            val count = selection.snapshot().size
            confirm.text = getString(R.string.page_download_confirm, count)
            confirm.isEnabled = count > 0
            all.setText(if (count == total) R.string.page_download_clear else R.string.page_download_all)
        }
        val adapter = PageAdapter(illust, selection, ::renderSummary)
        val grid = view.findViewById<RecyclerView>(R.id.pageDownloadGrid)
        grid.layoutManager = GridLayoutManager(requireContext(),
            if (resources.configuration.screenWidthDp >= 600) 5 else 3)
        grid.adapter = adapter
        grid.itemAnimator = null
        grid.scrollToPosition(current)
        fun refreshSelection() {
            adapter.notifyItemRangeChanged(0, total, SELECTION_PAYLOAD)
            renderSummary()
        }
        view.findViewById<Button>(R.id.selectCurrentPage).setOnClickListener {
            selection = PageDownloadSelection(total, listOf(current))
            adapter.selection = selection
            refreshSelection()
        }
        all.setOnClickListener { selection.toggleAll(); refreshSelection() }
        val missing = view.findViewById<Button>(R.id.selectMissingPages)
        missing.isEnabled = false
        missing.setOnClickListener {
            selection.selectMissing(downloaded, inProgress)
            refreshSelection()
        }
        confirm.setOnClickListener {
            val pages = selection.snapshot()
            if (pages.isEmpty()) return@setOnClickListener
            confirm.isEnabled = false
            IllustDownload.downloadIllustPagesWithResolution(illust, pages,
                IllustDownload.defaultImageResolution(), activity as? BaseActivity<*>)
            dismiss()
        }
        renderSummary()
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    ManagerReactive.doneTableInvalidations.collectLatest {
                        downloaded = DownloadedPageIndex.pages(requireContext(), illust, total).keys.toSet()
                        adapter.downloaded = downloaded
                        missing.isEnabled = true
                        adapter.notifyItemRangeChanged(0, total, SELECTION_PAYLOAD)
                    }
                }
                ManagerReactive.contentFlow.map { tasks ->
                    tasks.filter { it.illust?.id == id && !it.isFailed }.map { it.index }.toSet()
                }.distinctUntilChanged().collect { pages ->
                    inProgress = pages
                    adapter.inProgress = inProgress
                    adapter.notifyItemRangeChanged(0, total, SELECTION_PAYLOAD)
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        if (::selection.isInitialized) outState.putIntArray(SELECTED, selection.snapshot())
        super.onSaveInstanceState(outState)
    }

    companion object {
        private const val TAG = "selected-page-download"
        private const val ILLUST_ID = "illust-id"
        private const val CURRENT = "current-page"
        private const val SELECTED = "selected-pages"
        private const val SELECTION_PAYLOAD = "selection"

        fun show(host: FragmentActivity, illust: Illust, currentPage: Int) {
            if (illust.isGif()) return
            val fm = host.supportFragmentManager
            if (fm.isStateSaved || fm.findFragmentByTag(TAG) != null) return
            ObjectPool.updateIllust(illust)
            PageDownloadSheet().apply {
                arguments = bundleOf(ILLUST_ID to illust.id, CURRENT to currentPage)
            }.show(fm, TAG)
        }
    }

    private class PageAdapter(
        illust: Illust,
        var selection: PageDownloadSelection,
        private val changed: () -> Unit,
    ) : RecyclerView.Adapter<PageAdapter.Holder>() {
        private val models = ArtworkThumbsSheet.networkModels(illust)
        var downloaded = emptySet<Int>()
        var inProgress = emptySet<Int>()

        class Holder(view: View) : RecyclerView.ViewHolder(view) {
            val image: ImageView = view.findViewById(R.id.cell_thumb_image)
            val index: TextView = view.findViewById(R.id.cell_thumb_index)
            val badge: TextView = view.findViewById(R.id.pageDownloadBadge)
            val check: CheckBox = view.findViewById(R.id.pageDownloadChecked)
        }
        override fun getItemCount() = models.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(
            LayoutInflater.from(parent.context).inflate(R.layout.cell_page_download, parent, false))
        override fun onBindViewHolder(holder: Holder, position: Int) {
            Glide.with(holder.image).load(models[position]).into(holder.image)
            bindState(holder, position)
        }
        override fun onBindViewHolder(holder: Holder, position: Int, payloads: MutableList<Any>) {
            if (payloads.isEmpty()) onBindViewHolder(holder, position) else bindState(holder, position)
        }
        private fun bindState(holder: Holder, position: Int) {
            val context = holder.itemView.context
            val page = context.getString(R.string.page_save_page, position + 1, models.size)
            holder.index.text = page
            holder.check.contentDescription = page
            holder.check.isChecked = selection.contains(position)
            holder.image.alpha = if (selection.contains(position)) 1f else 0.7f
            holder.badge.isVisible = position in downloaded || position in inProgress
            holder.badge.setText(if (position in downloaded) R.string.page_download_saved else R.string.page_download_queued)
            val toggle = View.OnClickListener {
                val index = holder.bindingAdapterPosition
                if (index != RecyclerView.NO_POSITION) {
                    selection.toggle(index)
                    notifyItemChanged(index, SELECTION_PAYLOAD)
                    changed()
                }
            }
            holder.itemView.setOnClickListener(toggle)
            holder.check.setOnClickListener(toggle)
        }
        override fun onViewRecycled(holder: Holder) {
            Glide.with(holder.image).clear(holder.image)
            super.onViewRecycled(holder)
        }
    }
}
