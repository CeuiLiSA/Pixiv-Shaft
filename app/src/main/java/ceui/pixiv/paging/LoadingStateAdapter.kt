package ceui.pixiv.paging

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.paging.LoadState
import androidx.paging.LoadStateAdapter
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import ceui.lisa.R
import ceui.lisa.databinding.ItemLoadingBinding
import ceui.loxia.findFragmentOrNull
import ceui.loxia.getHumanReadableMessage
import ceui.loxia.openClashApp
import ceui.loxia.requireNetworkStateManager
import ceui.pixiv.ui.common.LoadingViewHolder
import ceui.pixiv.ui.common.PixivFragment
import ceui.pixiv.utils.setOnClick

class LoadingStateAdapter(
    private val retry: () -> Unit
) : LoadStateAdapter<LoadingViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, loadState: LoadState): LoadingViewHolder {
        val binding = ItemLoadingBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return LoadingViewHolder(binding)
    }

    override fun onBindViewHolder(holder: LoadingViewHolder, loadState: LoadState) {
        (holder.itemView.layoutParams as? StaggeredGridLayoutManager.LayoutParams)?.isFullSpan =
            true

        with(holder.binding) {
            val context = root.context
            emptyActionButton.setOnClick {
                it.findFragmentOrNull<PixivFragment>()?.let { fragment ->
                    if (fragment.requireNetworkStateManager().canAccessGoogle.value == true) {
                        retry()
                    } else {
                        openClashApp(context)
                    }
                }
            }

            if (loadState is LoadState.NotLoading) {
                progressCircular.hideProgress()
                loadingFrame.isVisible = false

                if (loadState.endOfPaginationReached) {
                    emptyFrame.isVisible = false
                } else {
                    emptyFrame.isVisible = true
                    emptyActionButton.text = context.getString(R.string.refresh)
                    emptyTitle.text = context.getString(R.string.empty_content_here)
                }
            } else if (loadState is LoadState.Loading) {
                emptyFrame.isVisible = false
                loadingFrame.isVisible = true
                progressCircular.showProgress()
            } else if (loadState is LoadState.Error) {
                progressCircular.hideProgress()
                loadingFrame.isVisible = false

                emptyFrame.isVisible = true
                emptyActionButton.text = context.getString(R.string.retry)
                emptyTitle.text = loadState.error.getHumanReadableMessage(context)
            }
        }
    }
}
