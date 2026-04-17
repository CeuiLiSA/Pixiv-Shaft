package ceui.pixiv.ui.detail

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ceui.lisa.activities.Shaft
import ceui.lisa.database.AppDatabase
import ceui.loxia.Client
import ceui.loxia.Comment
import ceui.loxia.Illust
import ceui.loxia.ObjectPool
import ceui.loxia.UserResponse
import ceui.pixiv.db.RecordType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

class ArtworkV3ViewModel(
    private val illustId: Long
) : ViewModel() {

    val illustLiveData: LiveData<Illust> = ObjectPool.get<Illust>(illustId)

    private val _comments = MutableLiveData<List<Comment>>()
    val comments: LiveData<List<Comment>> = _comments

    private val _relatedIllusts = MutableLiveData<List<Illust>>()
    val relatedIllusts: LiveData<List<Illust>> = _relatedIllusts

    private val _authorWorks = MutableLiveData<List<Illust>>()
    val authorWorks: LiveData<List<Illust>> = _authorWorks

    private val _userProfile = MutableLiveData<UserResponse?>()
    val userProfile: LiveData<UserResponse?> = _userProfile

    private val _isLoading = MutableLiveData(true)
    val isLoading: LiveData<Boolean> = _isLoading

    val detailExpanded = MutableLiveData(true)

    init {
        loadData()
    }

    private fun loadData() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                // Load illust if not in pool
                val illust = ObjectPool.get<Illust>(illustId).value ?: run {
                    withContext(Dispatchers.IO) {
                        val context = Shaft.getContext()
                        val entity = AppDatabase.getAppDatabase(context).generalDao()
                            .getByRecordTypeAndId(RecordType.VIEW_ILLUST_HISTORY, illustId)
                        entity?.typedObject<Illust>()?.also { ObjectPool.update(it) }
                    }
                } ?: run {
                    Client.appApi.getIllust(illustId).illust?.also { ObjectPool.update(it) }
                } ?: return@launch

                val userId = illust.user?.id ?: return@launch

                // Parallel: comments, related, author works, user profile
                val commentsDeferred = async {
                    try {
                        Client.appApi.getIllustComments(illustId).comments.take(3)
                    } catch (e: Exception) {
                        Timber.e(e)
                        emptyList()
                    }
                }

                val relatedDeferred = async {
                    try {
                        Client.appApi.getRelatedIllusts(illustId).illusts
                    } catch (e: Exception) {
                        Timber.e(e)
                        emptyList()
                    }
                }

                val authorWorksDeferred = async {
                    try {
                        Client.appApi.getUserCreatedIllusts(userId, "illust").illusts
                            .filter { it.id != illustId }
                            .take(10)
                    } catch (e: Exception) {
                        Timber.e(e)
                        emptyList()
                    }
                }

                val profileDeferred = async {
                    try {
                        Client.appApi.getUserProfile(userId)
                    } catch (e: Exception) {
                        Timber.e(e)
                        null
                    }
                }

                _comments.value = commentsDeferred.await()
                _relatedIllusts.value = relatedDeferred.await()
                _authorWorks.value = authorWorksDeferred.await()
                _userProfile.value = profileDeferred.await()
            } catch (e: Exception) {
                Timber.e(e)
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun refresh() {
        loadData()
    }
}
