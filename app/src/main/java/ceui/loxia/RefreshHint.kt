package ceui.loxia

sealed class RefreshHint {
    data object PullToRefresh : RefreshHint()
    data object InitialLoad : RefreshHint()
    data object LoadMore : RefreshHint()
    data object ErrorRetry : RefreshHint()
    data object FetchingLatest : RefreshHint()
}