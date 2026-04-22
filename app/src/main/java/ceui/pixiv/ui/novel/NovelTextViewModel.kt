package ceui.pixiv.ui.novel

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.lisa.fragments.WebNovelParser
import ceui.loxia.Client
import ceui.loxia.ObjectPool
import ceui.loxia.RefreshHint
import ceui.loxia.RefreshState
import ceui.loxia.SpaceHolder
import ceui.loxia.WebNovel
import ceui.pixiv.ui.chats.RedSectionHeaderHolder
import ceui.pixiv.ui.common.HoldersViewModel
import ceui.pixiv.ui.common.ListItemHolder
import ceui.pixiv.ui.detail.UserInfoHolder
import ceui.pixiv.ui.novel.reader.NovelTextCache
import ceui.pixiv.ui.novel.reader.paginate.ContentParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import timber.log.Timber

class NovelTextViewModel(
    private val novelId: Long,
) : HoldersViewModel() {

    private val _webNovel = MutableLiveData<WebNovel>()
    val webNovel: LiveData<WebNovel> = _webNovel

    init {
        refresh(RefreshHint.InitialLoad)
    }

    override suspend fun refreshImpl(hint: RefreshHint) {
        super.refreshImpl(hint)
        val context = Shaft.getContext()
        coroutineScope {
            Client.appApi.getNovel(novelId).novel?.let {
                ObjectPool.update(it)
                it.user?.let { user ->
                    ObjectPool.update(user)
                }
            }
        }

        // 任务 #3：把核心按钮区以上的 1~5 排成固定长度的"定海神针"布局。
        // 顺序：标题+系列 → 作者 → 作品档案 → 功能按钮 → 标签 → 简介。
        val result = mutableListOf<ListItemHolder>()
        result.add(NovelHeaderHolder(novelId))
        result.add(UserInfoHolder(ObjectPool.get<ceui.loxia.Novel>(novelId).value?.user?.id ?: 0L))
        result.add(NovelProfileHolder(novelId))
        result.add(NovelActionsHolder(novelId))
        result.add(RedSectionHeaderHolder(context.getString(R.string.novel_section_tags)))
        result.add(NovelTagsHolder(novelId))
        result.add(RedSectionHeaderHolder(context.getString(R.string.novel_section_caption)))
        result.add(NovelCaptionHolder(novelId))
        result.add(SpaceHolder())
        result.add(SpaceHolder())

        _itemHolders.value = result
        _refreshState.value = RefreshState.LOADED(hasContent = true, hasNext = false)

        // Fire-and-forget: 后台预热 V3 reader 要用的数据（拉 HTML → 解析
        // WebNovel → tokenize → 落 NovelTextCache），用户看完详情点"开始阅读"
        // 时秒开。缓存命中则直接跳过，避免重复拉。
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                if (NovelTextCache.get(novelId) != null) return@runCatching
                val html = Client.appApi.getNovelText(novelId).string()
                val web = WebNovelParser.parsePixivObject(html)?.novel ?: return@runCatching
                val tokens = ContentParser.tokenize(web)
                NovelTextCache.put(novelId, NovelTextCache.Entry(web, tokens))
                _webNovel.postValue(web)
            }.onFailure {
                Timber.tag("NovelTextViewModel").w(it, "prewarm failed novelId=$novelId")
            }
        }
    }
}
