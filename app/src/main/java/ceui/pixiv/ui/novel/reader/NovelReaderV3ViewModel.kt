package ceui.pixiv.ui.novel.reader

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import ceui.lisa.R
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
import ceui.pixiv.api.Client
import ceui.pixiv.api.model.Illust
import ceui.loxia.Novel
import ceui.pixiv.cache.ObjectPool
import ceui.pixiv.actions.PixivActions
import ceui.pixiv.api.model.WebNovel
import ceui.pixiv.ui.novel.reader.model.ContentToken
import ceui.pixiv.ui.novel.reader.model.NovelIllustSource
import ceui.pixiv.ui.novel.reader.model.Page
import ceui.pixiv.ui.novel.reader.model.PageGeometry
import ceui.pixiv.ui.novel.reader.paginate.ContentParser
import ceui.pixiv.ui.novel.reader.paginate.IllustMixInserter
import ceui.pixiv.ui.novel.reader.paginate.ImageResolver
import ceui.pixiv.ui.novel.reader.paginate.Paginator
import ceui.pixiv.ui.novel.reader.settings.ReaderSettings
import ceui.pixiv.ui.novel.reader.paginate.TextMeasurer
import ceui.pixiv.ui.novel.reader.paginate.TypeStyle
import ceui.pixiv.ui.novel.reader.export.ExportFormat
import ceui.pixiv.ui.novel.reader.export.ExportResult
import ceui.pixiv.ui.novel.reader.export.NovelExportManager
import ceui.pixiv.ui.novel.reader.feature.SearchEngine
import ceui.pixiv.ui.novel.reader.model.SearchHit
import ceui.pixiv.ui.novel.reader.paginate.ChapterOutlineEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import ceui.pixiv.db.discovery.DiscoveryPool

