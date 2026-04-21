package ceui.pixiv.ui.detail

import android.os.Handler
import android.os.Looper
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
import io.reactivex.Observable
import io.reactivex.schedulers.Schedulers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import timber.log.Timber
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class ArtworkV3ViewModel(
    private val illustId: Long
) : ViewModel() {

    // ── internal state ──
    private var illustBean: IllustsBean? = null
    private var fullUser: ceui.lisa.models.UserBean? = null
    private var comments: List<Comment>? = null
    private var authorWorks: List<IllustsBean>? = null
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

    var relatedNextUrl: String? = null
        private set
    private val _isLoadingRelated = MutableLiveData(false)
    val isLoadingRelated: LiveData<Boolean> = _isLoadingRelated
    private var isLoadingMore = false
    val hasMoreRelated: Boolean get() = !relatedNextUrl.isNullOrEmpty() && !isLoadingMore

    // ── ObjectPool observers ──
    private val illustBeanLiveData = ObjectPool.get<IllustsBean>(illustId)
    private var userBeanLiveData: LiveData<ceui.lisa.models.UserBean>? = null

    // Coalesce multiple triggers (illust observer + user observer + loadData end)
    // fired within the same main-thread turn into a single header rebuild.
    private val mainHandler = Handler(Looper.getMainLooper())
    private val rebuildRunnable = Runnable { doBuildHeaderItems() }

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
        mainHandler.removeCallbacks(rebuildRunnable)
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
        mainHandler.removeCallbacks(rebuildRunnable)
        mainHandler.post(rebuildRunnable)
    }

    private fun doBuildHeaderItems() {
        val illust = illustBean ?: return
        val list = mutableListOf<ArtworkDetailItem>()

        list.add(ArtworkDetailItem.Hero(illust))

        if (illust.series != null && !TextUtils.isEmpty(illust.series.title)) {
            list.add(ArtworkDetailItem.Series(illust))
        }

        list.add(ArtworkDetailItem.Artist(illust, fullUser))

        if (!TextUtils.isEmpty(illust.caption)) {
            list.add(ArtworkDetailItem.Desc(illust.caption))
        }

        list.add(ArtworkDetailItem.Tags(illust))
        list.add(ArtworkDetailItem.Stats(illust))
        list.add(ArtworkDetailItem.DetailPanel(illust))

        comments?.let {
            list.add(ArtworkDetailItem.Comments(it, illust.id, illust.title ?: ""))
        }
        authorWorks?.takeIf { it.isNotEmpty() }?.let {
            list.add(
                ArtworkDetailItem.AuthorWorks(
                    it,
                    illust.user?.name ?: "",
                    illust.user?.id ?: 0
                )
            )
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
                _isLoadingRelated.value = true
                val relatedD = async(Dispatchers.IO) {
                    runCatching {
                        val body = Client.appApi.generalGet(
                            "https://app-api.pixiv.net/v2/illust/related?illust_id=$illustId"
                        )
                        gson.fromJson(body.string(), ListIllust::class.java)
                    }.getOrElse { Timber.e(it); null }
                }
                val authorD = async(Dispatchers.IO) {
                    runCatching {
                        val resp = ceui.lisa.http.Retro.getAppApi()
                            .getUserSubmitIllust(userId.toInt(), "illust")
                            .awaitFirst()
                        resp.list?.filter { it.id != illustId.toInt() }?.take(10) ?: emptyList()
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
                    _isLoadingRelated.value = false
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
        _isLoadingRelated.value = true

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
                _isLoadingRelated.value = false
            }
        }
    }
}

/**
 * Bridge Rx2 Observable to a suspend function without pulling in kotlinx-coroutines-rx2.
 * Runs the subscription on Schedulers.io so the calling coroutine thread is freed while waiting.
 */
private suspend fun <T : Any> Observable<T>.awaitFirst(): T = suspendCancellableCoroutine { cont ->
    val disposable = subscribeOn(Schedulers.io())
        .firstOrError()
        .subscribe(
            { cont.resume(it) },
            { cont.resumeWithException(it) }
        )
    cont.invokeOnCancellation { disposable.dispose() }
}
