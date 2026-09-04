package ceui.lisa.cache

import android.util.AtomicFile
import androidx.annotation.WorkerThread
import ceui.lisa.models.FramesBean
import ceui.lisa.models.GifResponse
import ceui.lisa.models.ImageUrlsBean
import ceui.lisa.models.UgoiraMetadataBean
import com.blankj.utilcode.util.PathUtils
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import timber.log.Timber
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.InvalidClassException
import java.io.ObjectInputStream
import java.io.ObjectStreamClass
import java.util.ArrayList

/**
 * ugoira 元数据的磁盘缓存。
 *
 * 新数据使用有版本号的 JSON，并通过 [AtomicFile] 提交，进程在写入途中退出也不会留下
 * 半个文件。旧版本写在 cache 根目录的 Java 序列化文件会在首次读取时惰性迁移；
 * 迁移成功后才删除旧文件。
 *
 * cacheDir 随时可能被系统清理，因此所有失败都等价于缓存未命中。
 */
object UgoiraMetadataCache {

    private val store: UgoiraMetadataDiskCache by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        UgoiraMetadataDiskCache(File(PathUtils.getInternalAppCachePath()))
    }

    @JvmStatic
    @WorkerThread
    fun get(illustId: Long): GifResponse? = store.get(illustId)

    @JvmStatic
    @WorkerThread
    fun put(illustId: Long, value: GifResponse) = store.put(illustId, value)
}

/** 具体文件协议独立出来，既不依赖全局 Context，也能直接做迁移测试。 */
internal class UgoiraMetadataDiskCache(
    private val cacheRoot: File,
    private val gson: Gson = Gson(),
) {

    private val locks = Array(LOCK_STRIPES) { Any() }

    fun get(illustId: Long): GifResponse? = synchronized(lockFor(illustId)) {
        when (val current = readCurrent(illustId)) {
            is CurrentRead.Hit -> current.value
            CurrentRead.Unsupported -> null
            CurrentRead.Missing,
            CurrentRead.Corrupt,
            -> migrateLegacy(illustId)
        }
    }

    fun put(illustId: Long, value: GifResponse) {
        synchronized(lockFor(illustId)) {
            // 降级运行时只能把新格式视为 miss，不能让随后的网络回填覆盖它。
            if (readCurrent(illustId) == CurrentRead.Unsupported) return
            if (writeCurrent(illustId, value)) {
                legacyFile(illustId).delete()
            }
        }
    }

    private fun readCurrent(illustId: Long): CurrentRead {
        val file = currentFile(illustId)
        val atomicFile = AtomicFile(file)
        return try {
            val root = atomicFile.openRead().use { input ->
                val size = input.channel.size()
                require(size in 1..MAX_ENTRY_BYTES) { "invalid cache size: $size" }
                input.bufferedReader(Charsets.UTF_8).use(JsonParser::parseReader).asJsonObject
            }

            val version = root.get(FIELD_VERSION)?.asInt
                ?: throw IllegalStateException("missing cache version")
            if (version != FORMAT_VERSION) {
                Timber.i("Ignore newer ugoira cache format=%d illust=%d", version, illustId)
                CurrentRead.Unsupported
            } else {
                val value = gson.fromJson(root.get(FIELD_VALUE), GifResponse::class.java)
                    ?: throw IllegalStateException("missing cache value")
                CurrentRead.Hit(value)
            }
        } catch (_: FileNotFoundException) {
            CurrentRead.Missing
        } catch (error: Exception) {
            atomicFile.delete()
            Timber.w(error, "Discard corrupt ugoira cache illust=%d", illustId)
            CurrentRead.Corrupt
        }
    }

    private fun writeCurrent(illustId: Long, value: GifResponse): Boolean {
        val root = JsonObject().apply {
            addProperty(FIELD_VERSION, FORMAT_VERSION)
            add(FIELD_VALUE, gson.toJsonTree(value, GifResponse::class.java))
        }
        val bytes = gson.toJson(root).toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_ENTRY_BYTES) {
            Timber.w("Skip oversized ugoira cache illust=%d bytes=%d", illustId, bytes.size)
            return false
        }

        val file = currentFile(illustId)
        val parent = file.parentFile ?: return false
        if (!parent.isDirectory && !parent.mkdirs() && !parent.isDirectory) {
            Timber.w("Cannot create ugoira cache directory: %s", parent)
            return false
        }

        val atomicFile = AtomicFile(file)
        var output: FileOutputStream? = null
        return try {
            output = atomicFile.startWrite()
            output.write(bytes)
            atomicFile.finishWrite(output)
            output = null
            true
        } catch (error: Exception) {
            atomicFile.failWrite(output)
            Timber.w(error, "Write ugoira cache failed illust=%d", illustId)
            false
        }
    }

    private fun migrateLegacy(illustId: Long): GifResponse? {
        val file = legacyFile(illustId)
        if (!file.isFile) return null
        if (file.length() !in 1..MAX_ENTRY_BYTES) {
            file.delete()
            return null
        }

        val value = try {
            LegacyObjectInputStream(FileInputStream(file)).use { input ->
                GifResponse::class.java.cast(input.readObject())
            }
        } catch (error: Exception) {
            file.delete()
            Timber.w(error, "Discard unreadable legacy ugoira cache illust=%d", illustId)
            return null
        }

        if (writeCurrent(illustId, value)) {
            file.delete()
        }
        return value
    }

    private fun currentFile(illustId: Long) = File(File(cacheRoot, DIRECTORY), "$illustId.json")

    // 必须保持原始字面值：旧 Cache 的 key 是 Params.ILLUST_ID + "_" + illustId。
    private fun legacyFile(illustId: Long) = File(cacheRoot, "${LEGACY_KEY_PREFIX}_$illustId")

    private fun lockFor(illustId: Long): Any {
        val hash = (illustId xor (illustId ushr 32)).toInt()
        return locks[(hash and Int.MAX_VALUE) % locks.size]
    }

    private sealed interface CurrentRead {
        data class Hit(val value: GifResponse) : CurrentRead
        data object Missing : CurrentRead
        data object Corrupt : CurrentRead
        data object Unsupported : CurrentRead
    }

    companion object {
        private const val DIRECTORY = "ugoira_metadata"
        private const val LEGACY_KEY_PREFIX = "illust id"
        private const val FORMAT_VERSION = 1
        private const val MAX_ENTRY_BYTES = 8L * 1024L * 1024L
        private const val LOCK_STRIPES = 16
        private const val FIELD_VERSION = "version"
        private const val FIELD_VALUE = "value"
    }
}

