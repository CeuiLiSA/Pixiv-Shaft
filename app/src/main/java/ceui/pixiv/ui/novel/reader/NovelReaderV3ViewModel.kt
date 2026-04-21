package ceui.pixiv.ui.novel.reader

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import ceui.lisa.activities.Shaft
import ceui.lisa.database.AppDatabase
import ceui.lisa.database.NovelAnnotationEntity
import ceui.lisa.database.NovelBookmarkEntity
import ceui.lisa.fragments.WebNovelParser
import ceui.loxia.Client
import ceui.loxia.Novel
import ceui.loxia.ObjectPool
import ceui.loxia.WebNovel
import ceui.pixiv.ui.novel.reader.model.ContentToken
import ceui.pixiv.ui.novel.reader.model.Page
import ceui.pixiv.ui.novel.reader.model.PageGeometry
import ceui.pixiv.ui.novel.reader.paginate.ContentParser
import ceui.pixiv.ui.novel.reader.paginate.ImageResolver
import ceui.pixiv.ui.novel.reader.paginate.Paginator
import ceui.pixiv.ui.novel.reader.paginate.TextMeasurer
import ceui.pixiv.ui.novel.reader.paginate.TypeStyle
import ceui.pixiv.ui.novel.reader.feature.SearchEngine
import ceui.pixiv.ui.novel.reader.model.SearchHit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

