package ceui.pixiv.ui.novel

import ceui.lisa.R
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
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
import kotlinx.coroutines.coroutineScope

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
        val html = Client.appApi.getNovelText(novelId).string()
        val wNovel = WebNovelParser.parsePixivObject(html)?.novel

        val result = mutableListOf<ListItemHolder>()
        result.add(SpaceHolder())
        result.add(NovelHeaderHolder(novelId))
        result.add(RedSectionHeaderHolder(context.getString(R.string.string_432)))
        result.add(UserInfoHolder(ObjectPool.get<ceui.loxia.Novel>(novelId).value?.user?.id ?: 0L))
        result.add(RedSectionHeaderHolder("简介"))
        result.add(NovelCaptionHolder(novelId))
        result.add(SpaceHolder())
        result.add(ReadNovelButtonHolder(novelId))
        result.add(SpaceHolder())

        wNovel?.let { _webNovel.value = it }

        _itemHolders.value = result
        _refreshState.value = RefreshState.LOADED(hasContent = true, hasNext = false)
    }
}
