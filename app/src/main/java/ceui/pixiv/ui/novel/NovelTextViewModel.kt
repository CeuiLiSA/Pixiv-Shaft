package ceui.pixiv.ui.novel

import ceui.lisa.R
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ceui.lisa.activities.Shaft
import ceui.lisa.fragments.WebNovelParser
import ceui.lisa.utils.Common
import ceui.loxia.Client
import ceui.loxia.Novel
import ceui.loxia.ObjectPool
import ceui.loxia.RefreshHint
import ceui.loxia.RefreshState
import ceui.loxia.SpaceHolder
import ceui.loxia.WebNovel
import ceui.loxia.novel.NovelTextHolder
import ceui.pixiv.ui.chats.RedSectionHeaderHolder
import ceui.pixiv.ui.common.HoldersContainer
import ceui.pixiv.ui.common.ListItemHolder
import ceui.pixiv.ui.common.RefreshOwner
import ceui.pixiv.ui.detail.UserInfoHolder
import kotlinx.coroutines.launch
import timber.log.Timber

class NovelTextViewModel(
    private val novelId: Long,
) : ViewModel(), RefreshOwner, HoldersContainer {

    private val _itemHolders = MutableLiveData<List<ListItemHolder>>()
    private val _refreshState = MutableLiveData<RefreshState>()

    private val _webNovel = MutableLiveData<WebNovel>()
    val webNovel: LiveData<WebNovel> = _webNovel

    init {
        refresh(RefreshHint.InitialLoad)
    }

    override fun refresh(hint: RefreshHint) {
        viewModelScope.launch {
            try {
                _refreshState.value = RefreshState.LOADING(refreshHint = hint)
                val context = Shaft.getContext()
                val html = Client.appApi.getNovelText(novelId).string()
                val wNovel = WebNovelParser.parsePixivObject(html)?.novel

                val result = mutableListOf<ListItemHolder>()
                result.add(SpaceHolder())
                result.add(NovelHeaderHolder(novelId))
                result.add(RedSectionHeaderHolder(context.getString(R.string.string_432)))
                result.add(UserInfoHolder(ObjectPool.get<Novel>(novelId).value?.user?.id ?: 0L))
                result.add(RedSectionHeaderHolder("简介"))
                result.add(NovelCaptionHolder(novelId))
                result.add(RedSectionHeaderHolder("正文"))
                result.add(SpaceHolder())

                wNovel?.let {
                    (wNovel.text?.split("\n") ?: listOf()).forEach { oneLineText ->
                        result.addAll(
                            WebNovelParser.buildNovelHolders(wNovel, oneLineText)
                        )
                    }
                    _webNovel.value = it
                }
                result.add(SpaceHolder())
                result.add(NovelTextHolder("<===== End =====>", Common.getNovelTextColor()))
                result.add(SpaceHolder())

                _itemHolders.value = result
                _refreshState.value = RefreshState.LOADED(
                    hasContent = true, hasNext = false
                )
            } catch (ex: Exception) {
                _refreshState.value = RefreshState.ERROR(ex)
                Timber.e(ex)
            }
        }
    }

    override fun prepareIdMap(fragmentUniqueId: String) {

    }

    override val refreshState: LiveData<RefreshState>
        get() = _refreshState
    override val holders: LiveData<List<ListItemHolder>>
        get() = _itemHolders
}