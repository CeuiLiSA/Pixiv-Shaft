package ceui.pixiv.cache

import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import ceui.lisa.models.ModelObject
import ceui.lisa.models.ObjectSpec
import ceui.loxia.Novel
import ceui.loxia.User
import ceui.pixiv.api.model.Article
import ceui.pixiv.api.model.GifInfoResponse
import ceui.pixiv.api.model.Illust
import ceui.pixiv.api.model.UserPreview
import ceui.pixiv.api.model.UserResponse
import ceui.pixiv.plaza.PlazaPostCacheEntry
import com.google.gson.Gson
import java.io.Serializable
import kotlin.reflect.KClass


data class ObjectKey(
    val id: Long,
    val type: Int
) : Serializable

/**
 * 进程级的「同一实体只有一份可观察状态」池：列表页 / 详情页 / 收藏动作对同一 Illust、User、
 * Novel 的更新都汇到同一个 [MutableLiveData]，谁订阅谁就看到最新值。这是它必须是进程级的
 * 理由——它的价值就在于跨页面共享。
 *
 * ## 有界 + LRU
 *
 * 池按访问序 LRU，上限 [MAX_ENTRIES]。为什么要有上限：每条 Illust / Novel 连 caption、tags、
 * meta_pages 在内存里 ~10-15KB，一次长时间刷推荐/搜索会灌进上万条，无界的池会把这些对象在
 * 列表页早已销毁之后继续钉在内存里。2048 条 ≈ 70 页 × 30 条，够覆盖「从列表进详情再返回」
 * 这种共享场景；最坏情况 ~30MB，且大多数条目和仍存活的列表共享同一实例，实际增量远小于此。
 *
 * ## 被淘汰的条目的语义
 *
 * 淘汰只是把 `key → LiveData` 这条映射从池里移除，**不会**碰那份 LiveData 本身：仍在观察它的
 * 页面继续持有并收到自己那份的更新（不会崩、不会丢已有值）。为了不让「旧页面和新页面各看各的」
 * 发生，**仍有观察者的条目不会被淘汰**（见 [trimLocked]），被踢的只有已经没人看的。
 *
 * ## 线程
 *
 * 池内 map 由 [lock] 守护，任何线程 [get] / [update] 都不会再撞 ConcurrentModificationException；
 * 但 `LiveData.setValue` 仍只能在主线程调，所以 [update] 一律**在主线程**调用
 * （后台线程灌池的先例见 HistoryFeed：回主线程再喂）。
 */
object ObjectPool {

    /** 见类注释「有界 + LRU」。 */
    private const val MAX_ENTRIES = 2048

    private val lock = Any()

    /** 访问序 LinkedHashMap 实现 LRU；只在 [lock] 内碰。淘汰见 [trimLocked]。 */
    private val store: LinkedHashMap<ObjectKey, MutableLiveData<Any>> =
        LinkedHashMap(MAX_ENTRIES, 0.75f, true)

    /**
     * 超限时从最旧的一端起淘汰，但**跳过仍有观察者的条目**：详情 pager 里离屏页的 ViewModel
     * 会 `observeForever` 自己那份 LiveData，如果把它踢出池，翻回来时 Fragment 重新 [get]
     * 会拿到另一份新 LiveData，VM 和 Fragment 从此各看各的（收藏爱心不翻转、简介不渲染）。
     * 有人在看的对象不该被踢；没人看的才是真正的垃圾。极端情况下全池都有观察者则暂不淘汰。
     * 必须在 [lock] 内调用。
     */
    private fun trimLocked() {
        if (store.size <= MAX_ENTRIES) return
        val it = store.entries.iterator()
        while (store.size > MAX_ENTRIES && it.hasNext()) {
            val entry = it.next()
            if (entry.value.hasObservers()) continue
            it.remove()
            // 条目走了，「detail 确认过」的标记也没有存在意义（下次 update 会重新置）。
            fullVersionKeys.remove(entry.key)
            untrustedViewerKeys.remove(entry.key)
        }
    }

    /** 当前池内条目数，只供日志 / 调试。 */
    val size: Int
        get() = synchronized(lock) { store.size }

