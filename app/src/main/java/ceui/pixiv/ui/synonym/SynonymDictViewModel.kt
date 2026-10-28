package ceui.pixiv.ui.synonym

import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import ceui.pixiv.db.synonym.SynonymDao
import ceui.pixiv.db.synonym.SynonymTagEntity
import ceui.pixiv.db.synonym.SynonymTargetEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.concurrent.Executors
import javax.inject.Inject

/**
 * 同义词词典管理页 ViewModel（issue #904）。
 *
 * 数据全部在这里：词典 LiveData、搜索词、展开状态、跳转目标开关；Fragment 只渲染 + 转发点击。
 *
 * 线程模型：词典/搜索词变化（主线程回调）→ 过滤+扁平化丢到 [rebuildExecutor]（导入预生成
 * 词典后可达数万条，主线程全量扫描会卡顿）→ postValue 回主线程；代次守卫丢弃过期结果。
 */
@HiltViewModel
class SynonymDictViewModel @Inject constructor(dao: SynonymDao) : ViewModel() {

    private val rebuildExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "synonym-dict-rebuild").apply { isDaemon = true }
    }

    @Volatile
    private var rebuildSeq = 0

    /** 搜索词（同时匹配目标标签名 / 同义词名 / 备注） */
    val searchQuery = MutableLiveData("")

    /** 单击跳转目标：false=插画/漫画收藏，true=小说收藏（issue：页面顶部切换） */
    val jumpToNovel = MutableLiveData(false)

    /** 单击跳转目标：false=公开收藏，true=私人收藏 */
    val jumpToPrivate = MutableLiveData(false)

    /** 已展开的目标标签 id（issue #905：默认折叠，导入内置词典后全量平铺有数万行） */
    private val expandedTargetIds = MutableLiveData<Set<Long>>(emptySet())

    private val allEntries = dao.getAllWithSynonymsLive()

    /** 扁平化后的树形列表（目标行 + 缩进同义词行），随词典 / 搜索词 / 展开状态变化自动重建 */
    val displayItems = MediatorLiveData<List<DictItem>>().apply {
        addSource(allEntries) { rebuild() }
        addSource(searchQuery) { rebuild() }
        addSource(expandedTargetIds) { rebuild() }
    }

    /** 展开 ↔ 折叠某个目标标签 */
    fun toggleExpanded(targetId: Long) {
        val current = expandedTargetIds.value.orEmpty()
        expandedTargetIds.value = if (targetId in current) current - targetId else current + targetId
    }

    /** 总数统计（目标标签数 to 同义词数），过滤前的全量 */
    val totalCount = MediatorLiveData<Pair<Int, Int>>().apply {
        addSource(allEntries) { list ->
            value = (list?.size ?: 0) to (list?.sumOf { it.synonyms.size } ?: 0)
        }
    }

    override fun onCleared() {
        rebuildExecutor.shutdownNow()
        super.onCleared()
    }

    private fun rebuild() {
        // 主线程只做快照，全量过滤+扁平化丢后台
        val query = searchQuery.value?.trim().orEmpty()
        val entries = allEntries.value.orEmpty()
        val expanded = expandedTargetIds.value.orEmpty()
        rebuildSeq++
        val seq = rebuildSeq
        rebuildExecutor.execute {
            if (seq != rebuildSeq) return@execute
            val items = ArrayList<DictItem>()
            entries.forEach { entry ->
                if (query.isEmpty()) {
                    // 无搜索词：目标行默认折叠，点击展开（issue #905 —— 内置词典数千组全量平铺不可读）
                    val isExpanded = entry.target.id in expanded
                    items.add(DictItem.Target(entry.target, entry.synonyms.size, isExpanded))
                    if (isExpanded) {
                        entry.synonyms.forEach { items.add(DictItem.Synonym(it)) }
                    }
                    return@forEach
                }

                val targetHit = entry.target.name.contains(query, ignoreCase = true)
                val hitSynonyms = entry.synonyms.filter {
                    it.name.contains(query, ignoreCase = true) ||
                            it.remark?.contains(query, ignoreCase = true) == true
                }
                // issue 搜索规则：无关结果隐藏，但保持从属关系与缩进；命中项自动展开（折叠会让结果不可见）
                if (targetHit || hitSynonyms.isNotEmpty()) {
                    items.add(DictItem.Target(entry.target, entry.synonyms.size, expanded = true))
                    // 目标自身命中 → 展示其全部同义词；只有同义词命中 → 只展示命中的
                    val toShow = if (targetHit) entry.synonyms else hitSynonyms
                    toShow.forEach { items.add(DictItem.Synonym(it)) }
                }
            }
            if (seq == rebuildSeq) {
                displayItems.postValue(items)
            }
        }
    }

    /** 树形列表的两种行 */
    sealed class DictItem {
        data class Target(
            val entity: SynonymTargetEntity,
            val synonymCount: Int,
            val expanded: Boolean,
        ) : DictItem()

        data class Synonym(val entity: SynonymTagEntity) : DictItem()
    }
}