/**
 * Java 原生反序列化只存在于旧数据迁移路径，并把可实例化类型限制在历史对象图内。
 * 生产版启用 R8 后类名会被缩短且可能随版本变化，所以按字段名与字段类型识别这四个
 * DTO 的旧描述符；无法精确识别的类型仍会在 [resolveClass] 被拒绝。
 */
private class LegacyObjectInputStream(input: FileInputStream) : ObjectInputStream(input) {

    private val allowedClassNames = setOf(
        GifResponse::class.java.name,
        UgoiraMetadataBean::class.java.name,
        FramesBean::class.java.name,
        ImageUrlsBean::class.java.name,
        ArrayList::class.java.name,
    )

    override fun readClassDescriptor(): ObjectStreamClass {
        val descriptor = super.readClassDescriptor()
        if (descriptor.name == ArrayList::class.java.name) return descriptor

        // 无论旧描述符是源码类名还是某一版 R8 名称都替换为当前类描述符，同时绕开
        // 默认 serialVersionUID 因方法变化而不一致的问题。类型码也参与匹配，避免仅凭
        // 字段名把不相干的序列化类型映射进来。
        val fieldShape = descriptor.fields.associate { it.name to it.typeCode }
        val modelClass = when (fieldShape) {
            mapOf("ugoira_metadata" to 'L') -> GifResponse::class.java
            mapOf("frames" to 'L', "zip_urls" to 'L') -> UgoiraMetadataBean::class.java
            mapOf("delay" to 'I', "file" to 'L') -> FramesBean::class.java
            mapOf(
                "large" to 'L',
                "medium" to 'L',
                "original" to 'L',
                "square_medium" to 'L',
            ) -> ImageUrlsBean::class.java
            else -> null
        }
        return modelClass?.let(ObjectStreamClass::lookup) ?: descriptor
    }

    override fun resolveClass(descriptor: ObjectStreamClass): Class<*> {
        if (descriptor.name !in allowedClassNames) {
            throw InvalidClassException(descriptor.name, "Unexpected legacy cache type")
        }
        return super.resolveClass(descriptor)
    }

    override fun resolveProxyClass(interfaces: Array<out String>): Class<*> {
        throw InvalidClassException("Proxy types are not allowed in legacy cache")
    }
}
