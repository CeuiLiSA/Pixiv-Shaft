package ceui.pixiv.ui.user

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.map
import androidx.lifecycle.viewModelScope
import ceui.lisa.utils.Params
import ceui.loxia.Client
import ceui.loxia.Illust
import ceui.loxia.ObjectPool
import ceui.loxia.RefreshHint
import ceui.loxia.RefreshState
import ceui.loxia.User
import ceui.loxia.combineLatest
import ceui.pixiv.ui.chats.RedSectionHeaderHolder
import ceui.pixiv.ui.common.HoldersContainer
import ceui.pixiv.ui.common.ListItemHolder
import ceui.pixiv.ui.common.RefreshOwner
import ceui.pixiv.ui.common.ValueContent
import kotlinx.coroutines.launch
import timber.log.Timber

class UserViewModel(private val userId: Long) : ViewModel(), RefreshOwner, HoldersContainer {

    private val _userLiveData = ObjectPool.get<User>(userId)
    val userLiveData: LiveData<User> = _userLiveData

    private val _itemHolders = MutableLiveData<List<ListItemHolder>>()
    override val holders: LiveData<List<ListItemHolder>>
        get() = _itemHolders

    private val userCreatedIllusts = ValueContent(viewModelScope, { Client.appApi.getUserCreatedIllusts(userId, Params.TYPE_ILLUST) })
    private val userBookmarkedIllusts = ValueContent(viewModelScope, { Client.appApi.getUserBookmarkedIllusts(userId, Params.TYPE_PUBLIC) })
    val blurBackground: LiveData<Illust?> get() {
        return combineLatest(userCreatedIllusts.result, userBookmarkedIllusts.result).map { (created, bookmarked) ->
            (created?.illusts?.getOrNull(userId.mod(10)) ?:
            bookmarked?.illusts?.getOrNull(userId.mod(10)))?.also { target ->
            ObjectPool.update(target)
        } }
    }

    override fun prepareIdMap(fragmentUniqueId: String) {

    }

    private val _refreshState = MutableLiveData<RefreshState>()

    override val refreshState: LiveData<RefreshState>
        get() = _refreshState

    override fun refresh(hint: RefreshHint) {
        viewModelScope.launch {
            try {
                _refreshState.value = RefreshState.LOADING(refreshHint = hint)
                val result = mutableListOf<ListItemHolder>()
                result.add(RedSectionHeaderHolder("Created"))
                result.add(SectionPreviewHolder(userCreatedIllusts))
                result.add(RedSectionHeaderHolder("Bookmarked"))
                result.add(SectionPreviewHolder(userBookmarkedIllusts))
                result.add(RedSectionHeaderHolder("Bio"))
                result.add(SectionPreviewHolder(userCreatedIllusts))
                result.add(RedSectionHeaderHolder("Tags"))
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

    init {
        refresh(RefreshHint.InitialLoad)
        userCreatedIllusts.refresh(RefreshHint.InitialLoad)
        userBookmarkedIllusts.refresh(RefreshHint.InitialLoad)
    }
}