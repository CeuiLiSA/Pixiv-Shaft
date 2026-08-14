package ceui.pixiv.ui.search

import java.text.Normalizer
import java.util.Base64
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 搜索结果页的本地高风险词门控。
 *
 * 这不是对任何法律法规的解释，也不是外部服务返回的动态名单；它只是产品侧第一版人工维护词库。
 * 命中后由三个搜索数据源在网络请求之前直接返回空页，UI 再显示统一的「搜索结果未予显示」。
 *
 * 匹配前会做 NFKC、大小写折叠，并去掉空白、标点和零宽格式字符，防止简单拆字绕过。
 * 词库不以明文进入源码或资源：AES-256-GCM 载荷只在首次匹配时解密并建立内存索引。
 */
object SearchRiskPolicy {

    private const val IV_SIZE_BYTES = 12
    private const val TAG_SIZE_BITS = 128

    /** IV + ciphertext + GCM tag。Base64 本身不含任何可直接检索的词库明文。 */
    private const val ENCRYPTED_TERMS = "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"

    /** 32-byte key 以两份掩码保存，避免 APK 常量池里出现完整密钥字节串。 */
    private val keyMask = intArrayOf(
        255, 125, 179, 64, 192, 170, 99, 74, 168, 38, 108, 142, 86, 101, 182, 159,
        254, 97, 70, 210, 6, 244, 178, 62, 104, 136, 103, 105, 211, 178, 177, 211,
    )
    private val maskedKey = intArrayOf(
        102, 203, 49, 130, 58, 121, 204, 197, 152, 212, 205, 41, 138, 239, 20, 251,
        55, 49, 90, 163, 48, 171, 226, 133, 225, 103, 110, 164, 134, 245, 101, 248,
    )

    /** 首次命中搜索相关代码时解密一次；后续输入只复用内存索引，不重复做密码学运算。 */
    private val indexedTermsLazy = lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        decryptTerms()
            .map(::normalize)
            .distinct()
            .sortedByDescending(String::length)
    }
    private val indexedTerms: List<String> by indexedTermsLazy

    /** 搜索页创建时可在后台预热，避免首次键入承担密码提供器和解密的冷启动成本。 */
    @JvmStatic
    fun warmUp() {
        indexedTerms.size
    }

    /** 数据源只在尚未预热时切后台；热路径不为每一页额外调度协程。 */
    @JvmStatic
    fun isWarmedUp(): Boolean = indexedTermsLazy.isInitialized()

    @JvmStatic
    fun shouldWithhold(rawQuery: String?): Boolean {
        val normalizedQuery = normalize(rawQuery.orEmpty())
        if (normalizedQuery.isEmpty()) return false
        return indexedTerms.any(normalizedQuery::contains)
    }

    /** 命中时返回去除首尾空白后的完整原查询，供 UI 套进微博式提示；未命中返回 null。 */
    @JvmStatic
    fun withheldQuery(rawQuery: String?): String? {
        val trimmed = rawQuery?.trim().orEmpty()
        return trimmed.takeIf { it.isNotEmpty() && shouldWithhold(it) }
    }

    private fun decryptTerms(): List<String> {
        val payload = Base64.getDecoder().decode(ENCRYPTED_TERMS)
        require(payload.size > IV_SIZE_BYTES + TAG_SIZE_BITS / 8) { "invalid search policy payload" }
        val key = ByteArray(keyMask.size) { index ->
            (keyMask[index] xor maskedKey[index]).toByte()
        }
        val plaintext = try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(key, "AES"),
                GCMParameterSpec(TAG_SIZE_BITS, payload, 0, IV_SIZE_BYTES),
            )
            cipher.doFinal(payload, IV_SIZE_BYTES, payload.size - IV_SIZE_BYTES)
        } finally {
            key.fill(0)
            payload.fill(0)
        }
        return try {
            plaintext.toString(Charsets.UTF_8)
                .lineSequence()
                .filter(String::isNotEmpty)
                .toList()
        } finally {
            plaintext.fill(0)
        }
    }

    private fun normalize(value: String): String {
        val normalized = Normalizer.normalize(value, Normalizer.Form.NFKC)
            .lowercase(Locale.ROOT)
        return buildString(normalized.length) {
            normalized.forEach { char ->
                if (!char.isIgnoredSeparator()) append(char)
            }
        }
    }

    private fun Char.isIgnoredSeparator(): Boolean {
        if (isWhitespace()) return true
        return when (Character.getType(this)) {
            Character.SPACE_SEPARATOR.toInt(),
            Character.LINE_SEPARATOR.toInt(),
            Character.PARAGRAPH_SEPARATOR.toInt(),
            Character.FORMAT.toInt(),
            Character.CONNECTOR_PUNCTUATION.toInt(),
            Character.DASH_PUNCTUATION.toInt(),
            Character.START_PUNCTUATION.toInt(),
            Character.END_PUNCTUATION.toInt(),
            Character.INITIAL_QUOTE_PUNCTUATION.toInt(),
            Character.FINAL_QUOTE_PUNCTUATION.toInt(),
            Character.OTHER_PUNCTUATION.toInt() -> true
            else -> false
        }
    }
}
