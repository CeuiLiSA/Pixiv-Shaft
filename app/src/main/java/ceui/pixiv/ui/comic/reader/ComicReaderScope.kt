package ceui.pixiv.ui.comic.reader

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Application-scoped coroutine 用于读写持久化（书签 / 阅读统计）。
 *
 * 不能用 viewLifecycleOwner.lifecycleScope —— 那个 scope 在 Fragment 旋转 / 退出时立即取消，
 * 会导致用户在 onPause 之前刚做的操作（加书签 / 写时长）被截断丢失。
 *
 * 选用 SupervisorJob 因为各持久化任务彼此独立，单条失败不应连带取消其它任务。
 */
internal object ComicReaderScope {
    val io: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
}
