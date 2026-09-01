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
        }
    }

    /** 当前池内条目数，只供日志 / 调试。 */
    val size: Int
        get() = synchronized(lock) { store.size }

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
            update(exist.copy(is_followed = true))
        }
    }

    fun unFollowUser(userId: Long) {
        get<User>(userId).value?.let { exist ->
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

    fun hasFullIllustVersion(illustId: Long): Boolean {
        return synchronized(lock) { ObjectKey(illustId, ObjectSpec.Illust) in fullVersionKeys }
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
            else -> {
                ObjectSpec.UNKNOWN
            }
        }
    }
}
