package ceui.pixiv.ui.download

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import ceui.lisa.R
import ceui.lisa.download.IllustDownload
import ceui.lisa.utils.GlideUrlChild
import ceui.lisa.utils.Params
import ceui.pixiv.download.ImagePageSaver
import com.bumptech.glide.Glide
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.launch

/** Observes the existing process-owned saves; this page never dispatches queue work. */
@AndroidEntryPoint
class PageSavesV3Fragment : Fragment(R.layout.fragment_page_saves_v3) {
    @Inject lateinit var saver: ImagePageSaver

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val adapter = SaveAdapter(saver)
        val list = view.findViewById<RecyclerView>(R.id.pageSaveList)
        list.layoutManager = LinearLayoutManager(requireContext())
        list.adapter = adapter
        list.itemAnimator = null
        val empty = view.findViewById<View>(R.id.pageSaveEmpty)
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                saver.states.collect { states ->
                    val rows = states.mapNotNull { (key, state) ->
                        if (state.target !is ImagePageSaver.Target.Original) return@mapNotNull null
                        val request = saver.request(key) ?: return@mapNotNull null
                        SaveRow(key, request.illust.title.orEmpty(),
                            request.illust.page_count.coerceAtLeast(1),
                            IllustDownload.getUrl(request.illust, key.page, Params.IMAGE_RESOLUTION_MEDIUM), state)
                    }.sortedWith(compareBy({ it.key.illustId }, { it.key.page }))
                    adapter.submitList(rows)
                    empty.isVisible = rows.isEmpty()
                }
            }
        }
    }
}

private data class SaveRow(
    val key: ImagePageSaver.PageKey,
    val title: String,
    val total: Int,
    val thumbnail: String?,
    val state: ImagePageSaver.PageState,
)

private class SaveAdapter(private val saver: ImagePageSaver) : ListAdapter<SaveRow, SaveAdapter.Holder>(
    object : DiffUtil.ItemCallback<SaveRow>() {
        override fun areItemsTheSame(a: SaveRow, b: SaveRow) = a.key == b.key
        override fun areContentsTheSame(a: SaveRow, b: SaveRow) = a == b
    },
) {
    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val thumb: ImageView = view.findViewById(R.id.pageSaveThumb)
        val title: TextView = view.findViewById(R.id.pageSaveTitle)
        val page: TextView = view.findViewById(R.id.pageSavePage)
        val status: TextView = view.findViewById(R.id.pageSaveStatus)
        val progress: ProgressBar = view.findViewById(R.id.pageSaveProgress)
        val retry: Button = view.findViewById(R.id.pageSaveRetry)
        val cancel: Button = view.findViewById(R.id.pageSaveCancel)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(
        LayoutInflater.from(parent.context).inflate(R.layout.cell_page_save_v3, parent, false),
    )

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val row = getItem(position)
        val context = holder.itemView.context
        holder.title.text = row.title.ifBlank { row.key.illustId.toString() }
        holder.page.text = context.getString(R.string.page_save_page, row.key.page + 1, row.total)
        Glide.with(holder.thumb).load(row.thumbnail?.let(::GlideUrlChild)).into(holder.thumb)
        val saving = row.state as? ImagePageSaver.PageState.Saving
        val failure = row.state as? ImagePageSaver.PageState.Failed
        holder.progress.isVisible = saving != null
        holder.progress.isIndeterminate = saving?.phase != ImagePageSaver.Phase.LOADING
        holder.progress.progress = saving?.percent ?: 0
        holder.status.text = when {
            failure != null -> context.getString(R.string.page_save_failed,
                failure.error.ifBlank { context.getString(R.string.page_save_unknown_error) })
            saving?.phase == ImagePageSaver.Phase.WRITING -> context.getString(R.string.page_save_writing)
            saving?.phase == ImagePageSaver.Phase.WAITING_TO_WRITE -> context.getString(R.string.page_save_waiting)
            else -> context.getString(R.string.page_save_loading, saving?.percent ?: 0)
        }
        holder.retry.isVisible = failure != null
        holder.retry.setOnClickListener { saver.retry(row.key) }
        holder.cancel.isEnabled = saving?.phase != ImagePageSaver.Phase.WRITING
        holder.cancel.setText(if (failure != null) R.string.page_save_remove else R.string.page_save_cancel)
        holder.cancel.setOnClickListener {
            // Recheck live state: a completion or claimWrite may have raced this binding.
            if (saver.states.value[row.key] is ImagePageSaver.PageState.Failed) saver.dismissFailure(row.key)
            else saver.cancel(row.key)
        }
    }

    override fun onViewRecycled(holder: Holder) {
        Glide.with(holder.thumb).clear(holder.thumb)
        super.onViewRecycled(holder)
    }
}
