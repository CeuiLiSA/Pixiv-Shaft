package ceui.pixiv.ui.bulk

import ceui.lisa.models.IllustsBean

/**
 * 跨 Activity 临时传递 illust 列表的轻量 holder（避免序列化整个 list 走 Intent extras）。
 * 原来的 DataChannel 已删除，这是替代品。
 *
 * 用法：
 *   1. 入口处（IAdapter / TagAdapter 等长按弹批量下载）
 *      → BulkSelectStorage.put(list) → 启动 TemplateActivity("批量选择")
 *   2. BulkSelectV3Fragment.onViewCreated → BulkSelectStorage.consume()
 *      取出列表（同时清空 holder，避免内存泄漏 / 旧数据复用）
 */
object BulkSelectStorage {
    @Volatile private var pendingItems: List<IllustsBean>? = null

    fun put(items: List<IllustsBean>) {
        pendingItems = items.toList() // 防御性拷贝
    }

    /** 取出列表并清空 holder。返回 null 说明流程异常（直接退出 fragment）。 */
    fun consume(): List<IllustsBean>? {
        val items = pendingItems
        pendingItems = null
        return items
    }
}
