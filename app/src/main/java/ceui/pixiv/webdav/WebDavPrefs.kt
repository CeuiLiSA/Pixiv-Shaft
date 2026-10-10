package ceui.pixiv.webdav

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.tencent.mmkv.MMKV
import timber.log.Timber
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** 用户填写的 WebDAV 连接信息。[baseUrl] 已规范成以 `/` 结尾。 */
data class WebDavConfig(
    val baseUrl: String,
    val username: String,
    val password: String,
    /** 服务器上存放备份的目录（相对 [baseUrl]），可多级，如 `apps/Shaft`。 */
    val folder: String,
) {
    val isComplete: Boolean
        get() = baseUrl.isNotEmpty() && username.isNotEmpty() && password.isNotEmpty() && folder.isNotEmpty()

    companion object {
        const val DEFAULT_FOLDER = "Shaft"

        /** 补全末尾 `/`：WebDAV 目录 URL 不带斜杠时部分服务器（坚果云）会 301 甚至 404。 */
        fun normalizeBaseUrl(raw: String): String {
            val trimmed = raw.trim()
            return if (trimmed.isEmpty() || trimmed.endsWith("/")) trimmed else "$trimmed/"
        }

        /** 去掉首尾斜杠、空段与 `.` / `..`，`/apps//Shaft/` → `apps/Shaft`；目录不能跳出 [baseUrl]。 */
        fun normalizeFolder(raw: String): String =
            raw.split('/').map { it.trim() }
                .filter { it.isNotEmpty() && it != "." && it != ".." }
                .joinToString("/")
    }
}

/** 最近一次成功上传的备份：内容摘要 + 远端文件名，自动备份据此判断能不能跳过。 */
data class WebDavLastUpload(val digest: String, val name: String)

/** 最近一次 WebDAV 备份的结果，设置页与入口行展示用。 */
data class WebDavLastRun(val timeMs: Long, val success: Boolean, val message: String)

/**
 * WebDAV 配置的**设备本地**存储（独立 MMKV namespace）。
 *
 * 不进 [ceui.lisa.utils.Settings]：Settings 是备份 / 云同步的载荷本体，放进去 WebDAV 密码就会
 * 被打进上传到 WebDAV 的备份里。密码用 Android Keystore 的 AES-GCM 密钥加密后再落 MMKV
 * （做法同 `TokenStore`），密钥不可导出也不随系统备份迁移，所以这个 namespace 已在
 * backup_rules / data_extraction_rules 里整份排除；换机后需要重新填写。
 */
object WebDavPrefs {

    private const val MMKV_ID = "webdav_v1"
    private const val KEY_BASE_URL = "base_url"
    private const val KEY_USERNAME = "username"
    private const val KEY_PASSWORD = "password_enc"
    private const val KEY_FOLDER = "folder"
    private const val KEY_INCLUDE_HISTORY = "include_history"
    private const val KEY_AUTO_BACKUP = "auto_backup"
    private const val KEY_LAST_TIME = "last_time"
    private const val KEY_LAST_SUCCESS = "last_success"
    private const val KEY_LAST_MESSAGE = "last_message"
    private const val KEY_LAST_DIGEST = "last_digest"
    private const val KEY_LAST_UPLOAD_NAME = "last_upload_name"
    private const val KEY_DEVICE_ID = "device_id"

    private const val KEY_ALIAS = "shaft.webdav.aes"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val ENVELOPE_VERSION = "v1"

    private val store: MMKV by lazy { MMKV.mmkvWithID(MMKV_ID) }

    /** 安装实例的标识，独立于型号；同型号的两台设备不能共用备份保留额度。 */
    val deviceId: String
        get() = synchronized(this) {
            store.decodeString(KEY_DEVICE_ID) ?: UUID.randomUUID().toString().replace("-", "").also {
                store.encode(KEY_DEVICE_ID, it)
            }
        }

