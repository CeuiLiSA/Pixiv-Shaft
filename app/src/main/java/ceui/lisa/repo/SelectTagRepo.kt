package ceui.lisa.repo

import ceui.lisa.activities.Shaft
import ceui.lisa.core.RemoteRepo
import ceui.lisa.database.AppDatabase
import ceui.lisa.http.Retro
import ceui.lisa.model.ListBookmarkTag
import ceui.lisa.model.ListTag
import ceui.lisa.models.TagsBean
import ceui.lisa.utils.Params
import ceui.pixiv.db.synonym.SynonymMatcher
import ceui.lisa.core.ResponseMapper

class SelectTagRepo(
        private val id: Int,
        private val type: String,
        private val tagNames: List<String>,
) : RemoteRepo<ListBookmarkTag>() {

    var listTag: ListTag? = null

    override suspend fun initApi(): ListBookmarkTag {
        val api = Retro.getAppApiSuspend()
        // 先拉用户全量收藏标签存进 listTag（mapper 勾选要用），再拉本作品已打的标签（对齐旧 flatMap 顺序）。
        return when (type) {
            Params.TYPE_ILLUST -> {
                listTag = api.getAllIllustBookmarkTags(currentUserID(), Params.TYPE_PUBLIC)
                api.getIllustBookmarkTags(id)
            }
            Params.TYPE_NOVEL -> {
                listTag = api.getAllNovelBookmarkTags(currentUserID(), Params.TYPE_PUBLIC)
                api.getNovelBookmarkTags(id)
            }
            else -> throw IllegalArgumentException("unknown type $type")
        }
    }

    override suspend fun initNextApi(): ListBookmarkTag? {
        return null
    }

    override fun mapper(): ResponseMapper<ListBookmarkTag> {
        return ResponseMapper { listBookmarkTag ->
            val tags = listBookmarkTag.list
            if (listTag != null) {
                tags.forEach { tag ->
                    if (listTag!!.list.any { t -> t.name == tag.name } && tagNames.contains(tag.name)) {
                        tag.isSelected = true
                    }
                }
            }
            // 同义词词典（issue #904）核心闭环：作品标签命中词典 → 对应目标标签（=收藏标签）自动勾选。
            // mapper 由 RemoteRepo.loadFirst 在 IO 上跑，DB 全量读不会阻塞 UI。
            // 词典是增强功能：任何异常（DB 锁/迁移中等）都不能把整个收藏标签列表拖垮成 onError。
            try {
                applySynonymMatching(tags)
            } catch (e: Exception) {
                timber.log.Timber.e(e, "synonym matching failed, skipped")
            }

            listBookmarkTag
        }
    }

    /**
     * 用户收藏标签列表里已有同名目标标签 → 勾选；没有 → 作为新标签插到列表顶部并勾选。
     */
    private fun applySynonymMatching(tags: MutableList<TagsBean>) {
        // 功能总开关（issue #904）默认关闭：关闭时按标签收藏页与本功能存在之前完全一致
        if (!Shaft.sSettings.isSynonymDictEnabled) {
            return
        }
        if (tagNames.isEmpty()) {
            return
        }
        val dict = AppDatabase.getAppDatabase(Shaft.getContext()).synonymDao().getAllWithSynonyms()
        if (dict.isEmpty()) {
            return
        }
        SynonymMatcher.matchedTargetNames(tagNames, dict).forEach { targetName ->
            val existing = tags.firstOrNull { it.name == targetName }
            if (existing != null) {
                existing.isSelected = true
            } else {
                val newTag = TagsBean()
                newTag.name = targetName
                newTag.count = 0
                newTag.isSelected = true
                tags.add(0, newTag)
            }
        }
    }
}
