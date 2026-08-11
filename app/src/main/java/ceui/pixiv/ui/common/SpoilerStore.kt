package ceui.pixiv.ui.common

import com.tencent.mmkv.MMKV
import java.util.concurrent.ConcurrentHashMap

/**
 * 「屏蔽此作品」的本地遮罩名单：被屏蔽的作品仍然留在列表里，只是卡片图换成 Glide 模糊图 +
 * 叠一层 [ceui.pixiv.widget.SpoilerParticleView] 粒子（Telegram spoiler 的观感）。
 *
 * 与 [ceui.lisa.helper.IllustNovelFilter] 的「屏蔽作品 ID」是两件事，故意不共用一张表：
 * 那条链路是**过滤**——命中就整条从列表里删掉，用户再也找不回来，也没有「揭开看一眼」的余地；
 * 这里是**遮罩**——条目还在原位，长按菜单能取消，点一下卡片就揭开。混进同一份名单会让
 * 「取消屏蔽」无处下手（条目早被过滤掉了，UI 上根本不存在）。
 *
 * 存 MMKV 而不是 Settings：这是设备本地的观感偏好，不该跟着 Settings 跨设备同步（同
 * [ceui.pixiv.ui.synonym.SynonymBuiltinDict] 里那条理由）。key 就是作品 id 的十进制串，
 * 值恒为 true —— 「不在名单里」即未屏蔽，取消屏蔽直接删 key，不留 false 墓碑。
 *
 * 全表在首次访问时一次性读进内存 Set：判定发生在列表 bind 的热路径上（fling 时每秒几十次），
 * 不该每次都过一趟 JNI。写入同时更新内存与磁盘，两边不会分家。
 *
 * 插画与小说各持一个实例（[IllustSpoilerStore] / [NovelSpoilerStore]），**表必须分开**：
 * 插画 id 与小说 id 是两条互相独立的自增序列，同号完全可能，合表会让屏蔽一本小说顺带糊掉
 * 一张同号插画。
 *
 * 依赖 `Shaft.onCreate` 里的 `MMKV.initialize()` 先跑过（同 ComicReaderProgressStore）。
 */
open class SpoilerStore(private val mmkvId: String) {

    private val store: MMKV by lazy { MMKV.mmkvWithID(mmkvId) }

    /** 线程安全的 Set：读在主线程（bind），写也在主线程（菜单/点击），但不值得为此赌上一次 CME。 */
    private val spoileredIds: MutableSet<Long> by lazy {
        ConcurrentHashMap.newKeySet<Long>().apply {
            store.allKeys()?.forEach { key -> key.toLongOrNull()?.let { add(it) } }
        }
    }

    fun isSpoilered(workId: Long): Boolean = workId > 0L && spoileredIds.contains(workId)

    /**
     * 设置屏蔽态。
     *
     * @return 状态是否**真的**变了。已经是目标态返回 false，调用方据此跳过重绑——
     * 幂等守卫放在这里，省得每个入口各写一遍（对齐 [IllustFeedItem.withBookmarked] 的做法）。
     */
    fun setSpoilered(workId: Long, spoilered: Boolean): Boolean {
        if (workId <= 0L) return false
        val key = workId.toString()
        return if (spoilered) {
            if (!spoileredIds.add(workId)) return false
            store.encode(key, true)
            true
        } else {
            if (!spoileredIds.remove(workId)) return false
            store.removeValueForKey(key)
            true
        }
    }
}

/** 插画卡（recy_illust_stagger）的遮罩名单。 */
object IllustSpoilerStore : SpoilerStore("illust_spoiler_v1")

/** 小说卡（recy_novel）的遮罩名单。 */
object NovelSpoilerStore : SpoilerStore("novel_spoiler_v1")
