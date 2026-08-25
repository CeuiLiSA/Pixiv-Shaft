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
import io.reactivex.Observable
import ceui.lisa.core.ResponseMapper

class SelectTagRepo(
        private val id: Int,
        private val type: String,
        private val tagNames: List<String>,
) : RemoteRepo<ListBookmarkTag>() {

    var listTag: ListTag? = null

    override fun initApi(): Observable<ListBookmarkTag> {

        var api1: Observable<ListBookmarkTag>? = null
        var api2: Observable<ListTag>? = null

        when(type){
            Params.TYPE_ILLUST -> {
                api1 = Retro.getAppApi().getIllustBookmarkTags(id)
                api2 = Retro.getAppApi().getAllIllustBookmarkTags(currentUserID(), Params.TYPE_PUBLIC)
            }
            Params.TYPE_NOVEL -> {
                api1 = Retro.getAppApi().getNovelBookmarkTags(id)
                api2 = Retro.getAppApi().getAllNovelBookmarkTags(currentUserID(), Params.TYPE_PUBLIC)
            }
        }

        return api2!!.flatMap(
            fun(listTag: ListTag): Observable<ListBookmarkTag> {
                this.listTag = listTag
                return api1!!
            }
        )
    }

    override fun initNextApi(): Observable<ListBookmarkTag>? {
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
            // mapper 跑在 Rx 后台线程（subscribeOn 之后、observeOn(main) 之前），DB 全量读不会阻塞 UI。
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
