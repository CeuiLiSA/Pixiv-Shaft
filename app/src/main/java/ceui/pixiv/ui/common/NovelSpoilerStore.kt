package ceui.pixiv.ui.common

import com.tencent.mmkv.MMKV
import java.util.concurrent.ConcurrentHashMap

/**
 * 「屏蔽此作品」的本地遮罩名单（小说版，对齐插画侧 [IllustSpoilerStore]）。
 *
 * 与 [ceui.lisa.helper.IllustNovelFilter] 的「屏蔽作品 ID」是两件事：那条链路是过滤——
 * 命中就整条从列表里删掉，用户再也找不回来；这里只是遮罩——条目留在原位置，长按菜单
 * 能取消，点一下卡片就揭开。
 *
 * 故意不开一张 MMKV 表跟插画混用：Pixiv 的插画/小说 id 虽然同属作品号段，但两边的
 * 语义（卡片渲染、揭开入口）各自独立，分开存零歧义，也碰不到插画侧已经跑起来的缓存。
 */
object NovelSpoilerStore {

    private const val MMKV_ID = "novel_spoiler_v1"

    private val store: MMKV by lazy { MMKV.mmkvWithID(MMKV_ID) }

    private val spoileredIds: MutableSet<Long> by lazy {
        ConcurrentHashMap.newKeySet<Long>().apply {
            store.allKeys()?.forEach { key -> key.toLongOrNull()?.let { add(it) } }
        }
    }

    fun isSpoilered(novelId: Long): Boolean = novelId > 0L && spoileredIds.contains(novelId)

    /**
     * 设置屏蔽状态。
     *
     * @return 状态是否**真的**变了。已经是目标态返回 false，调用方据此跳过重绑
     *（免得图片白重发一次 Glide 请求）。
     */
    fun setSpoilered(novelId: Long, spoilered: Boolean): Boolean {
        if (novelId <= 0L) return false
        val key = novelId.toString()
        return if (spoilered) {
            if (!spoileredIds.add(novelId)) return false
            store.encode(key, true)
            true
        } else {
            if (!spoileredIds.remove(novelId)) return false
            store.removeValueForKey(key)
            true
        }
    }
}
