package ceui.pixiv.paging

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.paging.LoadState
import androidx.paging.LoadStateAdapter
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import ceui.lisa.R
import ceui.lisa.annotations.ItemHolder
import ceui.lisa.databinding.ItemLoadStateBinding
import ceui.pixiv.ui.common.ListItemHolder
import ceui.pixiv.ui.common.ListItemViewHolder


class PagingLoadingHolder(val loadState: LoadState, val retry: () -> Unit) : ListItemHolder()

@ItemHolder(PagingLoadingHolder::class)
class PagingLoadingViewHolder(bd: ItemLoadStateBinding) :
    ListItemViewHolder<ItemLoadStateBinding, PagingLoadingHolder>(bd) {

    private val progressBar: ProgressBar = itemView.findViewById(R.id.progress_bar)
    private val retryButton: Button = itemView.findViewById(R.id.retry_button)
    private val errorText: TextView = itemView.findViewById(R.id.error_text)


    override fun onBindViewHolder(holder: PagingLoadingHolder, position: Int) {
        super.onBindViewHolder(holder, position)
        retryButton.setOnClickListener { holder.retry() }
        bind(holder.loadState)
    }


    fun bind(loadState: LoadState) {
        progressBar.isVisible = loadState is LoadState.Loading
        retryButton.isVisible = loadState is LoadState.Error
        errorText.isVisible = loadState is LoadState.Error
    }
}

class LoadingStateAdapter(
    private val retry: () -> Unit
) : LoadStateAdapter<LoadingStateAdapter.ViewHolder>() {

    class ViewHolder(itemView: View, retry: () -> Unit) : RecyclerView.ViewHolder(itemView) {
        private val progressBar: ProgressBar = itemView.findViewById(R.id.progress_bar)
        private val retryButton: Button = itemView.findViewById(R.id.retry_button)
        private val errorText: TextView = itemView.findViewById(R.id.error_text)

        init {
            retryButton.setOnClickListener { retry() }
        }

        fun bind(loadState: LoadState) {
            progressBar.isVisible = loadState is LoadState.Loading
            retryButton.isVisible = loadState is LoadState.Error
            errorText.isVisible = loadState is LoadState.Error
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, loadState: LoadState): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(
            R.layout.item_load_state, parent, false
        )
        return ViewHolder(view, retry)
    }

    override fun onBindViewHolder(holder: ViewHolder, loadState: LoadState) {
        // ⭐ 关键：设置全宽
        (holder.itemView.layoutParams as? StaggeredGridLayoutManager.LayoutParams)?.isFullSpan =
            true
        holder.bind(loadState)
    }
}
