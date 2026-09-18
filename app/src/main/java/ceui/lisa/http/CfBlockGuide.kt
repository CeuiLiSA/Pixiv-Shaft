package ceui.lisa.http

import android.app.Activity
import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.lisa.core.JavaAsync
import ceui.pixiv.witstudio.dialog.WitDialog
import ceui.pixiv.witstudio.dialog.WitDialogAction
import com.blankj.utilcode.util.ActivityUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import timber.log.Timber
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Cloudflare 403 的一次性引导弹窗。
 *
 * ## 只弹一次，且是**内存态**
 *
 * 按产品要求：宿主生命期内只出现一次，进程被杀即忘（不落盘、不跨启动）。所以这里就是
 * 一个 [AtomicBoolean]，不用 MMKV / SharedPreferences。重装、重启进程都会重置 —— 这是
 * 期望行为，不是缺陷。
 *
 * **配额只在真正弹出前才消费**：拿不到前台 Activity 就原样退出、不占用这一次机会。
 * 否则一次后台请求就能把整台设备唯一的提示名额烧掉。
 *
 * ## 「换干净网络」在两种模式下含义不同
 *
 * - **直连**：被 CF 拦的是**用户自己的出口 IP** → 引导换出口网络。
 * - **自建源代理**：用户 IP 根本没参与，被拦的是**代理服务器的出口** → 引导换节点。
 *
 * 说错方向等于让用户白折腾（换 Wi-Fi 换不掉代理节点的坏名声），所以文案按模式分叉。
 *
 * ## 附带的 IP 来自 Cloudflare 自己
 *
 * 拦截页里**没有**客户端 IP（实测 pixiv 的定制页只有图标 + 标题 + 说明 + `© pixiv`），
 * 所以改从 `/cdn-cgi/trace` 取 —— 那是 CF 在**同一个边缘**上自己回显的
 * `ip=`，正是被拦的那个出口 IP，且实测在 WAF 拦截期间仍返回 200。
 *
 * 取不到就**不带 IP 行**，不编造、不阻塞弹窗。
 */
object CfBlockGuide {

    private const val TAG = "CfBlockGuide"

    /** 与 [AppApiProxyInterceptor] 里的常量同义。用来判断这次请求打的是不是自建源。 */
    private const val PIXIV_APP_API_HOST = "app-api.pixiv.net"

    private const val TRACE_PATH = "/cdn-cgi/trace"
    private const val TRACE_TIMEOUT_SECONDS = 3L

    /** 进程级唯一配额。CAS 成功即「已消费」。 */
    private val prompted = AtomicBoolean(false)

    /**
     * 判定为 CF 拦截后调用。**不保证一定弹**（拿不到前台 Activity 时静默放弃），
     * 也**不保证只调一次**（配额在内部把关）。
     *
     * @param response 触发本次判定的响应，用来推断实际应答的 host（直连 = pixiv，
     *                 代理 = 自建源），进而决定文案方向与 trace 打哪台主机
     */
    @JvmStatic
    fun maybeGuide(response: okhttp3.Response?) {
        if (prompted.get()) return
        // 已经在主线程就立即执行（Main.immediate），否则先切回主线程：
        // ActivityUtils.getTopActivity() 与弹窗都必须在主线程碰。
        JavaAsync.appScope.launch { runGuide(response) }
    }

    /**
     * 真正的流程。单独抽成函数是为了让 launch 的 lambda 保持单表达式 ——
     * 早退若写成 `return@launch`，就成了「lambda 末表达式上的多余标签返回」。
     */
    private suspend fun runGuide(response: okhttp3.Response?) {
        if (prompted.get()) return

        val activity = ActivityUtils.getTopActivity()
        if (activity == null || activity.isFinishing || activity.isDestroyed) {
            Timber.tag(TAG).d("没有可用的前台 Activity，保留这次提示机会")
            return
        }
        // 真正的消费点：确认能弹了才占名额。
        if (!prompted.compareAndSet(false, true)) return

        val proxyMode = isProxyMode(response)
        val traceHost = response?.request?.url

        val ip = withContext(Dispatchers.IO) { fetchTraceIp(traceHost) }
        show(activity, proxyMode, ip)
    }

    /** 给 toast / 页面内文案用的短句，方向同样按模式分叉。 */
    @JvmStatic
    fun shortMessage(proxyMode: Boolean): Int =
        if (proxyMode) R.string.cf_block_short_proxy else R.string.cf_block_short_direct

