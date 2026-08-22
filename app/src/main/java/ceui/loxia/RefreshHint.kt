package ceui.loxia

sealed class RefreshHint {
    data object PullToRefresh : RefreshHint()
    data object InitialLoad : RefreshHint()
    data object ErrorRetry : RefreshHint()
}