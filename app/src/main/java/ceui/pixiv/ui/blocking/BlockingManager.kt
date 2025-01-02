package ceui.pixiv.ui.blocking

import com.tencent.mmkv.MMKV

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.map

object BlockingManager {
    private const val BLOCKED_WORKS_KEY = "blocked_works"
    private const val BLOCKED_USERS_KEY = "blocked_users"
    private const val BLOCKED_TAGS_KEY = "blocked_tags"
    private val prefStore: MMKV by lazy { MMKV.mmkvWithID("blocking-map") }

    // 使用 LiveData 保存屏蔽数据
    private val _blockedWorks = MutableLiveData<Set<Long>>(emptySet())
    private val _blockedUsers = MutableLiveData<Set<Long>>(emptySet())
    private val _blockedTags = MutableLiveData<Set<String>>(emptySet())

    val blockedWorks: LiveData<Set<Long>> get() = _blockedWorks
    val blockedUsers: LiveData<Set<Long>> get() = _blockedUsers
    val blockedTags: LiveData<Set<String>> get() = _blockedTags

    // 初始化方法
    fun initialize() {
        _blockedWorks.value = prefStore.decodeStringSet(BLOCKED_WORKS_KEY)?.map { it.toLong() }?.toSet() ?: emptySet()
        _blockedUsers.value = prefStore.decodeStringSet(BLOCKED_USERS_KEY)?.map { it.toLong() }?.toSet() ?: emptySet()
        _blockedTags.value = prefStore.decodeStringSet(BLOCKED_TAGS_KEY) ?: emptySet()
    }

    // 添加屏蔽条目
    fun addBlockedWork(workId: Long) {
        val updatedWorks = (_blockedWorks.value ?: emptySet()).plus(workId)
        _blockedWorks.value = updatedWorks
        saveToStorage(BLOCKED_WORKS_KEY, updatedWorks.map { it.toString() }.toSet())
    }

    fun addBlockedUser(userId: Long) {
        val updatedUsers = (_blockedUsers.value ?: emptySet()).plus(userId)
        _blockedUsers.value = updatedUsers
        saveToStorage(BLOCKED_USERS_KEY, updatedUsers.map { it.toString() }.toSet())
    }

    fun addBlockedTag(tagName: String) {
        val updatedTags = (_blockedTags.value ?: emptySet()).plus(tagName)
        _blockedTags.value = updatedTags
        saveToStorage(BLOCKED_TAGS_KEY, updatedTags)
    }

    // 删除屏蔽条目
    fun removeBlockedWork(workId: Long) {
        val updatedWorks = (_blockedWorks.value ?: emptySet()).minus(workId)
        _blockedWorks.value = updatedWorks
        saveToStorage(BLOCKED_WORKS_KEY, updatedWorks.map { it.toString() }.toSet())
    }

    fun removeBlockedUser(userId: Long) {
        val updatedUsers = (_blockedUsers.value ?: emptySet()).minus(userId)
        _blockedUsers.value = updatedUsers
        saveToStorage(BLOCKED_USERS_KEY, updatedUsers.map { it.toString() }.toSet())
    }

    fun removeBlockedTag(tagName: String) {
        val updatedTags = (_blockedTags.value ?: emptySet()).minus(tagName)
        _blockedTags.value = updatedTags
        saveToStorage(BLOCKED_TAGS_KEY, updatedTags)
    }

    // 检查屏蔽状态
    fun isWorkBlocked(workId: Long): LiveData<Boolean> = _blockedWorks.map { it.contains(workId) }
    fun isUserBlocked(userId: Long): LiveData<Boolean> = _blockedUsers.map { it.contains(userId) }
    fun isTagBlocked(tagName: String): LiveData<Boolean> = _blockedTags.map { it.contains(tagName) }

    // 保存数据到 MMKV
    private fun saveToStorage(key: String, data: Set<String>) {
        prefStore.encode(key, data)
    }
}
