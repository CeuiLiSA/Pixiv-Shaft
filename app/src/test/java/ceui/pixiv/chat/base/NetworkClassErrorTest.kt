package ceui.pixiv.chat.base

import com.google.gson.JsonParseException
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

/**
 * [isNetworkClassError] 决定错误态要不要多摆一个「去网络测试」入口。它的语义边界是
 * 「重试大概率还是失败、真正的出路是去诊断链路」——断网 / 超时 / SSL 属于这一类，
 * HTTP 业务错误（403 被拉黑、404 作品已删、5xx 服务端挂了）都不属于：那些页面上摆一个
 * 网络测试入口是把用户往错误方向引。
 *
 * 判定本身复用 [ceui.pixiv.chat.api.toAppError] 的分类，这里把两端都钉死，
 * 以后动那份 when 时不至于把这条 UI 语义悄悄改掉。
 */
class NetworkClassErrorTest {

    private fun httpError(code: Int): HttpException = HttpException(
        Response.error<Any>(code, "".toResponseBody("application/json".toMediaTypeOrNull())),
    )

    @Test
    fun `断网 超时 SSL 都算网络类错误`() {
        assertTrue("DNS 解析不了 = 断网", UnknownHostException("app-api.pixiv.net").isNetworkClassError())
        assertTrue("读写超时", SocketTimeoutException("timeout").isNetworkClassError())
        assertTrue("TLS 握手失败", SSLHandshakeException("handshake").isNetworkClassError())
        assertTrue("其余 IO 一律按断网算", IOException("broken pipe").isNetworkClassError())
    }

    @Test
    fun `HTTP 业务错误与服务端错误不算网络类错误`() {
        assertFalse("401 鉴权", httpError(401).isNetworkClassError())
        assertFalse("403 拉黑 / 会员限定", httpError(403).isNetworkClassError())
        assertFalse("404 作品已删", httpError(404).isNetworkClassError())
        assertFalse("429 限流", httpError(429).isNetworkClassError())
        assertFalse("500 服务端挂了", httpError(500).isNetworkClassError())
    }

    @Test
    fun `解析失败与未知异常不算网络类错误`() {
        assertFalse("响应体对不上 DTO", JsonParseException("bad json").isNetworkClassError())
        assertFalse("兜底分支", IllegalStateException("whatever").isNetworkClassError())
    }
}
