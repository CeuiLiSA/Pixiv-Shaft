package ceui.pixiv.ui.detail

import android.text.TextUtils
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ceui.lisa.activities.Shaft
import ceui.lisa.database.AppDatabase
import ceui.lisa.model.ListIllust
import ceui.lisa.models.IllustsBean
import ceui.loxia.Client
import ceui.loxia.Comment
import ceui.loxia.Illust
import ceui.loxia.ObjectPool
import ceui.pixiv.db.RecordType
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

class ArtworkV3ViewModel(
    private val illustId: Long
) : ViewModel() {

    // ── internal state ──
    private var illustBean: IllustsBean? = null
    private var fullUser: ceui.lisa.models.UserBean? = null
    private var comments: List<Comment>? = null
    private var authorWorks: List<Illust>? = null
    private val gson = Gson()

    // ── output: header sections ──
    private val _headerItems = MutableLiveData<List<ArtworkDetailItem>>()
    val headerItems: LiveData<List<ArtworkDetailItem>> = _headerItems

    // ── output: related illusts (for IAdapter) ──
    private val relatedList = mutableListOf<IllustsBean>()
    private val _relatedIllusts = MutableLiveData<List<IllustsBean>>()
    val relatedIllusts: LiveData<List<IllustsBean>> = _relatedIllusts

    private val _isBookmarked = MutableLiveData<Boolean>()
    val isBookmarked: LiveData<Boolean> = _isBookmarked

    private var relatedNextUrl: String? = null
    private var isLoadingMore = false
    val hasMoreRelated: Boolean get() = !relatedNextUrl.isNullOrEmpty() && !isLoadingMore

    // ── ObjectPool observers ──
    private val illustBeanLiveData = ObjectPool.get<IllustsBean>(illustId)
    private var userBeanLiveData: LiveData<ceui.lisa.models.UserBean>? = null

    private val illustBeanObserver = Observer<IllustsBean> { bean ->
        if (bean != null) {
            illustBean = bean
            _isBookmarked.value = bean.isIs_bookmarked
            observeUserIfNeeded(bean.user?.id ?: 0)
            buildHeaderItems()
        }
    }

    private val userBeanObserver = Observer<ceui.lisa.models.UserBean> { user ->
        if (user != null) {
            fullUser = user
            buildHeaderItems()
        }
    }

    init {
        illustBeanLiveData.observeForever(illustBeanObserver)
        loadData()
    }

    override fun onCleared() {
        illustBeanLiveData.removeObserver(illustBeanObserver)
        userBeanLiveData?.removeObserver(userBeanObserver)
    }

    private fun observeUserIfNeeded(userId: Int) {
        if (userId <= 0 || userBeanLiveData != null) return
        val ld = ObjectPool.get<ceui.lisa.models.UserBean>(userId.toLong())
        userBeanLiveData = ld
        ld.value?.let { fullUser = it }
        ld.observeForever(userBeanObserver)
    }

    // ── build header item list (everything except individual related cards) ──
    private fun buildHeaderItems() {
        val illust = illustBean ?: return
        val list = mutableListOf<ArtworkDetailItem>()

        list.add(ArtworkDetailItem.Hero(illust))

        if (illust.series != null && !TextUtils.isEmpty(illust.series.title)) {
            list.add(ArtworkDetailItem.Series(illust))
        }
        if (!TextUtils.isEmpty(illust.caption)) {
            list.add(ArtworkDetailItem.Desc(illust.caption))
        }

        list.add(ArtworkDetailItem.Stats(illust))
        list.add(ArtworkDetailItem.Tags(illust))
        list.add(ArtworkDetailItem.Artist(illust, fullUser))
        list.add(ArtworkDetailItem.DetailPanel(illust))

        comments?.let {
            list.add(ArtworkDetailItem.Comments(it, illust.id, illust.title ?: ""))
        }
        authorWorks?.takeIf { it.isNotEmpty() }?.let {
            list.add(ArtworkDetailItem.AuthorWorks(it, illust.user?.name ?: ""))
        }
        if (relatedList.isNotEmpty()) {
            list.add(ArtworkDetailItem.RelatedHeader(illust.id, illust.title ?: ""))
        }

        _headerItems.value = list
    }

    // ── data loading ──
    private fun loadData() {
        viewModelScope.launch {
            try {
                val illust = ObjectPool.get<Illust>(illustId).value ?: run {
                    withContext(Dispatchers.IO) {
                        val ctx = Shaft.getContext()
                        AppDatabase.getAppDatabase(ctx).generalDao()
                            .getByRecordTypeAndId(RecordType.VIEW_ILLUST_HISTORY, illustId)
                            ?.typedObject<Illust>()?.also { ObjectPool.update(it) }
                    }
                } ?: run {
                    Client.appApi.getIllust(illustId).illust?.also { ObjectPool.update(it) }
                } ?: return@launch

                val userId = illust.user?.id ?: return@launch

                val commentsD = async(Dispatchers.IO) {
                    runCatching { Client.appApi.getIllustComments(illustId).comments.take(3) }
                        .getOrElse { Timber.e(it); emptyList() }
                }
                val relatedD = async(Dispatchers.IO) {
                    runCatching {
                        // Parse as ListIllust to get List<IllustsBean> directly
                        val body = Client.appApi.generalGet(
                            "https://app-api.pixiv.net/v2/illust/related?illust_id=$illustId"
                        )
                        gson.fromJson(body.string(), ListIllust::class.java)
                    }.getOrElse { Timber.e(it); null }
                }
                val authorD = async(Dispatchers.IO) {
                    runCatching {
                        Client.appApi.getUserCreatedIllusts(userId, "illust").illusts
                            .filter { it.id != illustId }.take(10)
                    }.getOrElse { Timber.e(it); emptyList() }
                }
                val profileD = async(Dispatchers.IO) {
                    runCatching { Client.appApi.getUserProfile(userId) }
                        .getOrElse { Timber.e(it); null }
                }

                comments = commentsD.await()

                relatedD.await()?.let { resp ->
                    relatedList.clear()
                    relatedList.addAll(resp.list ?: emptyList())
                    relatedNextUrl = resp.next_url
                    _relatedIllusts.value = relatedList.toList()
                }

                authorWorks = authorD.await().ifEmpty { null }

                profileD.await()?.user?.let { u ->
                    illustBean?.user?.let { existing ->
                        existing.isIs_followed = u.is_followed == true
                        existing.comment = u.comment
                        ObjectPool.updateUser(existing)
                    }
                }

                buildHeaderItems()
            } catch (e: Exception) {
                Timber.e(e)
            }
        }
    }

    fun loadMoreRelated() {
        val url = relatedNextUrl
        if (url.isNullOrEmpty() || isLoadingMore) return
        isLoadingMore = true

        viewModelScope.launch(Dispatchers.Main) {
            try {
                val resp = withContext(Dispatchers.IO) {
                    val body = Client.appApi.generalGet(url)
                    gson.fromJson(body.string(), ListIllust::class.java)
                }
                relatedNextUrl = resp.next_url
                resp.list?.let { newItems ->
                    relatedList.addAll(newItems)
                    _relatedIllusts.value = relatedList.toList()
                }
            } catch (e: Exception) {
                Timber.e(e)
            } finally {
                isLoadingMore = false
            }
        }
    }
}
