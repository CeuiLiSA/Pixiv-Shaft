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
import ceui.loxia.SpaceHolder
import ceui.loxia.User
import ceui.loxia.UserResponse
import ceui.loxia.combineLatest
import ceui.loxia.findActionReceiverOrNull
import ceui.pixiv.ui.chats.RedSectionHeaderHolder
import ceui.pixiv.ui.chats.SeeMoreAction
import ceui.pixiv.ui.chats.SeeMoreType
import ceui.pixiv.ui.common.HoldersContainer
import ceui.pixiv.ui.common.KListShowValueContent
import ceui.pixiv.ui.common.ListItemHolder
import ceui.pixiv.ui.common.RefreshOwner
import ceui.pixiv.ui.detail.ArtworksMap
import kotlinx.coroutines.launch
import timber.log.Timber

class UserViewModel(private val userId: Long) : ViewModel(), RefreshOwner, HoldersContainer {

    private val _userLiveData = ObjectPool.get<User>(userId)
    val userLiveData: LiveData<User> = _userLiveData

    private val _itemHolders = MutableLiveData<List<ListItemHolder>>()
    override val holders: LiveData<List<ListItemHolder>>
        get() = _itemHolders

    private val _userProfile = MutableLiveData<UserResponse>()
    val userProfile: LiveData<UserResponse> = _userProfile

    companion object {
        const val ILLUST_PREVIEW_COUNT = 9
        const val NOVEL_PREVIEW_COUNT = 3
    }

    private val userCreatedIllusts = KListShowValueContent(
        viewModelScope,
        { Client.appApi.getUserCreatedIllusts(userId, Params.TYPE_ILLUST) })
    private val userCreatedManga = KListShowValueContent(
        viewModelScope,
        { Client.appApi.getUserCreatedIllusts(userId, Params.TYPE_MANGA) })
    private val userBookmarkedIllusts = KListShowValueContent(
        viewModelScope,
        { Client.appApi.getUserBookmarkedIllusts(userId, Params.TYPE_PUBLIC) })
    private val userCreatedNovels = KListShowValueContent(
        viewModelScope,
        { Client.appApi.getUserCreatedNovels(userId) })

    val previewWorksIds = combineLatest(
        userCreatedIllusts.result,
        userBookmarkedIllusts.result
    ).map { (created, bookmarked) ->
        val idList = mutableListOf<Long>()
        created?.illusts?.take(ILLUST_PREVIEW_COUNT)?.map {
            ObjectPool.update(it)
            it.id
        }?.let {
            idList.addAll(it)
        }
        bookmarked?.illusts?.take(ILLUST_PREVIEW_COUNT)?.map {
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
                val profileResp = Client.appApi.getUserProfile(userId)
                _userProfile.value = profileResp
                userBookmarkedIllusts.refresh(RefreshHint.InitialLoad)
                val result = mutableListOf<ListItemHolder>()

                if ((profileResp.profile?.total_illusts ?: 0) > 0) {
                    result.add(
                        RedSectionHeaderHolder(
                            "Created Illust",
                            type = SeeMoreType.USER_CREATED_ILLUST,
                            liveEndText = _userProfile.map { resp ->
                                Shaft.getContext().getString(
                                    R.string.all_works_count,
                                    resp.profile?.total_illusts ?: 0
                                )
                            }).onItemClick { sender ->
                            sender.findActionReceiverOrNull<SeeMoreAction>()?.seeMore(SeeMoreType.USER_CREATED_ILLUST)
                        }
                    )
                    result.add(SectionPreviewHolder(userCreatedIllusts, ILLUST_PREVIEW_COUNT))
                    userCreatedIllusts.refresh(RefreshHint.InitialLoad)
                }

                if ((profileResp.profile?.total_manga ?: 0) > 0) {
                    result.add(
                        RedSectionHeaderHolder(
                            "Created Manga",
                            type = SeeMoreType.USER_CREATED_MANGA,
                            liveEndText = _userProfile.map { resp ->
                                Shaft.getContext().getString(
                                    R.string.all_works_count,
                                    resp.profile?.total_manga ?: 0
                                )
                            }).onItemClick { sender ->
                            sender.findActionReceiverOrNull<SeeMoreAction>()?.seeMore(SeeMoreType.USER_CREATED_MANGA)
                        }
                    )
                    result.add(SectionPreviewHolder(userCreatedManga, ILLUST_PREVIEW_COUNT))
                    userCreatedManga.refresh(RefreshHint.InitialLoad)
                }

                result.add(
                    RedSectionHeaderHolder("Bookmarked", type = SeeMoreType.USER_BOOKMARKED_ILLUST,
                        liveEndText = MutableLiveData(Shaft.getContext().getString(R.string.string_167))).onItemClick { sender ->
                        sender.findActionReceiverOrNull<SeeMoreAction>()?.seeMore(SeeMoreType.USER_BOOKMARKED_ILLUST)
                    }
                )
                result.add(SectionPreviewHolder(userBookmarkedIllusts, ILLUST_PREVIEW_COUNT))

                if ((profileResp.profile?.total_novels ?: 0) > 0) {
                    result.add(
                        RedSectionHeaderHolder(
                            "Created Novel",
                            type = SeeMoreType.USER_CREATED_NOVEL,
                            liveEndText = _userProfile.map { resp ->
                                Shaft.getContext().getString(
                                    R.string.all_works_count,
                                    resp.profile?.total_novels ?: 0
                                )
                            }).onItemClick { sender ->
                            sender.findActionReceiverOrNull<SeeMoreAction>()?.seeMore(SeeMoreType.USER_CREATED_NOVEL)
                        }
                    )
                    result.add(NovelPreviewHolder(userCreatedNovels, NOVEL_PREVIEW_COUNT))
                    userCreatedNovels.refresh(RefreshHint.InitialLoad)
                }

                if (profileResp.user?.comment?.isNotEmpty() == true) {
                    result.add(RedSectionHeaderHolder("Bio"))
                    result.add(UserTextHolder(profileResp.user.comment))
                }

                if (profileResp.profile?.region?.isNotEmpty() == true) {
                    result.add(RedSectionHeaderHolder("Region"))
                    result.add(UserTextHolder(profileResp.profile.region))
                }

                if (profileResp.profile?.twitter_url?.isNotEmpty() == true) {
                    result.add(XLinkHolder(profileResp.profile.twitter_url))
                }

                if (profileResp.profile?.job?.isNotEmpty() == true) {
                    result.add(RedSectionHeaderHolder("Job"))
                    result.add(UserTextHolder(profileResp.profile.job))
                }

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

    init {
        refresh(RefreshHint.InitialLoad)
    }
}