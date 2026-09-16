package ceui.pixiv.snapshot

import ceui.pixiv.api.model.Illust

/** 有界的在飞作品表：去重合并触发来源，出队时按各自开关判定。 */
internal class AutoSnapshotPendingRequests(private val capacity: Int) {
    class Request(val id: Long)
    data class Ready(val illust: Illust, val behaviorSignal: String?)

    private class Entry(
        val request: Request,
        var illust: Illust,
        var bookmark: Boolean,
        var behaviorSignal: String?,
    )

    private val entries = mutableMapOf<Long, Entry>()

    /** 只有新作品返回执行凭证；同作品合并来源与最新元数据，不另起协程。 */
    @Synchronized
    fun add(illust: Illust, signal: String?): Request? {
        entries[illust.id]?.let { entry ->
            entry.illust = illust
            entry.bookmark = entry.bookmark || signal == null
            entry.behaviorSignal = signal ?: entry.behaviorSignal
            return null
        }
        if (illust.id <= 0L || entries.size >= capacity) return null
        val request = Request(illust.id)
        entries[illust.id] = Entry(request, illust, signal == null, signal)
        return request
    }

    @Synchronized
    fun ready(request: Request, bookmarkEnabled: Boolean, behaviorEnabled: Boolean): Ready? {
        val entry = entries[request.id]?.takeIf { it.request === request } ?: return null
        if (!(entry.bookmark && bookmarkEnabled) && !(entry.behaviorSignal != null && behaviorEnabled)) {
            // 判定跳过与释放名额必须原子完成，让紧接着到来的有效信号能重新入队。
            entries.remove(request.id)
            return null
        }
        return Ready(entry.illust, entry.behaviorSignal.takeIf { behaviorEnabled })
    }

    @Synchronized
    fun finish(request: Request) {
        // 被跳过的旧协程 finally 不能移除同 ID 后来重新入队的请求。
        if (entries[request.id]?.request === request) entries.remove(request.id)
    }
}
