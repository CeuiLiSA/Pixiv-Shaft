package ceui.pixiv.ui.translate

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * 漫画翻译用的两套 ONNX 模型会话(CTD 文本检测 + manga-ocr 识别)的进程内持有者。
 *
 * 为什么是「一份、进程级」而不是各调用方自己 new:模型加载要秒级、常驻几百 MB,
 * 整部批量翻译([MangaBatchTranslateCenter])和看图页单页 / 圈选翻译
 * ([ceui.lisa.activities.ImageTranslationViewModel])前后脚会用到同一套,各开一份既慢又炸内存。
 * 它由 [ceui.lisa.activities.Shaft] 构造并通过 [ceui.loxia.ServicesProvider.mangaTranslateModels] 提供;
 * 单测可以自己 new 一份。
 *
 * 构造廉价:不做任何 IO,模型在第一次 [ensureLoaded] 时才读盘。
 */
class MangaTranslateModels(app: Context) {

    private val app = app.applicationContext

    val ocr = MangaOcrRecognizer()
    val detector = ComicTextDetector()

    /** 防止批量与单页同时触发加载时把同一模型读两遍。 */
    private val loadMutex = Mutex()

    /**
     * 按需把 OCR 模型加载起来(IO 线程);已加载则立即返回。
     * @return 是否成功;失败已打日志,调用方自己决定怎么提示
     */
    suspend fun ensureOcrLoaded(ocrModel: MangaOcrModel): Boolean = load { ocr.loadModel(app, ocrModel) }

    /** 同时确保 CTD + OCR 都就绪(整页自动翻译需要两者;圈选只要 OCR,用 [ensureOcrLoaded])。 */
    suspend fun ensureLoaded(ocrModel: MangaOcrModel, ctdModel: ComicTextDetectorModel): Boolean = load {
        ocr.loadModel(app, ocrModel)
        detector.loadModel(app, ctdModel)
    }

    val isOcrLoaded: Boolean get() = ocr.isLoaded
    val isLoaded: Boolean get() = ocr.isLoaded && detector.isLoaded

    private suspend fun load(block: () -> Unit): Boolean = loadMutex.withLock {
        withContext(Dispatchers.IO) {
            runCatching(block).onFailure { Timber.e(it, "MangaTranslateModels: loadModel failed") }.isSuccess
        }
    }

    /**
     * 释放两套 session。进程进后台被 onTrimMemory 在主线程敲时调;正有识别在跑的那套
     * 会跳过(tryLock),不阻塞主线程,下次用到再重新加载。
     */
    fun release() {
        ocr.release()
        detector.release()
    }
}
