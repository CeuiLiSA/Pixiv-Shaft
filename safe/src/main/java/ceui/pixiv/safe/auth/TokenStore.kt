package ceui.pixiv.safe.auth

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.tencent.mmkv.MMKV
import java.io.IOException
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal interface AuthKeyValueStore {
    fun decodeString(key: String): String?
    fun encodeString(key: String, value: String): Boolean
    fun removeValue(key: String)
    fun removeValues(keys: Array<String>)
    fun sync()
}

private class MmkvAuthKeyValueStore(
    private val mmkv: MMKV,
) : AuthKeyValueStore {
    override fun decodeString(key: String): String? = mmkv.decodeString(key)
    override fun encodeString(key: String, value: String): Boolean = mmkv.encode(key, value)
    override fun removeValue(key: String) = mmkv.removeValueForKey(key)
    override fun removeValues(keys: Array<String>) = mmkv.removeValuesForKeys(keys)
    override fun sync() = mmkv.sync()
}

/** Android-Keystore-backed storage for the first-party token pair. */
class TokenStore internal constructor(
    private val store: AuthKeyValueStore,
    private val gson: Gson,
) {
    constructor() : this(MmkvAuthKeyValueStore(MMKV.mmkvWithID(STORE_ID)), Gson())

    @Synchronized
    fun load(): AuthSession? {
        val envelope = store.decodeString(KEY_SESSION)?.takeIf { it.isNotBlank() } ?: return null
        return runCatching {
            val parts = envelope.split(':', limit = 3)
            require(parts.size == 3 && parts[0] == ENVELOPE_VERSION)
            val iv = Base64.decode(parts[1], Base64.NO_WRAP or Base64.URL_SAFE)
            val ciphertext = Base64.decode(parts[2], Base64.NO_WRAP or Base64.URL_SAFE)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
            val json = cipher.doFinal(ciphertext).toString(Charsets.UTF_8)
            gson.fromJson(json, AuthSession::class.java)
        }.getOrElse {
            AuthLog.warning("encrypted session could not be loaded; dropping local ciphertext", it)
            store.removeValue(KEY_SESSION)
            null
        }?.also {
            AuthLog.debug("encrypted session loaded uid=${it.uid} generation=${it.generation}")
        }
    }

    @Synchronized
    @Throws(IOException::class)
    fun save(session: AuthSession) {
        val encoded = runCatching { encrypt(session, getOrCreateKey()) }.getOrElse {
            deleteKey()
            encrypt(session, getOrCreateKey())
        }
        if (!store.encodeString(KEY_SESSION, encoded)) {
            throw IOException("failed to persist auth session")
        }
        // A rotated refresh token must be durable before the retried business
        // request is allowed to use it. Otherwise a process death can revive
        // the spent pair without its idempotency key.
        store.sync()
        AuthLog.debug("encrypted session persisted uid=${session.uid} generation=${session.generation}")
    }

    @Synchronized
    fun clear() {
        store.removeValue(KEY_SESSION)
        clearRefreshAttempt()
        AuthLog.debug("local auth session cleared")
    }

    @Synchronized
    fun deviceId(): String {
        store.decodeString(KEY_DEVICE_ID)?.takeIf { it.isNotBlank() }?.let { return it }
        val generated = UUID.randomUUID().toString()
        if (!store.encodeString(KEY_DEVICE_ID, generated)) {
            throw IOException("failed to persist auth device id")
        }
        store.sync()
        return generated
    }

    @Synchronized
    @Throws(IOException::class)
    fun refreshAttempt(sessionId: String, generation: Long): String {
        val existing = store.decodeString(KEY_REFRESH_ATTEMPT)
            ?.let { encoded -> runCatching { gson.fromJson(encoded, RefreshAttempt::class.java) }.getOrNull() }
        if (existing?.sessionId == sessionId &&
            existing.generation == generation &&
            existing.id.isNotBlank()
        ) {
            AuthLog.debug("durable refresh attempt reused generation=$generation")
            return existing.id
        }
        val created = UUID.randomUUID().toString()
        val encoded = gson.toJson(RefreshAttempt(sessionId, generation, created))
        if (!store.encodeString(KEY_REFRESH_ATTEMPT, encoded)) {
            throw IOException("failed to persist refresh attempt")
        }
        store.sync()
        AuthLog.debug("durable refresh attempt created generation=$generation")
        return created
    }

    @Synchronized
    fun clearRefreshAttempt() {
        store.removeValues(arrayOf(KEY_REFRESH_ATTEMPT, LEGACY_KEY_ATTEMPT_OWNER, LEGACY_KEY_ATTEMPT_ID))
        store.sync()
        AuthLog.debug("durable refresh attempt cleared")
    }

    private fun encrypt(session: AuthSession, key: SecretKey): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val ciphertext = cipher.doFinal(gson.toJson(session).toByteArray(Charsets.UTF_8))
        val flags = Base64.NO_WRAP or Base64.URL_SAFE
        return listOf(
            ENVELOPE_VERSION,
            Base64.encodeToString(cipher.iv, flags),
            Base64.encodeToString(ciphertext, flags),
        ).joinToString(":")
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    private fun deleteKey() {
        runCatching {
            KeyStore.getInstance(KEYSTORE).apply {
                load(null)
                deleteEntry(KEY_ALIAS)
            }
        }
        store.removeValue(KEY_SESSION)
    }

    private data class RefreshAttempt(
        @SerializedName("session_id") val sessionId: String,
        @SerializedName("generation") val generation: Long,
        @SerializedName("id") val id: String,
    )

    private companion object {
        const val STORE_ID: String = "pixshaft-auth-v2"
        const val KEY_SESSION: String = "session_ciphertext"
        const val KEY_DEVICE_ID: String = "device_id"
        const val KEY_REFRESH_ATTEMPT: String = "refresh_attempt"
        const val LEGACY_KEY_ATTEMPT_OWNER: String = "refresh_attempt_owner"
        const val LEGACY_KEY_ATTEMPT_ID: String = "refresh_attempt_id"
        const val ENVELOPE_VERSION: String = "v1"
        const val KEYSTORE: String = "AndroidKeyStore"
        const val KEY_ALIAS: String = "pixshaft.auth.v2.aes"
        const val TRANSFORMATION: String = "AES/GCM/NoPadding"
    }
}