    fun load(): WebDavConfig = WebDavConfig(
        baseUrl = store.decodeString(KEY_BASE_URL).orEmpty(),
        username = store.decodeString(KEY_USERNAME).orEmpty(),
        password = decryptPassword(),
        folder = store.decodeString(KEY_FOLDER) ?: WebDavConfig.DEFAULT_FOLDER,
    )

    fun save(config: WebDavConfig) {
        store.encode(KEY_BASE_URL, config.baseUrl)
        store.encode(KEY_USERNAME, config.username)
        store.encode(KEY_FOLDER, config.folder)
        if (config.password.isEmpty()) {
            store.removeValueForKey(KEY_PASSWORD)
        } else {
            store.encode(KEY_PASSWORD, encrypt(config.password))
        }
        // 换了服务器 / 目录，上次上传的记录不再代表远端现状。
        lastUpload = null
    }

    var includeHistory: Boolean
        get() = store.decodeBool(KEY_INCLUDE_HISTORY, true)
        set(value) {
            store.encode(KEY_INCLUDE_HISTORY, value)
        }

    var autoBackup: Boolean
        get() = store.decodeBool(KEY_AUTO_BACKUP, false)
        set(value) {
            store.encode(KEY_AUTO_BACKUP, value)
        }

    var lastUpload: WebDavLastUpload?
        get() {
            val digest = store.decodeString(KEY_LAST_DIGEST) ?: return null
            val name = store.decodeString(KEY_LAST_UPLOAD_NAME) ?: return null
            return WebDavLastUpload(digest, name)
        }
        set(value) {
            if (value == null) {
                store.removeValuesForKeys(arrayOf(KEY_LAST_DIGEST, KEY_LAST_UPLOAD_NAME))
            } else {
                store.encode(KEY_LAST_DIGEST, value.digest)
                store.encode(KEY_LAST_UPLOAD_NAME, value.name)
            }
        }

    fun lastRun(): WebDavLastRun? {
        val time = store.decodeLong(KEY_LAST_TIME, 0L)
        if (time <= 0L) return null
        return WebDavLastRun(
            timeMs = time,
            success = store.decodeBool(KEY_LAST_SUCCESS, false),
            message = store.decodeString(KEY_LAST_MESSAGE).orEmpty(),
        )
    }

    fun recordRun(run: WebDavLastRun) {
        store.encode(KEY_LAST_TIME, run.timeMs)
        store.encode(KEY_LAST_SUCCESS, run.success)
        store.encode(KEY_LAST_MESSAGE, run.message)
    }

    private fun decryptPassword(): String {
        val envelope = store.decodeString(KEY_PASSWORD)?.takeIf { it.isNotBlank() } ?: return ""
        return runCatching {
            val parts = envelope.split(':', limit = 3)
            require(parts.size == 3 && parts[0] == ENVELOPE_VERSION)
            val iv = Base64.decode(parts[1], Base64.NO_WRAP or Base64.URL_SAFE)
            val ciphertext = Base64.decode(parts[2], Base64.NO_WRAP or Base64.URL_SAFE)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
            cipher.doFinal(ciphertext).toString(Charsets.UTF_8)
        }.getOrElse {
            // 密钥丢失（系统清了 Keystore / 文件被搬到别的设备）：密文已无法解开，删掉让用户重填。
            Timber.w(it, "WebDAV password could not be decrypted; dropping ciphertext")
            store.removeValueForKey(KEY_PASSWORD)
            ""
        }
    }

    private fun encrypt(plain: String): String {
        val cipher = runCatching {
            Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, getOrCreateKey()) }
        }.getOrElse {
            // 密钥损坏时重建一把；旧密文本来就解不开了。
            deleteKey()
            Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, getOrCreateKey()) }
        }
        val ciphertext = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        val flags = Base64.NO_WRAP or Base64.URL_SAFE
        return "$ENVELOPE_VERSION:${Base64.encodeToString(cipher.iv, flags)}:${Base64.encodeToString(ciphertext, flags)}"
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    private fun deleteKey() {
        runCatching {
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(KEY_ALIAS)
        }
    }
}
