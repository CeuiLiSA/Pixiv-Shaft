package ceui.pixiv.utils

import java.security.MessageDigest
import java.util.UUID

object TokenGenerator {

    /**
     * 生成一个类似 Git commit hash 的 token（长度默认 16 位十六进制）
     */
    fun generateToken(length: Int = 16): String {
        val uuid = UUID.randomUUID().toString() + System.currentTimeMillis()
        val sha1 = sha1Hex(uuid)
        return sha1.take(length)
    }

    /**
     * 使用 SHA-1 生成十六进制字符串
     */
    private fun sha1Hex(input: String): String {
        val md = MessageDigest.getInstance("SHA-1")
        val bytes = md.digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
