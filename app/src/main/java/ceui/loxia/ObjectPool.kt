package ceui.loxia

import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.google.gson.Gson
import ceui.lisa.models.IllustsBean
import ceui.lisa.models.ModelObject
import ceui.lisa.models.NovelBean
import ceui.lisa.models.ObjectSpec
import ceui.lisa.models.UserBean
import java.io.Serializable
import kotlin.collections.set
import kotlin.reflect.KClass


data class ObjectKey(
    val id: Long,
    val type: Int
) : Serializable

/**
 * Object pool in Android is a software design pattern that involves reusing objects that are expensive to create or configure. It's essentially a collection of initialized objects that can be readily used by the application, reducing the overhead of creating new objects all the time.
 * */
object ObjectPool {

    val store = mutableMapOf<ObjectKey, MutableLiveData<Any>>()

    fun putUserPreview(preview: UserPreview) {
        preview.user?.let { user ->
            update(user)
        }

        preview.illusts?.forEach { illust ->
            update(illust)
        }
    }

    fun updateIllust(illust: IllustsBean) {
        update(illust)
        illust.user?.let { user ->
            update(user)
        }
    }

    /**
     * @param illustId The id of specified illustration
     * @return
     * */
    fun getIllust(illustId: Long): LiveData<IllustsBean> {
        return get(illustId)
    }

    fun getNovel(novelId: Long): LiveData<NovelBean> {
        return get(novelId)
    }

    fun updateUser(userBean: UserBean) {
        update(userBean)
    }

    fun followUser(userId: Long) {
        get<UserBean>(userId).value?.let { exist ->
            exist.isIs_followed = true
            update(exist)
        }
        get<User>(userId).value?.let { exist ->
            update(exist.copy(is_followed = true))
        }
    }

    fun unFollowUser(userId: Long) {
        get<UserBean>(userId).value?.let { exist ->
            exist.isIs_followed = false
            update(exist)
        }
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
        val storedLiveData = store[key]
        return (if (storedLiveData == null) {
            val newly = MutableLiveData<Any>()
            store[key] = newly
            newly
        } else {
            storedLiveData
        }) as LiveData<ObjectT>
    }

    inline fun <reified ObjectT : ModelObject> update(obj: ObjectT, isFullVersion: Boolean = false) {
        return updateObjectPool(obj, isFullVersion)
    }

    inline fun <reified ObjectT : ModelObject> updateObjectPool(obj: ObjectT, isFullVersion: Boolean) {
        val key = ObjectKey(obj.objectUniqueId, obj.objectType)
        if (isFullVersion) {
            fullVersionKeys.add(key)
        }
        val storedObject = store[key]
        if (storedObject == null) {
            store[key] = MutableLiveData(obj)
        } else {
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
        Log.d("updateObjectPool", "对象池大小：${store.size}")
    }

    /**
     * 收到过 isFullVersion=true(detail 接口整体覆盖)更新的 key。详情页用它区分
     * 「列表接口不定期掐掉的空 caption」和「detail 确认过的真无简介」:前者要回源补拉,
     * 后者不该反复白拉(#960,见 [hasTrustedCaption])。进程内存活即可,
     * 重启后代价不过是每个空简介作品多拉一次 detail。
     */
    @PublishedApi
    internal val fullVersionKeys = mutableSetOf<ObjectKey>()

    fun hasFullIllustVersion(illustId: Long): Boolean {
        return ObjectKey(illustId, ObjectSpec.POST) in fullVersionKeys
    }

    @PublishedApi
    internal val gson: Gson = Gson()

    /**
     * 列表接口返回的是「精简版」对象，往往缺少 detail 接口才有的字段（典型：caption）。
     * 池里已存在更完整的旧值时，新值只用来「补充」自己实际带值的字段，绝不让空/缺失的字段
     * 覆盖旧值的非空字段。这样后到的精简列表更新（如作者其他作品、用户作品列表）不会把
     * 已经展示出来的简介等抹掉。isFullVersion=true 的 detail 更新仍走整体覆盖。
     */
    @PublishedApi
    internal fun <T : Any> mergeKeepingExisting(clazz: Class<T>, old: Any, fresh: T): T {
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
        val classSimpleName = objClass.simpleName ?: return ObjectSpec.UNKNOWN
        return when (classSimpleName) {
            "IllustsBean" -> {
                ObjectSpec.POST
            }
            "Novel" -> {
                // 不能跟 IllustsBean 共用 POST：插画/小说 ID 各自独立，撞键会让
                // get<Novel> 取到 IllustsBean 直接 ClassCastException。
                ObjectSpec.KNovel
            }
            "Illust" -> {
                ObjectSpec.Illust
            }
            "UserBean" -> {
                ObjectSpec.USER
            }
            "User" -> {
                ObjectSpec.KUser
            }
            "Article" -> {
                ObjectSpec.ARTICLE
            }
            "GifInfoResponse" -> {
                ObjectSpec.GIF_INFO
            }
            "UserResponse" -> {
                ObjectSpec.UserProfile
            }
            else -> {
                ObjectSpec.UNKNOWN
            }
        }
    }
}