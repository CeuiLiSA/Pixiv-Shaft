package ceui.pixiv.ui.common

sealed class RefreshHint {
    data object PullToRefresh : RefreshHint()
    data object InitialLoad : RefreshHint()
    data object ErrorRetry : RefreshHint()
}