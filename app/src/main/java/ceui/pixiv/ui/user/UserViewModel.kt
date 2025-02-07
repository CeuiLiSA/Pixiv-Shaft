package ceui.pixiv.ui.user

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.map
import androidx.lifecycle.viewModelScope
import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.lisa.utils.Params
import ceui.loxia.Client
import ceui.loxia.Illust
import ceui.loxia.ObjectPool
import ceui.loxia.RefreshHint
import ceui.loxia.RefreshState
import ceui.loxia.User
import ceui.loxia.combineLatest
import ceui.loxia.findActionReceiverOrNull
import ceui.loxia.waitForValue
import ceui.pixiv.ui.chats.RedSectionHeaderHolder
import ceui.pixiv.ui.chats.SeeMoreAction
import ceui.pixiv.ui.chats.SeeMoreType
import ceui.pixiv.ui.common.HoldersContainer
import ceui.pixiv.ui.common.IllustsValueContent
import ceui.pixiv.ui.common.ListItemHolder
import ceui.pixiv.ui.common.RefreshOwner
import ceui.pixiv.ui.common.ValueContent
import ceui.pixiv.ui.detail.ArtworksMap
import kotlinx.coroutines.launch
import timber.log.Timber
import kotlin.collections.addAll

class UserViewModel(private val userId: Long) : ViewModel(), RefreshOwner, HoldersContainer {

    private val _userLiveData = ObjectPool.get<User>(userId)
    val userLiveData: LiveData<User> = _userLiveData

    private val _itemHolders = MutableLiveData<List<ListItemHolder>>()
    override val holders: LiveData<List<ListItemHolder>>
        get() = _itemHolders

    val userProfile = ValueContent(viewModelScope, { Client.appApi.getUserProfile(userId) })
    private val userCreatedIllusts = IllustsValueContent(
        viewModelScope,
        { Client.appApi.getUserCreatedIllusts(userId, Params.TYPE_ILLUST) })
    private val userCreatedManga = IllustsValueContent(
        viewModelScope,
        { Client.appApi.getUserCreatedIllusts(userId, Params.TYPE_MANGA) })
    private val userBookmarkedIllusts = IllustsValueContent(
        viewModelScope,
        { Client.appApi.getUserBookmarkedIllusts(userId, Params.TYPE_PUBLIC) })

    val previewWorksIds = combineLatest(
        userCreatedIllusts.result,
        userBookmarkedIllusts.result
    ).map { (created, bookmarked) ->
        val idList = mutableListOf<Long>()
        created?.illusts?.take(9)?.map {
            ObjectPool.update(it)
            it.id
        }?.let {
            idList.addAll(it)
        }
        bookmarked?.illusts?.take(9)?.map {
            ObjectPool.update(it)
            it.id
        }?.let {
            idList.addAll(it)
        }
        idList
    }

    val blurBackground: LiveData<Illust?>
        get() {
            return userCreatedIllusts.result.map { created ->
                created.illusts.getOrNull(userId.mod(10))?.also { target ->
                    ObjectPool.update(target)
                }
            }
        }

    override fun prepareIdMap(fragmentUniqueId: String) {
        previewWorksIds.value?.let {
            ArtworksMap.store[fragmentUniqueId] = it
        }
    }

    private val _refreshState = MutableLiveData<RefreshState>()

    override val refreshState: LiveData<RefreshState>
        get() = _refreshState

    override fun refresh(hint: RefreshHint) {
        viewModelScope.launch {
            try {
                _refreshState.value = RefreshState.LOADING(refreshHint = hint)
                userProfile.refresh(RefreshHint.InitialLoad)
                userCreatedIllusts.refresh(RefreshHint.InitialLoad)
                userCreatedManga.refresh(RefreshHint.InitialLoad)
                userBookmarkedIllusts.refresh(RefreshHint.InitialLoad)
                val result = mutableListOf<ListItemHolder>()
                result.add(
                    RedSectionHeaderHolder(
                        "Created Illust",
                        type = SeeMoreType.USER_CREATED_ILLUST,
                        liveEndText = userProfile.result.map { resp ->
                            Shaft.getContext().getString(
                                R.string.all_works_count,
                                resp.profile?.total_illusts ?: 0
                            )
                        }).onItemClick { sender ->
                        sender.findActionReceiverOrNull<SeeMoreAction>()?.seeMore(SeeMoreType.USER_CREATED_ILLUST)
                    }
                )
                result.add(SectionPreviewHolder(userCreatedIllusts))

                result.add(
                    RedSectionHeaderHolder(
                        "Created Manga",
                        type = SeeMoreType.USER_CREATED_MANGA,
                        liveEndText = userProfile.result.map { resp ->
                            Shaft.getContext().getString(
                                R.string.all_works_count,
                                resp.profile?.total_manga ?: 0
                            )
                        }).onItemClick { sender ->
                        sender.findActionReceiverOrNull<SeeMoreAction>()?.seeMore(SeeMoreType.USER_CREATED_MANGA)
                    }
                )
                result.add(SectionPreviewHolder(userCreatedManga))


                result.add(
                    RedSectionHeaderHolder("Bookmarked", type = SeeMoreType.USER_BOOKMARKED_ILLUST,
                        liveEndText = MutableLiveData(Shaft.getContext().getString(R.string.string_167))).onItemClick { sender ->
                        sender.findActionReceiverOrNull<SeeMoreAction>()?.seeMore(SeeMoreType.USER_BOOKMARKED_ILLUST)
                    }
                )
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
    }
}