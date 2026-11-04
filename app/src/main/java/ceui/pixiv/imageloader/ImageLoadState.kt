package ceui.pixiv.imageloader

import java.io.File

/**
 * 一次图片加载在某一时刻的状态。V3 系统里同一个 url 的所有订阅方(详情页 B、大图页 C)
 * 观察的是**同一条** [ImageLoadState] 流,因此进度天然共享。
 *
 * - [Idle]    还没开始下载
 * - [Loading] 下载中,携带百分比 0..99(真正 100% 只在拿到文件后进 [Success],避免「进度到头了图还没出来」)
 * - [Success] 已拿到本地文件,可直接解码/渲染/下载
 * - [Error]   下载失败,可 retry
 */
sealed interface ImageLoadState {

    data object Idle : ImageLoadState

    data class Loading(
        val percent: Int,
        val bytesRead: Long = -1L,
        val totalBytes: Long = -1L,
    ) : ImageLoadState

    data class Success(val file: File) : ImageLoadState

    data class Error(val cause: Throwable) : ImageLoadState
}

/** 命中结果文件(仅 [ImageLoadState.Success] 时非空)。 */
val ImageLoadState.fileOrNull: File?
    get() = (this as? ImageLoadState.Success)?.file

/** 是否已到达终态(成功或失败)。 */
val ImageLoadState.isTerminal: Boolean
    get() = this is ImageLoadState.Success || this is ImageLoadState.Error
