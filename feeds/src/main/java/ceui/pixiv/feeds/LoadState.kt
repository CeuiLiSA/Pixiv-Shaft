package ceui.pixiv.feeds

/**
 * 一次加载动作（首屏刷新 or 追加翻页）的状态。
 *
 * 刻意不引入 "End" 状态：是否到底由 [FeedUiState.reachedEnd] 单独表达，
 * 避免「状态机里混入非互斥语义」（到底和出错可以同时成立）。
 */
sealed interface LoadState {

    data object Idle : LoadState

    data object Loading : LoadState

    data class Error(val throwable: Throwable) : LoadState
}
