package ceui.pixiv.sticker

import ceui.lisa.activities.Shaft
import ceui.pixiv.shaftapi.MediaHttpTransport
import com.google.gson.Gson
import java.io.File
import java.io.FileOutputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.Request

internal sealed interface StickerState {
    data object Idle : StickerState
    data class Loading(val phase: String = "catalog", val bytes: Long = 0, val total: Long = 0) : StickerState
    data class Ready(val data: StickerStore.Ready) : StickerState
    data class Failed(val error: Exception) : StickerState
}

/** One installation per process, shared by chat and plaza; independent of login. */
internal object StickerRepository {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableState = MutableStateFlow<StickerState>(StickerState.Idle)
    val state = mutableState.asStateFlow()
    private val store by lazy { StickerStore(File(Shaft.getContext().noBackupFilesDir, "stickers")) { event -> StickerLog.i(event) } }
    const val TAG = "Sticker-System"
    private val gson = Gson()
    private val loggedLocalGenerations = java.util.concurrent.atomic.AtomicReferenceArray<String>(2)
    private var job: Job? = null
    private val api = MediaHttpTransport.apiClient.newBuilder().callTimeout(30, TimeUnit.SECONDS).build()
    private val storage = MediaHttpTransport.storageClient.newBuilder()
        .followRedirects(false).followSslRedirects(false).callTimeout(10, TimeUnit.MINUTES).build()

    @Synchronized
    fun prepare(recheck: Boolean = false) {
        if (job?.isActive == true || (!recheck && state.value is StickerState.Ready)) {
            StickerLog.d("prepare_join recheck=%s state=%s", recheck, state.value.javaClass.simpleName)
            return
        }
        StickerLog.i("prepare_start recheck=%s previous=%s", recheck, state.value.javaClass.simpleName)
        val previous = (state.value as? StickerState.Ready)?.data
        mutableState.value = StickerState.Loading()
        job = scope.launch {
            try {
                if (previous != null && store.isUnchanged(previous)) {
                    StickerLog.i("gate_reused generation=%s verified_files=%d", previous.generation, previous.inventory.size)
                    mutableState.value = StickerState.Ready(previous)
                    return@launch
                }
                val saved = try { store.savedCatalog() } catch (error: Exception) {
                    StickerLog.w(error, "Discarding invalid sticker catalog")
                    null
                }
                val catalog = try {
                    val versions = gson.fromJson(metadata("stickers-version"), StickerVersions::class.java)
                    StickerLog.i("versions latest=%s reuse_catalog=%s", versions.list, saved?.versions == versions)
                    if (saved?.versions == versions) saved else StickerCatalog(versions,
                        StickerCatalog.TYPES.associateWith { type ->
                            gson.fromJson(metadata("stickers?type=$type"), StickerPack::class.java)
                        })
                } catch (error: IOException) {
                    // Offline reuse still requires the full disk verification below.
                    StickerLog.w(error, "offline_verify saved_catalog=%s", saved != null)
                    saved ?: throw error
                }
                val coroutine = currentCoroutineContext()
                var lastProgressAt = 0L
                var lastPhase = ""
                val ready = store.prepare(catalog, ::download,
                    { phase, bytes, total ->
                        mutableState.value = StickerState.Loading(phase, bytes, total)
                        if (phase != lastPhase || System.currentTimeMillis() - lastProgressAt >= 2000) {
                            StickerLog.i("progress phase=%s bytes=%d total=%d percent=%d", phase, bytes, total, if (total == 0L) 0 else bytes * 100 / total)
                            lastPhase = phase
                            lastProgressAt = System.currentTimeMillis()
                        }
                    },
                    { coroutine.ensureActive() })
                mutableState.value = StickerState.Ready(ready)
            } catch (error: CancellationException) {
                mutableState.value = StickerState.Idle
                throw error
            } catch (error: Exception) {
                StickerLog.e(error, "installation_failed state=%s", mutableState.value)
                mutableState.value = StickerState.Failed(error)
            }
        }
    }

    private fun metadata(path: String): String {
        StickerLog.i("metadata_start path=%s", path)
        api.newCall(Request.Builder().url("https://api.pixshaft.com/f/v1/$path").build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Sticker API HTTP ${response.code}")
            val body = response.body ?: throw IOException("Empty sticker metadata")
            // Catalogs are small JSON; keep a hard cap on the accumulated response.
            body.byteStream().use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    if (output.size() + n > 8 * 1024 * 1024) throw IOException("Sticker metadata too large")
                    output.write(buffer, 0, n)
                }
                StickerLog.i("metadata_complete path=%s bytes=%d", path, output.size())
                return output.toString("UTF-8")
            }
        }
    }

    private fun download(pkg: StickerPackage, destination: File, progress: (Long) -> Unit) {
        // Catalog.validate() has already restricted this URL to the public COS prefix.
        storage.newCall(Request.Builder().url(pkg.url).build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Sticker ZIP HTTP ${response.code}")
            val body = response.body ?: throw IOException("Empty sticker ZIP")
            if (body.contentLength() >= 0 && body.contentLength() != pkg.size) throw IOException("Sticker ZIP length mismatch")
            body.byteStream().use { input ->
                FileOutputStream(destination).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var bytes = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        bytes += n
                        if (bytes > pkg.size) throw IOException("Sticker ZIP exceeds declared size")
                        output.write(buffer, 0, n)
                        progress(bytes)
                    }
                    output.fd.sync()
                    if (bytes != pkg.size) throw IOException("Incomplete sticker ZIP")
                }
            }
        }
    }

    /** Called on Glide's IO thread. Failure changes the gate; never starts a URL request. */
    fun localFile(model: LocalSticker): File {
        val readyState = state.value as? StickerState.Ready ?: throw IOException("Stickers are not ready")
        val ready = readyState.data
        if (ready.generation != model.generation) throw IOException("Stale sticker image request")
        try {
            check(store.hasReadyMarker(model.generation)) { "Sticker generation is not ready" }
            val file = ready.file(model.stickerId, model.resourceSize)?.takeIf { it.isFile && it.length() > 0 }
                ?: throw IOException("Local sticker is missing")
            val slot = if (model.resourceSize == 64) 0 else 1
            if (loggedLocalGenerations.getAndSet(slot, model.generation) != model.generation) {
                StickerLog.i("local_resource_resolved source=LOCAL resource_size=%d file=%s", model.resourceSize, file.path)
            }
            return file
        } catch (error: Exception) {
            StickerLog.e(error, "local_file_failed sticker_id=%d generation=%s", model.stickerId, model.generation)
            mutableState.compareAndSet(readyState, StickerState.Failed(error))
            throw error
        }
    }
}
