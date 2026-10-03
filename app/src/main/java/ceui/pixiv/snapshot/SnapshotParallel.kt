package ceui.pixiv.snapshot

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * 按 [parallelism] 并发跑 [block]，**结果按输入顺序返回**；任一项失败则取消其余兄弟并把异常原样抛出。
 *
 * 快照逐页写盘用它同时满足两件事：
 * - 本地复制可以并行 —— 这里没有需要串行保护的竞态：目录是本次生成新建的、每页文件名带页码、
 *   assets 映射在全部完成之后才单线程构建；
 * - 并发宽度受控 —— 联网那一段靠它压住突发，别把源站打出 403。
 *
 * 顺序结果是有意保留的：`pagePaths` / `SnapshotAssets` 的条目顺序因此与页码一致，生成的 JSON
 * 逐字节可复现，排障时 diff 才有意义。
 *
 * [parallelism] 小于 1 时按 1 处理（退化成串行），不抛异常 —— 调用方传的是策略常量，
 * 这里不该再让一个「宽度配错了」变成一次生成失败。
 */
internal suspend fun <T, R> parallelMapOrdered(
    items: List<T>,
    parallelism: Int,
    block: suspend (T) -> R,
): List<R> = coroutineScope {
    val gate = Semaphore(parallelism.coerceAtLeast(1))
    items.map { item -> async { gate.withPermit { block(item) } } }.awaitAll()
}
