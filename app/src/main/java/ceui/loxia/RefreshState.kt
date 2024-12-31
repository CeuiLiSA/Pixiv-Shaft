package ceui.loxia

import android.content.Context
import androidx.core.view.isVisible
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LiveData
import androidx.recyclerview.widget.RecyclerView
import ceui.lisa.R
import ceui.lisa.databinding.ItemLoadingBinding
import ceui.pixiv.ui.common.CommonAdapter
import ceui.refactor.setOnClick
import com.scwang.smart.refresh.header.FalsifyFooter
import com.scwang.smart.refresh.header.MaterialHeader
import com.scwang.smart.refresh.layout.SmartRefreshLayout
import java.io.Serializable
import java.lang.Exception
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeoutException
import javax.net.ssl.SSLHandshakeException

sealed class RefreshState: Serializable {
    data class LOADING(val title: String = "", val refreshHint: RefreshHint? = null) : RefreshState()
    data class LOADED(val hasContent: Boolean = true, val hasNext: Boolean = true) : RefreshState()
    data class ERROR(val exception: Exception, val isInitialLoad: Boolean = false) : RefreshState()
}

sealed class RefreshHint {
    data object PullToRefresh : RefreshHint()
    data object InitialLoad : RefreshHint()
    data object LoadMore : RefreshHint()
    data object ErrorRetry : RefreshHint()
    // You can add additional methods if needed
}

inline fun <reified FragmentT : SlinkyListFragment> FragmentT.setUpSlinkyList(
    listView: RecyclerView,
    refreshLayout: SmartRefreshLayout,
    itemLoading: ItemLoadingBinding,
    viewModel: SlinkyListViewModel<FragmentT>
) {
    val adapter = CommonAdapter(viewLifecycleOwner)
    listView.adapter = adapter
    viewModel.holderList.observe(viewLifecycleOwner) { list ->
        adapter.submitList(list)
    }
    itemLoading.setUpRefreshState(
        this,
        refreshLayout,
        viewModel.refreshState,
        refreshBlock = { viewModel.refresh(RefreshHint.PullToRefresh, this) },
        retryBlock = { viewModel.refresh(RefreshHint.InitialLoad, this) },
        loadMoreBlock = { viewModel.loadMore(this) }
    )
    if (!viewModel.isInitialLoaded) {
        viewModel.isInitialLoaded = true
        viewModel.refresh(RefreshHint.InitialLoad, this)
    }
}

fun ItemLoadingBinding.setUpRefreshState(
    fragment: NavFragment,
    refreshLayout: SmartRefreshLayout,
    refreshState: LiveData<RefreshState>,
    refreshBlock: () -> Unit,
    retryBlock: () -> Unit,
    loadMoreBlock: () -> Unit,
) {
    with(fragment) {
        val context = requireContext()
        refreshLayout.setRefreshHeader(MaterialHeader(context))
        refreshLayout.setOnRefreshListener {
            refreshBlock.invoke()
        }
        refreshLayout.setOnLoadMoreListener {
            loadMoreBlock.invoke()
        }
        emptyActionButton.setOnClick {
            retryBlock.invoke()
        }
        refreshState.observe(viewLifecycleOwner) { refreshState ->
            if (refreshState is RefreshState.LOADED) {
                progressCircular.hideProgress()
                loadingFrame.isVisible = false
                refreshLayout.finishRefresh()
                refreshLayout.finishLoadMore()

                if (refreshState.hasContent) {
                    emptyFrame.isVisible = false
                } else {
                    emptyFrame.isVisible = true
                    emptyActionButton.text = getString(R.string.refresh)
                    emptyTitle.text = getString(R.string.empty_content_here)
                }

                if (refreshState.hasNext) {
                    refreshLayout.setRefreshFooter(SlinkyFooter(context))
                } else {
                    refreshLayout.setRefreshFooter(FalsifyFooter(context))
                }
            } else if (refreshState is RefreshState.LOADING) {
                emptyFrame.isVisible = false
                if (refreshState.refreshHint == RefreshHint.PullToRefresh) {
                    loadingFrame.isVisible = false
                    progressCircular.hideProgress()
                    if (!refreshLayout.isRefreshing) {
                        refreshLayout.autoRefreshAnimationOnly()
                    }
                } else if (refreshState.refreshHint == RefreshHint.InitialLoad) {
                    loadingFrame.isVisible = true
                    progressCircular.showProgress()
                }
            } else if (refreshState is RefreshState.ERROR) {
                progressCircular.hideProgress()
                loadingFrame.isVisible = false
                refreshLayout.finishRefresh()
                refreshLayout.finishLoadMore()

                emptyFrame.isVisible = true
                emptyActionButton.text = getString(R.string.retry)
                emptyTitle.text = refreshState.exception.getHumanReadableMessage(context)
            }
        }
    }
}

fun ItemLoadingBinding.setUpHolderRefreshState(
    refreshState: LiveData<RefreshState>,
    viewLifecycleOwner: LifecycleOwner,
    retryBlock: () -> Unit,
) {
    val context = root.context
    emptyActionButton.setOnClick {
        retryBlock.invoke()
    }
    refreshState.observe(viewLifecycleOwner) { refreshState ->
        if (refreshState is RefreshState.LOADED) {
            progressCircular.hideProgress()
            loadingFrame.isVisible = false

            if (refreshState.hasContent) {
                emptyFrame.isVisible = false
            } else {
                emptyFrame.isVisible = true
                emptyActionButton.text = context.getString(R.string.refresh)
                emptyTitle.text = context.getString(R.string.empty_content_here)
            }
        } else if (refreshState is RefreshState.LOADING) {
            emptyFrame.isVisible = false
            if (refreshState.refreshHint == RefreshHint.PullToRefresh) {
                loadingFrame.isVisible = false
                progressCircular.hideProgress()
            } else if (refreshState.refreshHint == RefreshHint.InitialLoad) {
                loadingFrame.isVisible = true
                progressCircular.showProgress()
            }
        } else if (refreshState is RefreshState.ERROR) {
            progressCircular.hideProgress()
            loadingFrame.isVisible = false

            emptyFrame.isVisible = true
            emptyActionButton.text = context.getString(R.string.retry)
            emptyTitle.text = refreshState.exception.getHumanReadableMessage(context)
        }
    }
}

fun Throwable.getHumanReadableMessage(context: Context): String {
    return if (this is SSLHandshakeException || this is TimeoutException || this is SocketTimeoutException) {
        "${context.getString(R.string.connection_error)}: ${this.javaClass.simpleName}"
    } else {
        val lc = localizedMessage
        if (lc == null) {
            context.getString(R.string.unknown_error_message)
        } else if (lc.contains("<html") || lc.contains("<!DOCTYPE html")) {
            val titleAfter = lc.substringAfter("<title>")
            val title = titleAfter.substringBefore("</title>")
            title
        } else {
            lc
        }
    }
}