    /** Main thread: safety changes invalidate every retained plaza view, including offscreen details. */
    fun invalidatePlaza(viewerUid: Long, revision: Long) {
        val entries = synchronized(lock) {
            store.values.mapNotNull { live ->
                (live.value as? PlazaPostCacheEntry)?.takeIf { it.viewerUid == viewerUid }?.let { live to it }
            }
        }
        entries.forEach { (live, entry) ->
            live.value = PlazaPostCacheEntry(entry.id, viewerUid, null, System.currentTimeMillis(), revision)
        }
    }

    fun putUserPreview(preview: UserPreview) {
        preview.user?.let { user ->
            update(user)
        }

        preview.illusts?.forEach { illust ->
            update(illust)
        }
    }

    fun updateIllust(illust: Illust) {
        update(illust)
        illust.user?.let { user ->
            update(user)
        }
    }

    /**
     * @param illustId The id of specified illustration
     * @return
     * */
    fun getIllust(illustId: Long): LiveData<Illust> {
        return get(illustId)
    }

    fun getNovel(novelId: Long): LiveData<Novel> {
        return get(novelId)
    }

    fun updateUser(user: User) {
        update(user)
    }

    fun followUser(userId: Long) {
        get<User>(userId).value?.let { exist ->
            // 用户自己的关注动作（服务端确认 / 乐观 + 回滚）本身就是权威值。
            markViewerStateTrusted(userId, ObjectSpec.KUser)
            update(exist.copy(is_followed = true))
        }
    }

    fun unFollowUser(userId: Long) {
        get<User>(userId).value?.let { exist ->
            markViewerStateTrusted(userId, ObjectSpec.KUser)
            update(exist.copy(is_followed = false))
        }
    }

    /**
     * @param id The id of the illustration
     * @return
     * */
    inline fun <reified ObjectT : ModelObject> get(id: Long): LiveData<ObjectT> {
        return getFromMap(ObjectT::class, id)
    }

    /**
     * @param objClass The data source
     * @param id The id of the illustration
     * @return
     * */
    fun <ObjectT : ModelObject> getFromMap(objClass: KClass<ObjectT>, id: Long): LiveData<ObjectT> {
        val key = ObjectKey(id, findObjectSpec(objClass))
        @Suppress("UNCHECKED_CAST")
        return synchronized(lock) {
            store.getOrPut(key) { MutableLiveData<Any>() }.also { trimLocked() }
        } as LiveData<ObjectT>
    }

    inline fun <reified ObjectT : ModelObject> update(obj: ObjectT, isFullVersion: Boolean = false) {
        return updateObjectPool(obj, isFullVersion)
    }

    /** 主线程调用（内部走 `setValue`，见类注释「线程」）。 */
    fun <ObjectT : ModelObject> updateObjectPool(obj: ObjectT, isFullVersion: Boolean) {
        val key = ObjectKey(obj.objectUniqueId, obj.objectType)
        val storedObject: MutableLiveData<Any>
        val created: Boolean
        synchronized(lock) {
            if (isFullVersion) {
                fullVersionKeys.add(key)
                // detail 整体覆盖 = 当前用户视角的权威值，之前的不可信标记到此为止。
                untrustedViewerKeys.remove(key)
            }
            val existing = store[key]
            created = existing == null
            storedObject = existing ?: MutableLiveData<Any>(obj).also { store[key] = it; trimLocked() }
        }
        if (!created) {
            // setValue 放在锁外：observer 回调里可能再 get/update，锁内回调会重入同一把锁
            // (synchronized 可重入不死锁，但没必要把 UI 回调关在锁里)。
            try {
                val lastValue = storedObject.value
                // lastValue === obj:同一实例(典型 followUser 原地改字段后再 update),
                // merge 自己跟自己无意义,直接赋值以保留 observer 通知,省掉 Gson 开销。
                storedObject.value = if (isFullVersion || lastValue == null || lastValue === obj) {
                    obj
                } else {
                    mergeKeepingExisting(obj.javaClass, lastValue, obj)
                }
            } catch (ex: Exception) {
                storedObject.postValue(obj)
            }
        }
        Log.d("updateObjectPool", "对象池大小：$size")
    }