class NovelReaderV3ViewModel(
    val novelId: Long,
) : ViewModel() {

    sealed class LoadState {
        object Idle : LoadState()
        object Loading : LoadState()
        data class Loaded(
            val novel: Novel?,
            val webNovel: WebNovel,
            val tokens: List<ContentToken>,
        ) : LoadState()
        data class Error(val message: String) : LoadState()
    }

    data class PaginationState(
        val pages: List<Page>,
        val startPageIndex: Int,
        val style: TypeStyle,
        val geometry: PageGeometry,
    )

    private val db = AppDatabase.getAppDatabase(Shaft.getContext())
    private val annotationDao = db.novelAnnotationDao()
    private val bookmarkDao = db.novelBookmarkDao()

    private val _loadState = MutableLiveData<LoadState>(LoadState.Idle)
    val loadState: LiveData<LoadState> = _loadState

    private val _pagination = MutableLiveData<PaginationState?>(null)
    val pagination: LiveData<PaginationState?> = _pagination

    private val _currentPageIndex = MutableLiveData<Int>(0)
    val currentPageIndex: LiveData<Int> = _currentPageIndex

    /** Live list of highlights / notes the user has added to this novel. */
    val annotations: LiveData<List<NovelAnnotationEntity>> = annotationDao.observeForNovel(novelId)

    /** User-placed position bookmarks ("save my spot here"). */
    val bookmarks: LiveData<List<NovelBookmarkEntity>> = bookmarkDao.observeForNovel(novelId)

    /** Full-text search state, driven by the Fragment's search overlay. Kept
     *  here so the result survives configuration changes (rotation). */
    data class SearchResult(
        val hits: List<SearchHit>,
        val currentIndex: Int,
    ) {
        val total: Int get() = hits.size
        val currentHit: SearchHit? get() = hits.getOrNull(currentIndex)

        companion object {
            val EMPTY = SearchResult(emptyList(), -1)
        }
    }

    private val _searchResult = MutableLiveData(SearchResult.EMPTY)
    val searchResult: LiveData<SearchResult> = _searchResult

    private var webNovel: WebNovel? = null
    private var tokens: List<ContentToken> = emptyList()
    private var imageResolver: (ContentToken) -> String? = { null }

    private var pendingStyle: TypeStyle? = null
    private var pendingGeometry: PageGeometry? = null
    private var desiredCharIndex: Int = 0
    private var paginationJob: Job? = null

    fun load() {
        if (_loadState.value is LoadState.Loading) return
        _loadState.value = LoadState.Loading
        viewModelScope.launch {
            runCatching {
                val novel = ObjectPool.get<Novel>(novelId).value
                    ?: Client.appApi.getNovel(novelId).novel?.also { ObjectPool.update(it) }
                // 详情页进来时已经预热了 webNovel + tokens，命中就跳过网络 +
                // 解析。miss 就自己拉，完成后顺手回填缓存，用户下次再进秒开。
                val cached = NovelTextCache.get(novelId)
                val parsed = if (cached != null) {
                    cached.webNovel to cached.tokens
                } else {
                    val html = Client.appApi.getNovelText(novelId).string()
                    withContext(Dispatchers.Default) {
                        val web = WebNovelParser.parsePixivObject(html)?.novel ?: error("解析 HTML 失败")
                        val toks = ContentParser.tokenize(web)
                        NovelTextCache.put(novelId, NovelTextCache.Entry(web, toks))
                        web to toks
                    }
                }
                webNovel = parsed.first
                tokens = parsed.second
                imageResolver = ImageResolver.of(parsed.first)
                desiredCharIndex = ReaderProgressStore.loadCharIndex(novelId)
                _loadState.postValue(LoadState.Loaded(novel, parsed.first, parsed.second))
                repaginateIfReady()
            }.onFailure { throwable ->
                Timber.tag("NovelReaderV3").e(throwable)
                _loadState.postValue(LoadState.Error(throwable.message ?: "加载失败"))
            }
        }
    }

    fun updateLayout(style: TypeStyle, geometry: PageGeometry) {
        val prevStyle = pendingStyle
        val prevGeom = pendingGeometry
        pendingStyle = style
        pendingGeometry = geometry
        if (style == prevStyle && geometry == prevGeom) return
        repaginateIfReady()
    }

    fun onPageChanged(index: Int) {
        _currentPageIndex.postValue(index)
        val pages = _pagination.value?.pages ?: return
        val page = pages.getOrNull(index) ?: return
        desiredCharIndex = page.charStart
        ReaderProgressStore.saveProgress(novelId, page.charStart, index, pages.size)
    }

    fun jumpToCharIndex(charIndex: Int) {
        desiredCharIndex = charIndex
        val pages = _pagination.value?.pages ?: return
        val target = pages.indexOfFirst { it.charEnd >= charIndex }.coerceAtLeast(0)
        _currentPageIndex.postValue(target)
    }

    // ---- Annotation CRUD --------------------------------------------------

    fun addHighlight(charStart: Int, charEnd: Int, excerpt: String, colorArgb: Int) {
        viewModelScope.launch {
            annotationDao.insert(
                NovelAnnotationEntity(
                    novelId = novelId,
                    charStart = charStart,
                    charEnd = charEnd,
                    excerpt = excerpt.take(500),
                    note = "",
                    color = colorArgb,
                    kind = NovelAnnotationEntity.KIND_HIGHLIGHT,
                ),
            )
        }
    }

    fun saveNote(
        annotationId: Long,
        charStart: Int,
        charEnd: Int,
        excerpt: String,
        note: String,
        colorArgb: Int,
    ) {
        viewModelScope.launch {
            if (annotationId == 0L) {
                annotationDao.insert(
                    NovelAnnotationEntity(
                        novelId = novelId,
                        charStart = charStart,
                        charEnd = charEnd,
                        excerpt = excerpt.take(500),
                        note = note,
                        color = colorArgb,
                        kind = NovelAnnotationEntity.KIND_NOTE,
                    ),
                )
            } else {
                val existing = annotationDao.getForNovel(novelId).firstOrNull { it.annotationId == annotationId }
                if (existing != null) {
                    annotationDao.update(
                        existing.copy(
                            note = note,
                            color = colorArgb,
                            kind = NovelAnnotationEntity.KIND_NOTE,
                            updatedTime = System.currentTimeMillis(),
                        ),
                    )
                }
            }
        }
    }

    fun deleteAnnotation(id: Long) {
        viewModelScope.launch { annotationDao.deleteById(id) }
    }

    // ---- Position bookmark CRUD ------------------------------------------

    fun addPositionBookmark(charIndex: Int, pageIndex: Int, preview: String) {
        viewModelScope.launch {
            bookmarkDao.insert(
                NovelBookmarkEntity(
                    novelId = novelId,
                    charIndex = charIndex,
                    pageIndex = pageIndex,
                    preview = preview.take(300),
                ),
            )
        }
    }

    fun deleteBookmark(id: Long) {
        viewModelScope.launch { bookmarkDao.deleteById(id) }
    }

    // ---- Search ---------------------------------------------------------------

    fun performSearch(query: String, regex: Boolean) {
        if (query.isEmpty()) {
            _searchResult.value = SearchResult.EMPTY
            return
        }
        val loaded = _loadState.value as? LoadState.Loaded ?: return
        val source = loaded.webNovel.text.orEmpty()
        val rawHits = SearchEngine.search(source, query, regex = regex, caseSensitive = false)
        val pages = _pagination.value?.pages.orEmpty()
        val annotated = SearchEngine.annotatePageIndices(rawHits, pages)
        val idx = if (annotated.isEmpty()) -1 else 0
        _searchResult.value = SearchResult(annotated, idx)
    }

    fun nextSearchHit(): SearchHit? {
        val current = _searchResult.value ?: return null
        if (current.hits.isEmpty()) return null
        val newIndex = (current.currentIndex + 1) % current.hits.size
        _searchResult.value = current.copy(currentIndex = newIndex)
        return current.hits[newIndex]
    }

    fun prevSearchHit(): SearchHit? {
        val current = _searchResult.value ?: return null
        if (current.hits.isEmpty()) return null
        val size = current.hits.size
        val newIndex = ((current.currentIndex - 1) % size + size) % size
        _searchResult.value = current.copy(currentIndex = newIndex)
        return current.hits[newIndex]
    }

    fun clearSearch() {
        _searchResult.value = SearchResult.EMPTY
    }

    private fun repaginateIfReady() {
        val style = pendingStyle ?: return
        val geom = pendingGeometry ?: return
        val toks = tokens
        if (toks.isEmpty()) return
        if (geom.contentWidth <= 0 || geom.contentHeight <= 0) return
        paginationJob?.cancel()
        val resolver = imageResolver
        val startChar = desiredCharIndex
        // Pagination runs on the main thread because the paginator drives
        // real AppCompatTextView instances (via [TextMeasurer]) to mirror
        // the reader's rendering layout pipeline exactly. TextView
        // construction / setText / measure all expect a Looper, and the
        // per-paragraph measurement is cheap enough (StaticLayout build,
        // which is what TextView does internally anyway) that a novel's
        // worth of it is a one-shot ms-level pause.
        paginationJob = viewModelScope.launch(Dispatchers.Main) {
            val measurer = TextMeasurer(Shaft.getContext())
            val paginator = Paginator(toks, geom, style, measurer, resolver)
            val pages = paginator.paginate()
            val start = if (pages.isEmpty()) 0 else pages.indexOfFirst { it.charEnd >= startChar }.coerceAtLeast(0)
            _pagination.value = PaginationState(pages, start, style, geom)
            _currentPageIndex.value = start
        }
    }

    override fun onCleared() {
        super.onCleared()
        paginationJob?.cancel()
    }

    companion object {
        fun factory(novelId: Long): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return NovelReaderV3ViewModel(novelId) as T
            }
        }
    }
}
