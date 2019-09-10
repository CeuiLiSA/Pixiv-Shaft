package ceui.lisa.http

import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.text.SimpleDateFormat
import java.util.*


/**
 * @author Aragaki
 *
 * thanks https://github.com/upbit/pixivpy/issues/83
 */
class PixivHeaders {
    var XClientTime: String
    var XClientHash: String

    init {
        val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZZZZZ", Locale.US)
        XClientTime = format.format(Date())

        val str = "${XClientTime}28c1fdd170a5204386cb1313c7077b34f83e4aaf4aa829ce78c231e05b0bae2c"
        XClientHash = md5(str)
    }

    fun md5(plainText: String): String {
        try {
            val md = MessageDigest.getInstance("MD5")
            md.update(plainText.toByteArray())
            val b = md.digest()

            var i: Int

            val buf = StringBuffer("")
            for (offset in b.indices) {
                i = b[offset].toInt()
                if (i < 0)
                    i += 256
                if (i < 16)
                    buf.append("0")
                buf.append(Integer.toHexString(i))
            }
            //32位加密
            return buf.toString()
            // 16位的加密
            //return buf.toString().substring(8, 24);
        } catch (e: NoSuchAlgorithmException) {
            e.printStackTrace()
            return ""
        }
    }
}