    /**
     * 收到过 isFullVersion=true(detail 接口整体覆盖)更新的 key。详情页用它区分
     * 「列表接口不定期掐掉的空 caption」和「detail 确认过的真无简介」:前者要回源补拉,
     * 后者不该反复白拉(#960,见 [hasTrustedCaption])。进程内存活即可,
     * 重启后代价不过是每个空简介作品多拉一次 detail。随条目一起 LRU 淘汰;只在 [lock] 内碰。
     */
    private val fullVersionKeys = mutableSetOf<ObjectKey>()

    /** 见下方「viewer 态可信度」。与 [fullVersionKeys] 同生命周期：随条目一起 LRU 淘汰。只在 [lock] 内碰。 */
    private val untrustedViewerKeys = mutableSetOf<ObjectKey>()

    fun hasFullIllustVersion(illustId: Long): Boolean {
        return synchronized(lock) { ObjectKey(illustId, ObjectSpec.Illust) in fullVersionKeys }
    }

    // ── viewer 态可信度 ────────────────────────────────────────────────────────
    //
    // 池里的 `is_bookmarked` / `user.is_followed` 只有在**当前用户视角**下才成立：官方列表接口、
    // detail 接口、用户自己的收藏 / 关注动作产出的值才算数；自建源（shaft-api-v2）上报快照、
    // 借号搜索、浏览历史快照里的是上报者 / 借来账号的状态。
    //
    // 脏值一旦进池就**不会自愈**：详情页用 isFullDetail() 短路回源（图片字段齐全就一次 detail
    // 都不发），所以进程内没有任何机会把它改回来，只能重启。因此：
    //  - 写侧：这类来源进池前一律清成「未知」（null，不是 false）并打上不可信标记；
    //  - 读侧：标记不可信的条目，收藏 / 关注态改由本地镜像「只升不降」地补（见
    //    [ceui.pixiv.db.mirror.BookmarkMirrorService.bookmarkedAmong]）。
    //
    // 标记随条目一起 LRU 淘汰；detail 整体覆盖（isFullVersion）与用户自己的收藏 / 关注动作
    // 都会把它清掉 —— 那两种写入本身就是权威的。

    /** 这条池条目的 viewer 态不是当前用户的视角，读之前必须先问镜像。 */
    fun markViewerStateUntrusted(id: Long, type: Int) {
        synchronized(lock) { untrustedViewerKeys.add(ObjectKey(id, type)) }
    }

    /** 这条池条目的 viewer 态已被权威来源确认（detail 覆盖 / 用户自己的动作）。 */
    fun markViewerStateTrusted(id: Long, type: Int) {
        synchronized(lock) { untrustedViewerKeys.remove(ObjectKey(id, type)) }
    }

    fun isViewerStateTrusted(id: Long, type: Int): Boolean {
        return synchronized(lock) { ObjectKey(id, type) !in untrustedViewerKeys }
    }

    fun isIllustViewerStateTrusted(illustId: Long): Boolean =
        isViewerStateTrusted(illustId, ObjectSpec.Illust)

    /**
     * 把一条**来源不可信**的作品放进池：收藏 / 关注态清成 null（未知）并打标记。
     *
     * 作者条目只在池里还没有它的时候才打标记 —— 已有的那条可能是画师主页 / detail 写进来的
     * 权威值，不能因为一张榜单卡经过就被降级。
     */
    fun seedUntrusted(illust: Illust) {
        val sanitized = illust.withoutViewerState()
        val authorId = sanitized.user?.id ?: 0L
        val authorAbsent = authorId > 0L && get<User>(authorId).value == null
        markViewerStateUntrusted(sanitized.id, ObjectSpec.Illust)
        updateIllust(sanitized)
        if (authorAbsent) {
            markViewerStateUntrusted(authorId, ObjectSpec.KUser)
        }
    }

    /**
     * 官方列表页合池：写入后把 viewer 态重新标成可信。
     *
     * 标记的语义是「**最近一次写入者**是否权威」：列表 bean 在各自 source 里已经洗过（自建源
     * 页面一律不喂池、借号页清成未知），所以这里写进去的收藏 / 关注态要么是当前用户视角、
     * 要么是未知，两种都不该再被镜像的「不知道」覆盖。少了这一下，官方列表刚补进来的真值会被
     * 详情页的镜像校正抹回 null（镜像没就绪时就是「明明收藏了却一直灰心」）。
     */
    fun updateIllustTrusted(illust: Illust) {
        updateIllust(illust)
        markViewerStateTrusted(illust.id, ObjectSpec.Illust)
    }

