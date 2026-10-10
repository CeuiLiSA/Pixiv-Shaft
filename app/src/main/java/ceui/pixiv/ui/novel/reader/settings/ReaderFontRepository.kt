package ceui.pixiv.ui.novel.reader.settings

import android.app.Application
import ceui.lisa.http.GithubProxy
import ceui.pixiv.ui.novel.reader.paginate.TypefaceProvider
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber

/**
 * 阅读器可下载字体（[ReaderWebFont]）的下载、删除与状态（#1060）。
 *
 * 进程级：关掉字体面板、甚至退出阅读器，下载照常跑完，回来时面板直接看到进度。
 * 构造廉价（见 [ceui.pixiv.services.ServicesProvider]）—— OkHttpClient 与首次的磁盘检查都延迟到真用时。
 */
class ReaderFontRepository(private val app: Application) {

    sealed interface State {
        object Absent : State
        /** [fraction] 按目录里写死的字节数算，加速站不回 Content-Length 也有进度。 */
        data class Downloading(val fraction: Float) : State
        object Installed : State
        object Failed : State
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<ReaderWebFont, Job>()

    private val client by lazy {
        OkHttpClient.Builder()
            .followRedirects(true)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    private val mutableStates by lazy {
        MutableStateFlow<Map<ReaderWebFont, State>>(
            ReaderWebFont.entries.associateWith { font ->
                if (font.file(app).exists()) State.Installed else State.Absent
            },
        )
    }

    val states: StateFlow<Map<ReaderWebFont, State>> get() = mutableStates

    fun isInstalled(font: ReaderWebFont): Boolean = states.value[font] == State.Installed

    fun download(font: ReaderWebFont) {
        if (jobs[font]?.isActive == true || isInstalled(font)) return
        set(font, State.Downloading(0f))
        // LAZY：先登记进 jobs 再启动，否则协程可能抢在登记前跑完，自己的状态回写全被当成「已被抢先」丢掉
        val job = scope.launch(start = CoroutineStart.LAZY) {
            val self = currentCoroutineContext().job
            // 被 cancel / delete 抢先改过状态的，不再回写
            fun finish(state: State) {
                if (jobs.remove(font, self)) set(font, state)
            }
            try {
                fetch(font) { fraction -> if (jobs[font] === self) set(font, State.Downloading(fraction)) }
                TypefaceProvider.evict(font.id)
                ReaderSettings.onFontInstalled(font.id)
                finish(State.Installed)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "reader font download failed: ${font.id}")
                finish(State.Failed)
            }
        }
        jobs[font] = job
        job.start()
    }

    fun cancel(font: ReaderWebFont) {
        jobs.remove(font)?.cancel() ?: return
        set(font, State.Absent)
    }

    fun delete(font: ReaderWebFont) {
        jobs.remove(font)?.cancel()
        font.file(app).delete()
        TypefaceProvider.evict(font.id)
        set(font, State.Absent)
    }

    private fun set(font: ReaderWebFont, state: State) {
        mutableStates.update { it + (font to state) }
    }

    /** 下到临时 `.part`，字节数与 SHA-256 都对上才换成正式文件，半截文件永远不会被当成字体加载。 */
    private suspend fun fetch(font: ReaderWebFont, onProgress: (Float) -> Unit) {
        val target = font.file(app)
        val dir = target.parentFile ?: throw IOException("no font dir")
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("cannot create ${dir.path}")
        // 每次下载各用一个临时文件：取消后紧接着重下时，旧协程可能还卡在一次阻塞读里（最长到读超时），
        // 共用同一路径的话它醒来后的 finally 会删掉新下载的文件，新下载在最后一步 rename 失败。
        // 同一款同时只有一个活着的下载，所以这里先清掉这款字体残留的临时文件（含进程被杀留下的）。
        dir.listFiles { f -> f.name.startsWith("${font.id}.") && f.name.endsWith(".part") }?.forEach { it.delete() }
        val part = File.createTempFile("${font.id}.", ".part", dir)
        try {
            val request = Request.Builder().url(GithubProxy.wrap(font.downloadUrl)).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                val body = response.body ?: throw IOException("empty body")
                val digest = MessageDigest.getInstance("SHA-256")
                var read = 0L
                var lastPercent = -1
                body.byteStream().use { input ->
                    part.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val n = input.read(buffer)
                            if (n < 0) break
                            read += n
                            if (read > font.byteSize) throw IOException("larger than ${font.byteSize}")
                            digest.update(buffer, 0, n)
                            output.write(buffer, 0, n)
                            val percent = (read * 100 / font.byteSize).toInt()
                            if (percent != lastPercent) {
                                lastPercent = percent
                                onProgress(read.toFloat() / font.byteSize)
                            }
                        }
                    }
                }
                val hex = digest.digest().joinToString("") { "%02x".format(it) }
                if (!font.matches(read, hex)) throw IOException("checksum mismatch: $read bytes, $hex")
            }
            currentCoroutineContext().ensureActive()
            if (!part.renameTo(target)) throw IOException("rename failed")
        } finally {
            part.delete()
        }
    }
}