class NovelReaderV3ViewModel(
    val novelId: Long,
    private val discoveryPool: DiscoveryPool,
    private val localUri: String? = null,
    private val localTitle: String? = null,
) : ViewModel() {

    /** 本地 txt 源：非空时 [load] 走离线分支，不碰网络 / ObjectPool。 */
    val isLocal: Boolean get() = !localUri.isNullOrEmpty()

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

    /** pixiv 原版书签（しおり/marker）所在页，0 = 未插书签。issue #935。 */
    private val _markerPage = MutableLiveData(0)
    val markerPage: LiveData<Int> = _markerPage

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

    private fun ctx(): Context = Shaft.getContext()

    private var webNovel: WebNovel? = null
    // 混排取材（Related 搜 tag）与相关性排序都要小说元数据；LoadState 走 postValue，
    // load() 里紧接着的 maybeFetchMixIllusts 读不到 value，所以单独存一份。
    private var novel: Novel? = null
    private var tokens: List<ContentToken> = emptyList()
    private var imageResolver: (ContentToken) -> String? = { null }

    // ---- 自动混排插画（issue #999）----
    // mixIllusts 只作用于展示链路（displayTokens / displayImageResolver）；
    // tokens 本体保持纯净，导出 / 复制 / 章节大纲 / NovelTextCache 都不受影响。
    private var mixIllusts: List<Illust> = emptyList()
    private var mixIllustsSource: NovelIllustSource? = null

    /** 取材拉到手后 +1，阅读页 observe 它来重绑纵向滚动视图（横向由内部 repaginate 覆盖）。 */
    private val _illustMixVersion = MutableLiveData(0)
    val illustMixVersion: LiveData<Int> = _illustMixVersion

    private var pendingStyle: TypeStyle? = null
    private var pendingGeometry: PageGeometry? = null
    private var desiredCharIndex: Int = 0
    private var paginationJob: Job? = null

    // Dedicated Looper thread for pagination — TextMeasurer drives an
    // AppCompatTextView which requires a Looper, but running it on Main
    // blocks the UI for long novels. HandlerThread provides the Looper;
    // its dispatcher bridges into coroutines so results post back to Main.
    private val paginationThread = HandlerThread("novel-paginate").apply { start() }
    private val paginationDispatcher = Handler(paginationThread.looper).asCoroutineDispatcher()

    fun load() {
        if (_loadState.value is LoadState.Loading) return
        _loadState.value = LoadState.Loading
        viewModelScope.launch {
            runCatching {
                if (isLocal) {
                    loadLocal()
                    return@runCatching
                }
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
                        val web = WebNovelParser.parsePixivObject(html)?.novel ?: error(ctx().getString(R.string.msg_parse_fail))
                        val toks = ContentParser.tokenize(web)
                        NovelTextCache.put(novelId, NovelTextCache.Entry(web, toks))
                        web to toks
                    }
                }
                webNovel = parsed.first
                this@NovelReaderV3ViewModel.novel = novel
                tokens = parsed.second
                imageResolver = ImageResolver.of(parsed.first)
                desiredCharIndex = ReaderProgressStore.loadCharIndex(novelId)
                _markerPage.postValue(parsed.first.marker?.page ?: 0)
                _loadState.postValue(LoadState.Loaded(novel, parsed.first, parsed.second))
                maybeFetchMixIllusts()
                repaginateIfReady()
            }.onFailure { throwable ->
                Timber.tag("NovelReaderV3").e(throwable)
                _loadState.postValue(LoadState.Error(throwable.message ?: ctx().getString(R.string.msg_load_fail)))
            }
        }
    }

    /**
     * 本地 txt 离线分支：读字节 → [ceui.pixiv.ui.novel.local.TextDecoder] 探测编码
     * （UTF-8 / GB18030）→ 直接喂 [ContentParser.tokenize]。novel 元数据为 null，
     * 标题取文件名（[localTitle]）。进度/标注/书签照常按 [novelId]（合成负数 id）走。
     */
    private suspend fun loadLocal() {
        val uri = android.net.Uri.parse(localUri)
        val parsed = withContext(Dispatchers.IO) {
            val bytes = ctx().contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: error(ctx().getString(R.string.msg_load_fail))
            val text = ceui.pixiv.ui.novel.local.TextDecoder.decode(bytes)
            val web = WebNovel(title = localTitle.orEmpty(), text = text)
            web to ContentParser.tokenize(text)
        }
        webNovel = parsed.first
        tokens = parsed.second
        imageResolver = ImageResolver.of(parsed.first)
        desiredCharIndex = ReaderProgressStore.loadCharIndex(novelId)
        _loadState.postValue(LoadState.Loaded(null, parsed.first, parsed.second))
        maybeFetchMixIllusts()
        repaginateIfReady()
    }

    // ---- 自动混排插画（issue #999）--------------------------------------------

    /**
     * 展示用 token 流：混排开着且取材已就绪时，在原始 [tokens] 上插入零宽度
     * PixivImage；否则原样返回（关着 / 拉取失败 / 来源切换后旧数据不复用），
     * 阅读页自然回退纯文字。横向 [repaginateIfReady] 与纵向 rebind 都从这取。
     */
    fun displayTokens(): List<ContentToken> {
        val source = ReaderSettings.illustMixSource
        if (source == NovelIllustSource.None) return tokens
        if (mixIllustsSource != source || mixIllusts.isEmpty()) return tokens
        return IllustMixInserter.insert(tokens, mixIllusts.map { it.id })
    }

    /** 内嵌插图照旧走 webNovel 的对象表，查不到再落到混排取材的 URL 表。 */
    fun displayImageResolver(): (ContentToken) -> String? {
        val base = imageResolver
        if (mixIllusts.isEmpty()) return base
        val urls = mixIllusts.associateBy({ it.id }, { NovelIllustMixStore.pickUrl(it) })
        return { token ->
            base(token) ?: (token as? ContentToken.PixivImage)?.let { urls[it.illustId] }
        }
    }

    /** 设置面板切换来源后由阅读页调用：需要就补拉取材，并立即按新口径重排版。 */
    fun onIllustMixSettingChanged() {
        maybeFetchMixIllusts()
        repaginateIfReady()
    }

    private fun maybeFetchMixIllusts() {
        val source = ReaderSettings.illustMixSource
        if (source == NovelIllustSource.None) return
        if (mixIllustsSource == source && mixIllusts.isNotEmpty()) return
        val novel = this.novel
        viewModelScope.launch {
            runCatching { NovelIllustMixStore.get(discoveryPool, source, novel) }
                .onSuccess { list ->
                    // 来源在拉取途中又被切走就丢弃结果，别用旧口径的图污染新设置。
                    if (list.isEmpty() || ReaderSettings.illustMixSource != source) return@onSuccess
                    // 消费顺序在这里定型：标签重叠/关注画师优先，同分按 novelId 稳定洗牌。
                    mixIllusts = IllustMixRanker.rank(list, novel, seed = novelId)
                    mixIllustsSource = source
                    repaginateIfReady()
                    _illustMixVersion.value = (_illustMixVersion.value ?: 0) + 1
                }
                .onFailure { Timber.tag("NovelReaderV3").w(it, "illust mix fetch failed, fall back to plain text") }
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

    fun onScrollPositionChanged(charIndex: Int) {
        desiredCharIndex = charIndex
        ReaderProgressStore.saveProgress(novelId, charIndex, 0, 0)
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
            // Upsert by exact range: re-picking a color on the same selection
            // updates the existing row instead of stacking a new one. Scoped
            // to KIND_HIGHLIGHT so a Note that happens to share the range is
            // left untouched.
            val existing = annotationDao.findExactRange(
                novelId = novelId,
                charStart = charStart,
                charEnd = charEnd,
                kind = NovelAnnotationEntity.KIND_HIGHLIGHT,
            )
            if (existing != null) {
                annotationDao.update(
                    existing.copy(
                        color = colorArgb,
                        updatedTime = System.currentTimeMillis(),
                    ),
                )
            } else {
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

    fun setSearchIndex(index: Int) {
        val current = _searchResult.value ?: return
        if (index in current.hits.indices) {
            _searchResult.value = current.copy(currentIndex = index)
        }
    }

    fun clearSearch() {
        _searchResult.value = SearchResult.EMPTY
    }

    // ---- Bookmark toggle ----------------------------------------------------

    /**
     * 收藏切换。写操作走 [PixivActions]，与 NovelTextFragment / 小说卡片同一条队列。
     *
     * 不能自己直接打接口：那条路是同步的，而 [PixivActions] 是乐观 + 排队的，两者都拿
     * ObjectPool 的 `is_bookmarked` 当真值。队列还在冷却里时，池子里已经是「已收藏」而
     * 服务端什么都没收到，这里读到 true 就会立刻发一个删除请求去删一个根本不存在的收藏，
     * 等冷却结束队列再把添加发出去，最终收藏态与用户最后一次操作相反。
     *
     * @return 要提示给用户的话；**成功路径返回空串**，调用方不弹。这一刻请求还没发出去，
     *         报「已收藏」是骗用户，而失败时队列几分钟后还会补一个「收藏失败」自相矛盾
     *         （插画与关注在迁进队列时已经这么改了，这里是漏掉的最后一处）。反馈由顶栏那颗
     *         爱心承担：它 observe 的是 ObjectPool 里的 Novel，乐观写当帧变红，队列回滚时
     *         自动拨回去。
     */
    suspend fun toggleBookmark(): String {
        if (isLocal) return ctx().getString(R.string.local_novel_bookmark_unsupported)
        return runCatching {
            val novel = ObjectPool.get<Novel>(novelId).value
                ?: Client.appApi.getNovel(novelId).novel?.also { ObjectPool.update(it) }
            novel ?: return ctx().getString(R.string.msg_novel_loading)
            PixivActions.setNovelBookmark(novel, novel.is_bookmarked != true)
            ""
        }.getOrElse { ctx().getString(R.string.msg_operation_fail, it.message.orEmpty()) }
    }

    // ---- pixiv marker (原版书签) toggle — issue #935 --------------------------

    /**
     * 在当前阅读位置插入 / 移除 pixiv 原版书签（しおり）。marker 的 page 是
     * 1-based 的 `[newpage]` 分页序号，与旧版阅读器（FragmentNovelHolder）及
     * 「我的-小说书签」列表页共用同一个服务端概念。
     */
    suspend fun toggleMarker(): String {
        if (isLocal) return ctx().getString(R.string.local_novel_bookmark_unsupported)
        return runCatching {
            if ((_markerPage.value ?: 0) > 0) {
                Client.appApi.removeNovelMarker(novelId)
                updateMarkerState(0)
                ctx().getString(R.string.msg_marker_removed)
            } else {
                val page = pixivPageForCharIndex(desiredCharIndex)
                Client.appApi.addNovelMarker(novelId, page)
                updateMarkerState(page)
                ctx().getString(R.string.msg_marker_added)
            }
        }.getOrElse { ctx().getString(R.string.msg_operation_fail, it.message.orEmpty()) }
    }

    /** charIndex 落在第几个 `[newpage]` 分段里（1-based），无分页符即第 1 页。 */
    private fun pixivPageForCharIndex(charIndex: Int): Int =
        tokens.count { it is ContentToken.PageBreak && it.sourceStart <= charIndex } + 1

    /**
     * 同步内存态：LiveData 驱动顶栏图标，webNovel + NovelTextCache 一起换新，
     * 否则退出后再进（缓存命中不走网络）会显示 toggle 前的旧书签状态。
     */
    private fun updateMarkerState(page: Int) {
        _markerPage.value = page
        val web = webNovel ?: return
        val marker = if (page > 0) {
            ceui.lisa.models.NovelDetail.NovelMarkerBean().apply { setPage(page) }
        } else null
        val updated = web.copy(marker = marker)
        webNovel = updated
        NovelTextCache.get(novelId)?.let { NovelTextCache.put(novelId, it.copy(webNovel = updated)) }
    }

    // ---- Export --------------------------------------------------------------

    suspend fun exportNovel(format: ExportFormat, allowAutoEpub: Boolean = false): ExportResult {
        val loaded = _loadState.value as? LoadState.Loaded
            ?: return ExportResult.Failure(ctx().getString(R.string.msg_novel_not_ready))
        // 仅默认 TXT 快路径允许静默切 EPUB；手动在“每次询问”里选 TXT 不切。
        val actualFormat = if (allowAutoEpub && NovelExportManager.shouldAutoEpubForDefaultTxt(format, loaded.tokens)) {
            ExportFormat.Epub
        } else {
            format
        }
        return NovelExportManager.export(
            context = Shaft.getContext(),
            format = actualFormat,
            novel = loaded.novel,
            webNovel = loaded.webNovel,
            tokens = loaded.tokens,
        )
    }

    // ---- Plain-text body (for clipboard copy) -------------------------------

    /**
     * 渲染正文为可粘贴的纯文本：章节作 `【标题】` 段落，图片/跳转折叠为占位符。
     * 加载未完成返回 null；上层弹 toast 让用户稍后再试。结构与 [TxtExporter] 保持一致，
     * 但不带元数据头——剪贴板场景要的就是干净正文。
     */
    fun buildBodyPlainText(): String? {
        val loaded = _loadState.value as? LoadState.Loaded ?: return null
        val title = loaded.webNovel.title.orEmpty()
        return buildString {
            if (title.isNotEmpty()) {
                appendLine(title)
                appendLine()
            }
            for (token in loaded.tokens) {
                when (token) {
                    is ContentToken.Paragraph -> appendLine(token.text)
                    is ContentToken.BlankLine -> appendLine()
                    is ContentToken.PageBreak -> {
                        appendLine()
                        appendLine("- - - - - - - - - -")
                        appendLine()
                    }
                    is ContentToken.Chapter -> {
                        appendLine()
                        appendLine("【${token.title}】")
                        appendLine()
                    }
                    is ContentToken.PixivImage -> appendLine("[图片: pixiv ${token.illustId}]")
                    is ContentToken.UploadedImage -> appendLine("[图片: uploaded ${token.imageId}]")
                    is ContentToken.Jump -> appendLine("[跳转→第 ${token.target} 段]")
                }
            }
        }
    }

    // ---- Chapter outline ----------------------------------------------------

    fun getChapterOutline(): List<ChapterOutlineEntry> {
        val toks = (_loadState.value as? LoadState.Loaded)?.tokens ?: return emptyList()
        return ContentParser.buildChapterOutline(toks)
    }

    // ---- Position bookmark (with preview extraction) ------------------------

    fun addBookmarkAtCurrentPage(pageIndex: Int) {
        val pages = _pagination.value?.pages ?: return
        val page = pages.getOrNull(pageIndex) ?: return
        val source = webNovel?.text.orEmpty()
        val preview = source.substring(
            page.charStart.coerceIn(0, source.length),
            minOf(source.length, page.charStart + 80),
        ).replace('\n', ' ').trim()
        addPositionBookmark(page.charStart, pageIndex, preview)
    }

    /**
     * 纵向滚动模式的位置书签（#1038）：纵向下 rv 从没排过版、[_pagination] 恒 null，
     * [addBookmarkAtCurrentPage] 会静默 return（而调用方 toast 已经报了「已保存」）。
     * 纵向的位置真源是滚动位置的 charIndex，直接按它存；pageIndex 存 0 兜底——
     * 书签列表优先显示 preview 文本（charIndex 起 80 字，此处必非空），跳转恢复
     * 两种模式都走 charIndex，页码只是横向语义的展示补充。
     */
    fun addBookmarkAtCharIndex(charIndex: Int) {
        val source = webNovel?.text.orEmpty()
        if (source.isEmpty()) return
        val start = charIndex.coerceIn(0, source.length)
        val preview = source.substring(start, minOf(source.length, start + 80))
            .replace('\n', ' ').trim()
        val pageIndex = _pagination.value?.pages
            ?.indexOfLast { it.charStart <= start }
            ?.takeIf { it >= 0 } ?: 0
        addPositionBookmark(start, pageIndex, preview)
    }

    private fun repaginateIfReady() {
        val style = pendingStyle ?: return
        val geom = pendingGeometry ?: return
        val toks = displayTokens()
        if (toks.isEmpty()) return
        if (geom.contentWidth <= 0 || geom.contentHeight <= 0) return
        paginationJob?.cancel()
        val resolver = displayImageResolver()
        val startChar = desiredCharIndex
        // Pagination runs on [paginationThread] — a dedicated HandlerThread
        // whose Looper satisfies the AppCompatTextView that [TextMeasurer]
        // drives internally. Running off the main thread keeps the UI
        // responsive for long novels; results are posted back to Main via
        // the surrounding viewModelScope launch (Dispatchers.Main.immediate).
        paginationJob = viewModelScope.launch {
            val result = withContext(paginationDispatcher) {
                val measurer = TextMeasurer(Shaft.getContext())
                val paginator = Paginator(toks, geom, style, measurer, resolver)
                val pages = paginator.paginate()
                val start = if (pages.isEmpty()) 0 else {
                    pages.indexOfFirst { it.charEnd >= startChar }.coerceAtLeast(0)
                }
                PaginationState(pages, start, style, geom)
            }
            // Back on Main — safe to touch LiveData.
            _pagination.value = result
            _currentPageIndex.value = result.startPageIndex
        }
    }

    override fun onCleared() {
        super.onCleared()
        paginationJob?.cancel()
        paginationThread.quitSafely()
    }

    companion object {
        fun factory(
            novelId: Long,
            discoveryPool: DiscoveryPool,
            localUri: String? = null,
            localTitle: String? = null,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return NovelReaderV3ViewModel(novelId, discoveryPool, localUri, localTitle) as T
            }
        }
    }
}