    /**
     * 这次 403 是不是「打自建源」时发生的。
     *
     * 只有在代理开关打开**且**实际应答的 host 已经不是 pixiv 时才算代理模式；
     * 代理开关开着但 host 仍是 app-api（比如代理地址非法被
     * [AppApiProxyInterceptor] 放行原请求）时，语义上仍是直连被拦。
     */
    @JvmStatic
    fun isProxyMode(response: okhttp3.Response?): Boolean {
        if (Shaft.sSettings?.isUseAppApiProxy != true) return false
        val host = response?.request?.url?.host ?: return true
        return host != PIXIV_APP_API_HOST
    }

    private fun show(activity: Activity, proxyMode: Boolean, ip: String?) {
        if (activity.isFinishing || activity.isDestroyed) return

        val message = buildString {
            append(
                activity.getString(
                    if (proxyMode) R.string.cf_block_message_proxy else R.string.cf_block_message_direct
                )
            )
            if (!ip.isNullOrEmpty()) {
                append("\n\n")
                append(
                    activity.getString(
                        if (proxyMode) R.string.cf_block_ip_proxy else R.string.cf_block_ip_direct,
                        ip,
                    )
                )
            }
        }

        try {
            WitDialog.MessageDialogBuilder(activity)
                .setTitle(R.string.cf_block_title)
                .setMessage(message)
                .addAction(0, android.R.string.ok, WitDialogAction.ACTION_PROP_POSITIVE) { d, _ ->
                    d.dismiss()
                }
                .show()
        } catch (e: Exception) {
            // 弹窗失败不该连坐调用方：调用方那边已经改成本地化文案了。
            Timber.tag(TAG).w(e, "show cf block dialog failed")
        }
    }

    /**
     * 取 CF 自己回显的出口 IP。
     *
     * 打的是**实际应答那台主机的** `/cdn-cgi/trace`，所以直连模式下拿到的是用户出口 IP，
     * 代理模式下拿到的是自建源入口看到的 IP —— 都是「CF 在这一跳上看到的那一个」。
     *
     * 任何一步失败都返回 null（不显示 IP 行）。
     */
    private fun fetchTraceIp(answeredUrl: HttpUrl?): String? {
        val host = answeredUrl ?: return null
        val traceUrl = host.newBuilder().encodedPath(TRACE_PATH).query(null).build()

        val client = traceClient()
        return try {
            client.newCall(Request.Builder().url(traceUrl).get().build()).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Timber.tag(TAG).d("trace HTTP %d，放弃显示 IP", resp.code)
                    return null
                }
                resp.body?.string()
                    ?.lineSequence()
                    ?.firstOrNull { it.startsWith("ip=") }
                    ?.removePrefix("ip=")
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { CfBlockDetector.maskIp(it) }
            }
        } catch (e: Exception) {
            Timber.tag(TAG).d(e, "trace 失败，放弃显示 IP")
            null
        } finally {
            // 本对象每进程最多走一次，用完即还：不留线程池和连接。
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }

    private fun traceClient(): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(TRACE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(TRACE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(TRACE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            // ★ 真正兜住这次请求的是 callTimeout，不是上面那三个。
            //
            // 直连模式下请求交给 [CronetInterceptor]，而它**完全不理会** connect/read/write
            // 超时 —— 用的是自己写死的 30 秒 deadline（见其源码里的注释）。少了 callTimeout，
            // 一次卡住的 trace 就会把引导弹窗拖到 30 秒后才出现，用户看到的是「点了半天没反应」，
            // 而弹窗本来就是为了让他立刻知道该换网络。
            //
            // callTimeout 之所以对 Cronet 也管用：OkHttp 到点会 cancel 这个 Call，
            // 而 CronetInterceptor 每 100ms 轮询一次 chain.call().isCanceled()，能立刻收手。
            .callTimeout(TRACE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .dns(IPv4OnlyDns)
            .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))

        // 直连模式下 app-api.pixiv.net 只有经 Cronet(QUIC) 才到得了，裸 OkHttp 会被
        // 带 pixiv SNI 的 TCP 握手直接 RST —— 那会让我们永远拿不到 IP。
        // 代理模式下 host 是自建源，不在 Cronet 白名单里，加了也自然放行。
        if (Shaft.sSettings?.isDirectConnect == true) {
            builder.addInterceptor(CronetInterceptor(CronetInterceptor.getEngine(Shaft.getContext())))
        }
        return builder.build()
    }
}
