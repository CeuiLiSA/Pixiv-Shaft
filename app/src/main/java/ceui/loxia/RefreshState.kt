package ceui.loxia

import android.content.Context
import androidx.core.view.isVisible
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

data class RefreshHint(
    val cause: Cause
) {
    enum class Cause {
        PULL_TO_REFRESH,
        INITIAL_LOAD,
        LOAD_MORE,
    }

    companion object {
        fun pullToRefresh(): RefreshHint {
            return RefreshHint(Cause.PULL_TO_REFRESH)
        }

        fun initialLoad(): RefreshHint {
            return RefreshHint(Cause.INITIAL_LOAD)
        }

        fun loadMore(): RefreshHint {
            return RefreshHint(Cause.LOAD_MORE)
        }
    }
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
        refreshBlock = { viewModel.refresh(RefreshHint.pullToRefresh(), this) },
        retryBlock = { viewModel.refresh(RefreshHint.initialLoad(), this) },
        loadMoreBlock = { viewModel.loadMore(this) }
    )
    if (!viewModel.isInitialLoaded) {
        viewModel.isInitialLoaded = true
        viewModel.refresh(RefreshHint.initialLoad(), this)
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
                progressCircular.showProgress(false)
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
                if (refreshState.refreshHint?.cause == RefreshHint.Cause.PULL_TO_REFRESH) {
                    loadingFrame.isVisible = false
                    progressCircular.showProgress(false)
                    if (!refreshLayout.isRefreshing) {
                        refreshLayout.autoRefreshAnimationOnly()
                    }
                } else if (refreshState.refreshHint?.cause == RefreshHint.Cause.INITIAL_LOAD) {
                    loadingFrame.isVisible = true
                    progressCircular.showProgress(true)
                }
            } else if (refreshState is RefreshState.ERROR) {
                progressCircular.showProgress(false)
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

fun Throwable.getHumanReadableMessage(context: Context): String {
    return if (this is UnknownHostException || this is SSLHandshakeException || this is TimeoutException || this is SocketTimeoutException) {
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