    /**
     * 用镜像结论覆盖池里这条作品的 viewer 态，**绕过 [mergeKeepingExisting]**。
     *
     * 为什么要绕过：merge 只把 null 当空值，false 会照盖。镜像的语义是「只升不降」——
     * 有就是 true，没有就写 null（未知），绝不写 false。整体替换这两个字段才能把历史上
     * 混进来的脏 true 真正抹掉。
     *
     * 主线程调用（内部 `setValue`，见类注释「线程」）。
     */
    fun applyMirroredViewerState(illustId: Long, bookmarked: Boolean?, followed: Boolean?) {
        val illustLive = synchronized(lock) { store[ObjectKey(illustId, ObjectSpec.Illust)] } ?: return
        val current = illustLive.value as? Illust ?: return
        val author = current.user
        // 值没变就别发（LiveData 不看相等性，每次都全量通知观察者）。
        val nextIllust = current.copy(
            is_bookmarked = bookmarked,
            user = author?.copy(is_followed = followed),
        )
        if (nextIllust != current) {
            try {
                illustLive.value = nextIllust
            } catch (ex: Exception) {
                illustLive.postValue(nextIllust)
            }
        }
        if (author == null) return
        val userLive = synchronized(lock) { store[ObjectKey(author.id, ObjectSpec.KUser)] } ?: return
        val currentUser = userLive.value as? User ?: return
        if (currentUser.is_followed == followed) return
        val nextUser = currentUser.copy(is_followed = followed)
        try {
            userLive.value = nextUser
        } catch (ex: Exception) {
            userLive.postValue(nextUser)
        }
    }

    private val gson: Gson = Gson()

    /**
     * 列表接口返回的是「精简版」对象，往往缺少 detail 接口才有的字段（典型：caption）。
     * 池里已存在更完整的旧值时，新值只用来「补充」自己实际带值的字段，绝不让空/缺失的字段
     * 覆盖旧值的非空字段。这样后到的精简列表更新（如作者其他作品、用户作品列表）不会把
     * 已经展示出来的简介等抹掉。isFullVersion=true 的 detail 更新仍走整体覆盖。
     */
    private fun <T : Any> mergeKeepingExisting(clazz: Class<T>, old: Any, fresh: T): T {
        return try {
            val oldJson = gson.toJsonTree(old).asJsonObject
            val freshJson = gson.toJsonTree(fresh).asJsonObject
            for ((key, oldValue) in oldJson.entrySet()) {
                if (oldValue == null || oldValue.isJsonNull) continue
                val freshValue = freshJson.get(key)
                val freshIsBlank = freshValue == null || freshValue.isJsonNull ||
                    (freshValue.isJsonPrimitive && freshValue.asJsonPrimitive.isString && freshValue.asString.isEmpty()) ||
                    (freshValue.isJsonArray && freshValue.asJsonArray.size() == 0)
                if (freshIsBlank) {
                    freshJson.add(key, oldValue)
                }
            }
            gson.fromJson(freshJson, clazz) ?: fresh
        } catch (ex: Exception) {
            fresh
        }
    }

    private fun <ObjectT : ModelObject> findObjectSpec(objClass: KClass<ObjectT>): Int {
        // Class names are not a stable type discriminator: release R8 is allowed to rename
        // Illust/Novel/User, which made simpleName fall through to UNKNOWN and split reads from
        // writes into different ObjectPool keys. Compare class identities so R8 can safely rename
        // the models without changing detail-page behavior.
        return when (objClass) {
            Novel::class -> {
                // 不能跟 Illust 共用类型：插画/小说 ID 各自独立，撞键会让
                // get<Novel> 取到 Illust 直接 ClassCastException。
                ObjectSpec.KNovel
            }
            Illust::class -> {
                ObjectSpec.Illust
            }
            User::class -> {
                ObjectSpec.KUser
            }
            Article::class -> {
                ObjectSpec.ARTICLE
            }
            GifInfoResponse::class -> {
                ObjectSpec.GIF_INFO
            }
            UserResponse::class -> {
                ObjectSpec.UserProfile
            }
            PlazaPostCacheEntry::class -> {
                ObjectSpec.PLAZA_POST
            }
            else -> {
                ObjectSpec.UNKNOWN
            }
        }
    }
}
