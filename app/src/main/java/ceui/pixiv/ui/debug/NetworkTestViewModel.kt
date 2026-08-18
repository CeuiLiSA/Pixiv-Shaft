package ceui.pixiv.ui.debug

import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ceui.lisa.BuildConfig
import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.lisa.http.AppApiProxyInterceptor
import ceui.lisa.http.CronetInterceptor
import ceui.lisa.http.HttpDns
import ceui.lisa.http.ImageHostManager
import ceui.lisa.http.PixivHeaders
import ceui.lisa.http.RubySSLSocketFactory
import ceui.lisa.http.TrustAllCertManager
import ceui.lisa.utils.Params
import ceui.loxia.Client
import ceui.loxia.HeaderInterceptor
import ceui.loxia.WebHeaderInterceptor
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.ConnectionPool
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Protocol
import okhttp3.Request
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.UnknownHostException
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** 点分四段 IPv4 前缀（可后接 :port / 路径），用于把 IP 按「段」而不是按字符数脱敏。 */
private val IPV4_PREFIX = Regex("""^(\d{1,3})\.(\d{1,3})\.\d{1,3}\.\d{1,3}""")

/**
 * 代理地址脱敏：保留 scheme 和地址开头少量信息，后面统一替换为 ***。
 * 用于 PxveAPI 地址与自定义图片反代在 UI / 原始日志中的展示，避免泄露他人私有地址。
 * 例：https://your-proxy.domain → https://your***；https://192.168.10.109:3021 → https://192.168***
 *
 * IPv4 按**段**截断（保留前两段）而不是按字符数：按字符数截会随 IP 变短而越露越多，
 * 极端情况（https://1.2.3.4:8080）能把整个公网 IP 原样漏出来，正好背离脱敏的目的。
 */
internal fun maskProxyUrl(url: String): String {
    val trimmed = url.trim()
    val scheme = when {
        trimmed.startsWith("https://", ignoreCase = true) -> "https://"
        trimmed.startsWith("http://", ignoreCase = true) -> "http://"
        else -> ""
    }
    val rest = if (scheme.isEmpty()) trimmed else trimmed.substring(scheme.length)
    val ipv4 = IPV4_PREFIX.find(rest)
    val visible = if (ipv4 != null) {
        // IP：保留前两段（192.168 / 10.0），后两段与端口一律隐藏。
        "${ipv4.groupValues[1]}.${ipv4.groupValues[2]}"
    } else {
        // 域名（含 IPv6 字面量）：保留前 4 个字符。
        rest.take(4)
    }
    return "$scheme$visible***"
}

/**
 * 网络测试页的 IPv4-only DNS（OkHttp [Dns]），做法抄自 PR #1036 的 IPv4OnlyDns。
 *
 * 当前测试流程大多会先把选定 IPv4 钉进 [buildHandshakeClient]（pinnedDns），所以这个
 * DNS 实际未必会走到；保留它作为防守：当未钉 IP（fake-ip / 代理 / Cronet 绕过等）时，
 * 仍对 app-api.pixiv.net / www.pixiv.net 过滤系统 DNS 返回的 IPv6，避免被污染的 IPv6
 * 拖出多余的 connect 尝试。
 *
 * 只过滤这两个官方 Pixiv 域名，其它域名（用户自建 PxveAPI 代理、图片反代等）原样放行
 * ——代理可能有合法 IPv6，不能一刀切。
 *
 * 安全回退：如果系统 DNS 只返回 IPv6（例如 IPv6-only/NAT64 环境），保留原结果，
 * 不让 OkHttp 拿到空地址列表而引入新的“无地址”语义。
 */
private object PixivIpv4OnlyDns : Dns {

    private val IPV4_ONLY_HOSTS = setOf(
        "app-api.pixiv.net",
        "www.pixiv.net",
    )

    override fun lookup(hostname: String): List<InetAddress> {
        val all = Dns.SYSTEM.lookup(hostname)
        if (hostname !in IPV4_ONLY_HOSTS) return all

        val ipv4 = all.filterIsInstance<Inet4Address>()
        return if (ipv4.isNotEmpty()) ipv4 else all
    }
}

/** 单条步骤的语义状态，决定圆点 / pill 的颜色（在 Fragment 里按状态染 v3 颜色）。 */
enum class StepStatus { INFO, OK, WARN, FAIL, RUNNING, HIGH_LATENCY, EXTREME_LATENCY }

/** 目标卡里的一行：label + 可选等宽 detail（可多行）。 */
data class TestStep(
    val label: String,
    val detail: String? = null,
    val status: StepStatus = StepStatus.INFO,
)

/** 系统 DNS 的一次解析结果，统一拆好 IPv4 / IPv6 / fake-ip / 公网 IPv6，避免各处重复过滤。 */
private data class SysDnsResult(
    val all: List<InetAddress>,
    val ipv4: List<Inet4Address>,
    val ipv6: List<Inet6Address>,
    val fakeIps: List<String>,
    val publicIpv6: List<Inet6Address>,
) {
    val hasFakeIp: Boolean get() = fakeIps.isNotEmpty()
    val hasPublicIpv6: Boolean get() = publicIpv6.isNotEmpty()
}

/**
 * 单个目标（域名）的整体判定，决定卡片右上角 pill。
 */
enum class TargetStatus { RUNNING, OK, HIGH_LATENCY, EXTREME_LATENCY, DEGRADED, POLLUTED, POLLUTED_BYPASSED, FAILED }

data class TargetReport(
    val title: String,
    val subtitle: String,
    val status: TargetStatus = TargetStatus.RUNNING,
    val steps: List<TestStep> = emptyList(),
    /** 卡片状态 pill 右侧可选的并列提示（如图片尺寸探测失败），黄底。 */
    val extraPill: String? = null,
    /** 覆盖状态 pill 的默认文案（颜色仍按 [status] 取，如图片服务器失败显示红底「图片无法加载」）。 */
    val statusPillOverride: String? = null,
)

/** 全局总览判定，决定顶部总览卡。 */
enum class OverallStatus { CLEAN, HIGH_LATENCY, EXTREME_LATENCY, DEGRADED, POLLUTED, NETWORK_DOWN }

/** 一次性弹窗事件的载荷：标题资源 + 已组好的正文（污染 / fake-ip 共用一条通道）。 */
data class NetworkAlert(val titleRes: Int, val message: String)

/**
 * 网络测试页的全部状态与测试逻辑（按项目约定，网络/异步状态归 ViewModel，
 * Fragment 只渲染）。承载 [NetworkTestFragment]。
 *
 * 该页用于「无代理环境下」诊断 Pixiv 连通性；代理（尤其 fake-ip 模式）下 DNS/ICMP 无参考价值，
 * 检测到 fake-ip 即跳过 DNS/ICMP、仅测握手，并提示用户可改 DNS 模式补全。
 *
 * 单次诊断对 app-api.pixiv.net / www.pixiv.net / i.pximg.net / pixshaft.com 依次做：
 *   1. 系统 DNS 解析 + CIDR 比对（检测本机 DNS 污染 / fake-ip；命中弹 [pollutionAlert]）
 *   2. DoH / 直连开启时，额外展示并校验应用内 [HttpDns] 实际解析路径（污染时改走此路）
 *   3. TCP 443 连通性（延迟过低标注无参考性）
 *   4. 直连开启时额外做 ICMP/echo 可达性（代理下无意义，故仅直连）
 *   5. HTTPS 握手：持续 5s 多次采样，给 min/avg/max/抖动 + TLS/协议信息
 *   · www.pixiv.net 握手后再发一次真实网页请求（/ajax/illust/{样例}），验证 web 端点可用
 *   · 开启 PxveAPI 代理时，app-api 目标替换为用户填写的代理根地址，并追加
 *     /pixiv-app-api 与 /pixiv-oauth 两条转发路径的响应探测（https 在握手成功后测；
 *     Debug 模式允许 http 代理，跳过 HTTPS 握手直接测转发）
 *
 * 所有目标握手测完后追加「图片下载探测」：对样例作品的插画图 + 画师头像各下一张真图，
 * URL 先经 [ImageHostManager.rewrite] 重写 —— 与 Glide / Manager / Ugoira 同源，
 * 所以 pixiv.cat / pixiv.re / pixiv.nl / 自定义反代在这里天然生效。
 *
 * 关键约定（来自 PR #895 的方向）：第 5 步**按目标各自复刻线上真实连接路径**，否则测出来的
 * 「通/不通」对用户没有参考价值——见 [buildHandshakeClient]：
 *   · app-api / pixshaft：H2+H1；直连开启时经 [CronetInterceptor]（QUIC，绕 SNI 阻断）
 *   · www.pixiv.net：HTTP/1.1 + [WebHeaderInterceptor]（cookie/Host/UA）；直连开启时经 Cronet
 *   · i.pximg.net：直连开启时无 SNI + 信任所有证书 + 强制 HTTP/1.1（图片服务器按 IP 路由）
 *
 * 另含独立的「作品 API 探测」：填插画/漫画 ID，测 /v1/illust/detail 响应时间与内容。
 */
class NetworkTestViewModel : ViewModel() {

    val running = MutableLiveData(false)
    val illustRunning = MutableLiveData(false)
    val targets = MutableLiveData<List<TargetReport>>(emptyList())
    val overall = MutableLiveData<OverallStatus?>(null)
    val overallSub = MutableLiveData<String?>(null)
    val rawLog = MutableLiveData("")
    val illustReport = MutableLiveData<TargetReport?>(null)
    val imageDownloadRunning = MutableLiveData(false)
    val imageDownloadReport = MutableLiveData<TargetReport?>(null)
    val imageDownloadSlow = MutableLiveData(false)
    val imageDimensionFailed = MutableLiveData(false)
    /** 检测到 DNS 污染、但污染域握手均成功（DoH/直连绕过生效）：总览黄底污染 + 绿底「网络勉强可用」。 */
    val pollutionBypassed = MutableLiveData(false)
    /** 图片服务器（官方 i.pximg.net 或当前反代域名）握手失败：顶部总览并列红底「图片无法加载」pill。 */
    val imageTargetFailed = MutableLiveData(false)

    val dohEnabled: Boolean get() = Shaft.sSettings?.isUseSecureDns == true
    val directConnect: Boolean get() = Shaft.sSettings?.isDirectConnect == true
    val proxyEnabled: Boolean get() = Shaft.sSettings?.isUseAppApiProxy == true
    val proxyRoot: String? get() = Shaft.sSettings?.getAppApiProxy()?.let { AppApiProxyInterceptor.normalizeBase(it) }

    /** 一次性事件：检测到 DNS 污染 / fake-ip 时弹窗提醒（PR #894 的核心诉求）。 */
    private val _pollutionAlert = MutableSharedFlow<NetworkAlert>(extraBufferCapacity = 1)
    val pollutionAlert = _pollutionAlert.asSharedFlow()

    private val work = mutableListOf<TargetReport>()
    private val rawBuilder = StringBuilder()

    /** 图片下载阶段是否有步骤失败（供总体判定降级）。 */
    private var imageDownloadFailed = false

    /**
     * 图片下载卡的延迟档（HIGH_LATENCY / EXTREME_LATENCY，其余为 null）。
     * 下载卡不在 [work] 里，不参与 `work.any { ... }` 的统计——不单独喂给总览的话，
     * 会出现「顶上绿底『网络通畅』、下面红底『延迟极高』」的自相矛盾。
     */
    private var imageDownloadLatency: TargetStatus? = null

    /** http 反代等跳过连通性的图片目标：卡片判定以图片下载探测为准（探测结束回写该卡状态）。 */
    private var imageCardRelyOnDownload = false

    /** 本轮图片目标卡的标题（imageCfg.displayName ?: imageCfg.host），供下载阶段定位并回写该卡。 */
    private var imageTargetTitle: String? = null

    /** 本轮在途的 OkHttp 客户端；onCleared 时 cancelAll 中断阻塞中的 execute()，让测试尽快收尾。 */
    private val activeClients = Collections.newSetFromMap(ConcurrentHashMap<OkHttpClient, Boolean>())

    override fun onCleared() {
        // 退出页面：viewModelScope 取消只对挂起点生效，阻塞 IO（execute / isReachable）不会被打断——
        // 显式 cancel 在途 Call，配合 runTests 的阶段间 isActive 检查快速收尾，
        // 避免 VM 重建 / 重进页面时旧一轮还在跑、又并发开第二轮。
        activeClients.forEach { it.dispatcher.cancelAll() }
        super.onCleared()
    }

    /** PxveAPI 开启且地址合法时，用代理根地址替换 app-api.pixiv.net 目标；否则保持官方域名。 */
    private fun buildAppApiConfig(): TargetConfig {
        val ctx = Shaft.getContext()
        val root = proxyRoot
        if (root != null) {
            val url = try {
                root.toHttpUrl()
            } catch (e: Exception) {
                null
            }
            if (url != null) {
                val defaultPort = if (url.scheme == "http") 80 else 443
                val host = url.host + if (url.port != defaultPort) ":${url.port}" else ""
                return TargetConfig(
                    host = host,
                    subtitle = ctx.getString(R.string.network_test_target_sub_app_api_proxy, maskProxyUrl(root)),
                    cidrs = null,
                    kind = TargetKind.APP_API,
                    rootUrl = root,
                    displayName = maskProxyUrl(root),
                )
            }
        }
        return TargetConfig(
            host = APP_API_HOST,
            subtitle = ctx.getString(R.string.network_test_target_sub_app_api),
            cidrs = PIXIV_CIDRS,
            kind = TargetKind.APP_API,
        )
    }

    /** fake-ip 提示弹窗每轮测试只弹一次。 */
    private var fakeIpDialogShown = false

    /** 本轮是否检测到 fake-ip（网络通畅小字里据此去掉「DNS解析」）。 */
    private var fakeIpDetected = false

    /** www.pixiv.net 探测阶段拿到的样例作品图片地址 / 作者 ID，供下载阶段复用，避免重复请求。 */
    private var probeIllustUrl: String? = null
    private var probeUserId: String? = null

    /** 决定 [buildHandshakeClient] 用哪套客户端复刻线上连接。 */
    private enum class TargetKind { APP_API, IMAGE, PIXSHAFT, WEB_API }

    private data class TargetConfig(
        val host: String,
        val subtitle: String,
        val cidrs: List<String>?,
        val kind: TargetKind,
        /** 自定义反代为 http://（明文，非 https）：https 握手无法代表它，跳过连通性，以图片下载探测为准。 */
        val plainHttp: Boolean = false,
        /** PxveAPI 代理根地址（https://host[/path]；Debug 下可为 http://host[/path]，无尾斜杠）；仅 APP_API 走代理时非空。 */
        val rootUrl: String? = null,
        /** 卡片标题展示名；缺省用 [host]。PxveAPI 场景展示完整根地址。 */
        val displayName: String? = null,
    )

    private data class HandshakeResult(val ok: Boolean, val avgMs: Int, val maxMs: Int)

    fun runTests() {
        if (running.value == true) return
        running.value = true
        work.clear()
        targets.value = emptyList()
        overall.value = null
        overallSub.value = null
        imageDownloadReport.value = null
        imageDownloadSlow.value = false
        imageDimensionFailed.value = false
        pollutionBypassed.value = false
        imageTargetFailed.value = false
        imageDownloadFailed = false
        imageDownloadLatency = null
        imageCardRelyOnDownload = false
        imageTargetTitle = null
        fakeIpDialogShown = false
        fakeIpDetected = false
        probeIllustUrl = null
        probeUserId = null
        rawBuilder.setLength(0)
        rawLog.value = ""

        val doh = dohEnabled
        val direct = directConnect
        val appApiCfg = buildAppApiConfig()
        val appApiTitle = appApiCfg.displayName ?: appApiCfg.host

        viewModelScope.launch(Dispatchers.IO) {
            try {
                log(
                    "环境: 安全 DNS(DoH) ${onOff(doh)} · 直连 ${onOff(direct)} · " +
                        "App API 代理 ${appApiCfg.rootUrl?.let { maskProxyUrl(it) } ?: onOff(proxyEnabled)}",
                )
                log("")

                // 图片目标联动当前图片代理：反代模式下直接把目标域名换成反代域名来测。
                val imageProxy = imageProxyDomain()
                // 自定义反代可能是 http://（明文）——https 握手无法代表它，跳过该目标的连通性，
                // 卡片判定以图片下载探测为准（runImageDownloadPhase 结束会回写该卡状态）。
                val imagePlainHttp = ImageHostManager.getMode() == ImageHostManager.Mode.CUSTOM &&
                    ImageHostManager.getCustomHost().trim().startsWith("http://", ignoreCase = true)
                imageCardRelyOnDownload = imagePlainHttp
                val imageCfg = if (imageProxy != null) {
                    val imageDisplay = if (ImageHostManager.getMode() == ImageHostManager.Mode.CUSTOM) {
                        maskProxyUrl(imageProxy)
                    } else {
                        imageProxy
                    }
                    TargetConfig(
                        imageProxy,
                        Shaft.getContext().getString(R.string.network_test_target_sub_image_proxy, imageDisplay),
                        null,
                        TargetKind.IMAGE,
                        plainHttp = imagePlainHttp,
                        displayName = imageDisplay,
                    )
                } else {
                    TargetConfig(
                        "i.pximg.net",
                        Shaft.getContext().getString(R.string.network_test_target_sub_image_official),
                        PXIMG_CIDRS,
                        TargetKind.IMAGE,
                    )
                }
                imageTargetTitle = imageCfg.displayName ?: imageCfg.host
                val imageTitle = imageCfg.displayName ?: imageCfg.host
                val configs = listOf(
                    appApiCfg,
                    TargetConfig(
                        "www.pixiv.net",
                        Shaft.getContext().getString(R.string.network_test_target_sub_web),
                        PIXIV_CIDRS,
                        TargetKind.WEB_API,
                    ),
                    imageCfg,
                    TargetConfig(
                        "pixshaft.com",
                        Shaft.getContext().getString(R.string.network_test_target_sub_pixshaft),
                        null,
                        TargetKind.PIXSHAFT,
                    ),
                )
                val polluted = mutableListOf<String>()
                val bypassOk = mutableListOf<Boolean>()
                for (cfg in configs) {
                    val idx = addTarget(TargetReport(cfg.displayName ?: cfg.host, cfg.subtitle))
                    // Debug 模式允许 http PxveAPI：不测 HTTPS 握手，直接验证两条转发路径。
                    val plainHttpProxy = cfg.kind == TargetKind.APP_API &&
                        BuildConfig.IS_DEBUG_MODE &&
                        cfg.rootUrl?.startsWith("http://", ignoreCase = true) == true
                    if (plainHttpProxy) {
                        log("PxveAPI 为 http（Debug），跳过 HTTPS 握手，只测转发路径")
                        addStep(
                            idx,
                            TestStep(
                                strRes(R.string.network_test_pxve_http_skip),
                                strRes(R.string.network_test_pxve_http_skip_detail),
                                StepStatus.INFO,
                            ),
                        )
                        probePxveEndpoints(idx, cfg, direct)
                        // http 代理没有握手步骤可判定状态；两条转发路径都通过时置为 OK。
                        if (work[idx].status == TargetStatus.RUNNING) {
                            setStatus(idx, TargetStatus.OK)
                        }
                    } else {
                        val (isPolluted, hsOk) = testTarget(idx, cfg, doh, direct)
                        // PxveAPI 代理开启时，额外验证 /pixiv-app-api 与 /pixiv-oauth 转发路径
                        // 有正确响应（握手成功后才做；握手失败时卡片已 FAILED，无需重复报错）。
                        if (cfg.kind == TargetKind.APP_API && cfg.rootUrl != null && hsOk) {
                            probePxveEndpoints(idx, cfg, direct)
                        }
                        if (isPolluted) {
                            polluted.add(cfg.host)
                            bypassOk.add(hsOk)
                        }
                    }
                    // 退出页面：每个目标测完检查一次取消，提前收尾、不再测下一个
                    // （阻塞段内取消不了，段间放行；onCleared 已 cancel 在途 Call 加速中断）。
                    if (!isActive) {
                        log("测试已取消（退出页面），提前结束")
                        return@launch
                    }
                }

                // 所有目标握手测完之后，再做真实图片下载（插画 + 头像），联动当前图片代理。
                if (!isActive) {
                    log("测试已取消（退出页面），跳过图片下载探测")
                    return@launch
                }
                val (imageSlow, imageDimFailed) = runImageDownloadPhase()

                val anyFailed = work.any { it.status == TargetStatus.FAILED }
                // 图片下载卡不在 work 里，延迟档要单独并进来，否则总览会绿、下载卡红。
                val anyHighLatency = work.any { it.status == TargetStatus.HIGH_LATENCY } ||
                    imageDownloadLatency == TargetStatus.HIGH_LATENCY
                val anyExtremeLatency = work.any { it.status == TargetStatus.EXTREME_LATENCY } ||
                    imageDownloadLatency == TargetStatus.EXTREME_LATENCY
                val anyDegraded = work.any { it.status == TargetStatus.DEGRADED } || imageDownloadFailed
                // app-api 是 app 的主 API（PxveAPI 开启时即代理目标）：它失败（握手失败 /
                // 污染且未绕过 / 代理转发路径异常）＝网络不可用，
                // 总览必须是红底「网络不可用」，且绝不能报「网络勉强可用」。
                val appApiFailed = work.any {
                    it.title == appApiTitle &&
                        (it.status == TargetStatus.FAILED || it.status == TargetStatus.POLLUTED)
                }
                // 图片服务器（官方 i.pximg.net 或当前反代域名）失败：卡片 pill 覆盖为红底
                // 「图片无法加载」，顶部总览并列红底 pill，小字追加换图片代理的提示。
                // 局部变量用 imageFailed，避免与成员属性 imageTargetFailed 重名遮蔽。
                val imageFailed = work.any {
                    it.title == imageTitle &&
                        (it.status == TargetStatus.FAILED || it.status == TargetStatus.POLLUTED)
                }
                imageTargetFailed.postValue(imageFailed)
                // 污染但污染域握手全部成功 = 应用内绕过路径（DoH/直连）生效：
                // 总览改成黄底「检测到 DNS 污染」+ 绿底「网络勉强可用」并列，而不是刺眼的失败判定。
                // 绕过判定**只看污染域自身的握手结果**（bypassOk 只对污染域记录）；图片下载失败、
                // 延迟、其它目标（如 pixshaft.com）失败或降级各有独立卡片与 pill，不掺进绕过判定——
                // 否则「安全 DNS 开 + 污染域握手全成功、只是某个无关目标不可达」会被连带否决成红色污染。
                // 两个强制否决项：「网络勉强可用」的前提是主链路可用——
                //   · app-api 失败：主 API 都连不上，谈不上「勉强可用」；
                //   · 图片服务器失败：图片都加载不了，「勉强可用」与「图片无法加载」自相矛盾，互斥。
                val bypassActive = polluted.isNotEmpty() && bypassOk.all { it } && !appApiFailed && !imageFailed
                pollutionBypassed.postValue(bypassActive)
                // 高延迟与超高延迟都算「延迟高」，用于小字提示的判断。
                val latencyHosts = work.filter {
                    it.status == TargetStatus.HIGH_LATENCY || it.status == TargetStatus.EXTREME_LATENCY
                }.map { it.title }
                // 图片下载卡的延迟同样归到「图片代理慢」，小字给换图片代理的建议。
                val imageHighLatency = latencyHosts.contains(imageTitle) || imageDownloadLatency != null
                val otherHighLatency = latencyHosts.any { it != imageTitle }
                val ov = when {
                    appApiFailed -> OverallStatus.NETWORK_DOWN
                    polluted.isNotEmpty() -> OverallStatus.POLLUTED
                    anyFailed || anyDegraded -> OverallStatus.DEGRADED
                    anyExtremeLatency -> OverallStatus.EXTREME_LATENCY
                    anyHighLatency -> OverallStatus.HIGH_LATENCY
                    else -> OverallStatus.CLEAN
                }
                overall.postValue(ov)
                val ctx = Shaft.getContext()
                val imageHint = ctx.getString(R.string.network_test_high_latency_sub_image)
                val subText: String? = when {
                    ov == OverallStatus.NETWORK_DOWN -> {
                        // 主 API 失败：小字说明主 API 不可达；PxveAPI 开启时指向代理地址；
                        // 图片服务器也失败时再追加换代理提示。
                        var base = if (appApiCfg.rootUrl != null) {
                            ctx.getString(R.string.network_test_overall_unavailable_sub_proxy, maskProxyUrl(appApiCfg.rootUrl))
                        } else {
                            ctx.getString(R.string.network_test_overall_unavailable_sub)
                        }
                        if (imageFailed) base += "\n$imageHint"
                        base
                    }
                    ov == OverallStatus.HIGH_LATENCY || ov == OverallStatus.EXTREME_LATENCY -> {
                        val generic = ctx.getString(R.string.network_test_overall_high_latency_sub)
                        // 图片代理与其他端点都高延迟：通用提示在上，图片代理提示在下面一行。
                        when {
                            imageHighLatency && otherHighLatency -> "$generic\n$imageHint"
                            imageHighLatency -> imageHint
                            else -> generic
                        }
                    }
                    ov == OverallStatus.CLEAN -> {
                        // 总览小字按段拼接：fake-ip 去掉「DNS解析」段、尺寸探测失败去掉「尺寸探测」段、
                        // 下载缓慢换「图片下载缓慢」段。不再对整串做中文字面 replace——
                        // 旧做法在非中文 locale 下 replace 目标根本不存在（各语言旧译与中文文案不一致）。
                        val segs = mutableListOf<String>()
                        if (!fakeIpDetected) segs.add(ctx.getString(R.string.network_test_clean_seg_dns))
                        segs.add(ctx.getString(R.string.network_test_clean_seg_handshake))
                        if (!imageDimFailed) segs.add(ctx.getString(R.string.network_test_clean_seg_dim))
                        segs.add(
                            ctx.getString(
                                if (imageSlow) R.string.network_test_clean_seg_download_slow
                                else R.string.network_test_clean_seg_download_ok,
                            ),
                        )
                        var base = segs.joinToString(ctx.getString(R.string.network_test_clean_seg_sep))
                        if (imageDimFailed) {
                            base += "\n" + ctx.getString(R.string.network_test_dim_probe_failed_impact)
                        }
                        base
                    }
                    ov == OverallStatus.DEGRADED -> {
                        // 部分异常：小字说明连通性/握手问题；图片服务器失败时追加换代理提示。
                        var base = ctx.getString(R.string.network_test_overall_degraded_sub)
                        if (imageFailed) base += "\n$imageHint"
                        base
                    }
                    ov == OverallStatus.POLLUTED -> {
                        // 绕过生效：小字保持绕过说明（「小字不动」）；图片服务器同时失败时追加换代理提示。
                        // 绕过未生效：小字同「部分异常」；图片服务器失败时同样追加换代理提示。
                        var base = if (bypassActive) {
                            ctx.getString(R.string.network_test_overall_polluted_bypass_sub)
                        } else {
                            ctx.getString(R.string.network_test_overall_degraded_sub)
                        }
                        if (imageFailed) base += "\n$imageHint"
                        base
                    }
                    else -> null
                }
                overallSub.postValue(subText)
                // 图片服务器失败：卡片 pill 覆盖为红底「图片无法加载」（颜色仍按 FAILED/POLLUTED 取红）。
                val imageIdx = work.indexOfFirst { it.title == imageTitle }
                if (imageIdx >= 0 && imageFailed) {
                    work[imageIdx] = work[imageIdx].copy(
                        statusPillOverride = ctx.getString(R.string.network_test_image_unavailable),
                    )
                    publish()
                }
                log(
                    "总体判定: " + when (ov) {
                        OverallStatus.CLEAN -> "网络通畅"
                        OverallStatus.HIGH_LATENCY -> "有端点高延迟"
                        OverallStatus.EXTREME_LATENCY -> "有端点延迟极高"
                        OverallStatus.DEGRADED -> "部分异常"
                        OverallStatus.POLLUTED -> "DNS 污染"
                        OverallStatus.NETWORK_DOWN -> "网络不可用"
                    },
                )
                log("总览说明: $subText")
                if (polluted.isNotEmpty()) {
                    _pollutionAlert.emit(
                        NetworkAlert(R.string.network_test_pollution_dialog_title, buildPollutionMessage(polluted, doh, direct)),
                    )
                }
            } catch (e: Exception) {
                // 兜底：任何一步意外异常都不能把「测试中」按钮卡死或让协程静默死掉。
                // 但取消必须放行（onCleared / 退出页面时 scope 取消，吞掉会留下半场状态）。
                if (e is CancellationException) throw e
                log("测试异常终止: ${e.javaClass.simpleName}: ${e.message}")
                Timber.e(e, "network test aborted")
                overall.postValue(OverallStatus.DEGRADED)
                overallSub.postValue(Shaft.getContext().getString(R.string.network_test_aborted_sub))
            } finally {
                running.postValue(false)
            }
        }
    }

    /**
     * @return (该目标本机 DNS 是否被判定为污染, 握手是否成功)。
     *   第二元素在污染时决定「绕过是否生效」，同时也是 PxveAPI 转发路径探测的前置条件，
     *   所以每条 return 都要给出**真实**的握手结果，不能因为该分支不关心污染就恒填 false。
     */
    private fun testTarget(idx: Int, cfg: TargetConfig, doh: Boolean, direct: Boolean): Pair<Boolean, Boolean> {
        log("========== ${cfg.displayName ?: cfg.host} ==========")

        // http 反代：握手客户端只走 HTTPS，无法代表明文反代（且 host 常带非 443 端口），
        // 直接跳过连通性 / 握手，卡片判定由 runImageDownloadPhase 以真实下载结果回写。
        if (cfg.kind == TargetKind.IMAGE && cfg.plainHttp) {
            addStep(
                idx,
                TestStep(
                    strRes(R.string.network_test_skip_connectivity),
                    strRes(R.string.network_test_skip_http_proxy),
                    StepStatus.INFO,
                ),
            )
            // 占位即显示「跳过」：该卡不做连通性判定；下载成功时回写也保持「跳过」（见 syncImageTargetCard）。
            work[idx] = work[idx].copy(
                status = TargetStatus.OK,
                statusPillOverride = strRes(R.string.network_test_pill_skipped),
            )
            publish()
            log("图片目标为 http 反代，跳过握手，以图片下载探测为准")
            log("")
            return false to true
        }

        // 自定义反代可能带端口（imageProxyDomain() 保留 host:port）：DNS 解析与 TCP 探测要拆开。
        val dnsHost = cfg.host.substringBefore(':')
        val connPort = cfg.host.substringAfter(':', "443").toIntOrNull() ?: 443

        val sysAddrs = try {
            InetAddress.getAllByName(dnsHost).toList()
        } catch (e: UnknownHostException) {
            addStep(
                idx,
                TestStep(
                    strRes(R.string.network_test_dns_step),
                    strRes(R.string.network_test_dns_resolve_failed, e.message),
                    StepStatus.FAIL,
                ),
            )
            log("系统 DNS 解析失败: ${e.message}")
            setStatus(idx, TargetStatus.FAILED)
            return false to false
        }
        val dns = analyzeSysDns(sysAddrs)
        val ipv4 = dns.ipv4
        // fake-ip 检测：代理接管 DNS 时返回保留地址。不取消目标——跳过 DNS/ping，仅测握手，
        // 且不再把解析出的 IP 喂给 OkHttp（让代理接管路由）。
        if (dns.hasFakeIp) {
            addStep(
                idx,
                TestStep(
                    strRes(R.string.network_test_dns_step),
                    strRes(R.string.network_test_dns_fakeip, dns.fakeIps.joinToString()),
                    StepStatus.WARN,
                ),
            )
            log("检测到 fake-ip: ${dns.fakeIps.joinToString()}，跳过 DNS/ping，仅测握手")
            fakeIpDetected = true
            val hs = httpsHandshakeSampled(idx, cfg, null, direct, fakeIp = true)
            // 握手走标准 TLS，成功即证书链有效；max/avg 超阈值判延迟异常。
            setStatus(
                idx,
                when {
                    !hs.ok -> TargetStatus.FAILED
                    hs.maxMs > EXTREME_LATENCY_MS -> TargetStatus.EXTREME_LATENCY
                    hs.avgMs > HIGH_LATENCY_MS -> TargetStatus.HIGH_LATENCY
                    else -> TargetStatus.OK
                },
            )
            if (hs.ok) appendLatencyToSubtitle(idx, hs.avgMs, hs.maxMs)
            if (!fakeIpDialogShown) {
                fakeIpDialogShown = true
                log("fake-ip 提示弹窗（本轮仅一次）")
                viewModelScope.launch {
                    _pollutionAlert.emit(
                        NetworkAlert(R.string.network_test_fakeip_dialog_title, strRes(R.string.network_test_fakeip_dialog_body)),
                    )
                }
            }
            log("")
            // fake-ip 下不判污染（isPolluted=false，bypassOk 不消费第二元素），但握手结果要如实返回：
            // Clash 等 fake-ip 环境正是自建 PxveAPI 的典型场景，恒填 false 会让转发路径探测整段不跑。
            return false to hs.ok
        }
        log("DNS: " + dns.all.joinToString(", ") { it.hostAddress ?: "?" })

        // 没开 PxveAPI 时，官方 Pixiv 域名如果被系统 DNS 返回公网 IPv6，直接视为 DNS 污染。
        // 只认公网 IPv6：fake-ip 给的非公网 IPv6 不走这条判定（仍由上面的 fake-ip / 代理逻辑处理）。
        val pixivPublicIpv6 = !proxyEnabled && isPixivDomain(dnsHost) && dns.hasPublicIpv6
        if (pixivPublicIpv6) {
            log("官方 Pixiv 域名返回公网 IPv6，直接判 DNS 污染")
        }
        var polluted = pixivPublicIpv6

        // 一次算好 IPv4 的 CIDR 命中与可用的 cleanV4，后续展示、握手、判定共用，避免重复过滤。
        val cidrHits = cfg.cidrs?.let { cidrs ->
            ipv4.map { a -> a to cidrs.firstOrNull { isIpInCidr(a.hostAddress ?: "", it) } }
        }
        val cleanV4 = if (cidrHits != null) cidrHits.filter { it.second != null }.map { it.first } else ipv4
        val cleanCount = cleanV4.size

        if (cidrHits != null) {
            val sb = StringBuilder()
            for ((a, hit) in cidrHits) {
                val ip = a.hostAddress ?: continue
                if (hit != null) {
                    sb.append(strRes(R.string.network_test_dns_hit, ip, hit))
                } else {
                    sb.append(strRes(R.string.network_test_dns_miss, ip))
                }
            }
            dns.ipv6.forEach { sb.append(strRes(R.string.network_test_dns_ipv6_skip, it.hostAddress)) }
            polluted = (ipv4.isNotEmpty() && cleanCount == 0) || polluted
            val st = when {
                polluted -> StepStatus.FAIL
                ipv4.isEmpty() -> StepStatus.WARN
                cleanCount < ipv4.size -> StepStatus.WARN
                else -> StepStatus.OK
            }
            addStep(idx, TestStep(strRes(R.string.network_test_dns_step_count, dns.all.size), sb.toString().trimEnd(), st))
        } else {
            addStep(
                idx,
                TestStep(
                    strRes(R.string.network_test_dns_step_count, dns.all.size),
                    dns.all.joinToString("\n") { strRes(R.string.network_test_dns_item, it.hostAddress) },
                    StepStatus.OK,
                ),
            )
        }

        // DoH / 直连开启时，展示应用真正使用的解析路径（HttpDns）。仅对 pixiv 域名有意义
        // —— HttpDns 只为 pixiv API/图片域名兜底，pixshaft.com 不走它。
        var appIp: Inet4Address? = null
        if ((doh || direct) && cfg.cidrs != null) {
            try {
                val appAddrs = HttpDns.getInstance().lookup(cfg.host)
                val appV4 = appAddrs.filterIsInstance<Inet4Address>()

                // 判断应用内解析结果是否干净
                val sb = StringBuilder()
                var appClean = 0
                for (a in appV4) {
                    val ip = a.hostAddress ?: continue
                    val hit = cfg.cidrs.firstOrNull { isIpInCidr(ip, it) }
                    if (hit != null) {
                        appClean++
                        sb.append(strRes(R.string.network_test_dns_hit, ip, hit))
                    } else {
                        sb.append(strRes(R.string.network_test_dns_miss, ip))
                    }
                }
                appAddrs.filter { it !is Inet4Address }
                    .forEach { sb.append(strRes(R.string.network_test_dns_ipv6_app_skip, it.hostAddress)) }

                val appPolluted = appV4.isNotEmpty() && appClean == 0
                val st = when {
                    appV4.isEmpty() -> StepStatus.WARN
                    appPolluted -> StepStatus.FAIL
                    appClean < appV4.size -> StepStatus.WARN
                    else -> StepStatus.OK
                }

                addStep(
                    idx,
                    TestStep(
                        strRes(R.string.network_test_dns_app_step, appAddrs.size),
                        sb.toString().trimEnd(),
                        st,
                    ),
                )
                log("HttpDns: " + appAddrs.joinToString(", ") { it.hostAddress ?: "?" })

                // 只取干净的应用内解析结果
                appIp = if (cfg.cidrs != null) {
                    appV4.firstOrNull { a -> cfg.cidrs.any { isIpInCidr(a.hostAddress ?: "", it) } }
                } else {
                    appV4.firstOrNull()
                }
            } catch (e: Exception) {
                addStep(
                    idx,
                    TestStep(
                        strRes(R.string.network_test_dns_app_step_plain),
                        strRes(R.string.network_test_dns_app_failed, e.message),
                        StepStatus.WARN,
                    ),
                )
            }
        }

        // 选用于后续连通性 / 握手的目标 IP：优先干净的系统解析，污染时退到应用内解析路径。
        val targetIp: Inet4Address? = cleanV4.firstOrNull() ?: appIp
        if (targetIp == null) {
            // 直连 + Cronet 覆盖的域名（APP_API / WEB_API）：真实 app 直连时走
            // CronetInterceptor 的 host_resolver_rules 钉 Cloudflare IP，根本不经本地 DNS——
            // 本机解析被污染也应仍跑一次 Cronet 握手（不钉 IP），否则「app 明明能用」会被
            // appApiFailed → NETWORK_DOWN 误诊成主 API 不可达。
            if (polluted && direct &&
                (cfg.kind == TargetKind.APP_API || cfg.kind == TargetKind.WEB_API)
            ) {
                log("本机 DNS 不可信，直连下 ${cfg.host} 由 Cronet 钉 IP——改走 Cronet 握手（不钉 IP）")
                addStep(
                    idx,
                    TestStep(
                        strRes(R.string.network_test_skip_cronet),
                        strRes(R.string.network_test_skip_cronet_detail, cfg.host),
                        StepStatus.WARN,
                    ),
                )
                val hs = httpsHandshakeSampled(idx, cfg, null, direct, bypassDns = true)
                val status = when {
                    !hs.ok -> TargetStatus.POLLUTED
                    hs.maxMs > EXTREME_LATENCY_MS -> TargetStatus.EXTREME_LATENCY
                    hs.avgMs > HIGH_LATENCY_MS -> TargetStatus.HIGH_LATENCY
                    else -> TargetStatus.POLLUTED_BYPASSED
                }
                setStatus(idx, status)
                if (hs.ok) appendLatencyToSubtitle(idx, hs.avgMs, hs.maxMs)
                log("")
                return polluted to hs.ok
            }
            val detail = if (polluted) {
                strRes(R.string.network_test_skip_polluted)
            } else {
                strRes(R.string.network_test_skip_no_ipv4)
            }
            addStep(idx, TestStep(strRes(R.string.network_test_skip_connectivity), detail, StepStatus.WARN))
            log("跳过后续: $detail")
            setStatus(idx, if (polluted) TargetStatus.POLLUTED else TargetStatus.FAILED)
            return polluted to false
        }
        if (polluted && cleanV4.isEmpty() && targetIp === appIp) {
            addStep(
                idx,
                TestStep(
                    strRes(R.string.network_test_bypass_step),
                    strRes(R.string.network_test_bypass_detail, targetIp.hostAddress),
                    StepStatus.WARN,
                ),
            )
        }

        tcpPing(idx, targetIp.hostAddress ?: "", connPort)
        if (direct) icmpPing(idx, targetIp)
        val hs = httpsHandshakeSampled(idx, cfg, targetIp, direct)
        // www.pixiv.net 握手后再发一次真实网页请求，验证 web 端点；失败只降级不算握手失败。
        var webDegraded = false
        if (cfg.kind == TargetKind.WEB_API && hs.ok) {
            webDegraded = !probeWebEndpoint(idx, cfg, targetIp, direct)
        }

        val status = when {
            polluted && !hs.ok -> TargetStatus.POLLUTED
            polluted -> TargetStatus.POLLUTED_BYPASSED
            !hs.ok -> TargetStatus.FAILED
            hs.maxMs > EXTREME_LATENCY_MS -> TargetStatus.EXTREME_LATENCY
            hs.avgMs > HIGH_LATENCY_MS -> TargetStatus.HIGH_LATENCY
            webDegraded -> TargetStatus.DEGRADED
            cfg.cidrs != null && cleanV4.size < ipv4.size -> TargetStatus.DEGRADED
            else -> TargetStatus.OK
        }
        setStatus(idx, status)
        if (hs.ok) appendLatencyToSubtitle(idx, hs.avgMs, hs.maxMs)
        log("")
        return polluted to hs.ok
    }

    private fun tcpPing(idx: Int, ip: String, port: Int) {
        try {
            val t0 = System.currentTimeMillis()
            Socket().use { it.connect(InetSocketAddress(ip, port), 3000) }
            val ms = System.currentTimeMillis() - t0
            val stepLabel = strRes(R.string.network_test_tcp_step, port)
            if (ms <= 10) {
                addStep(idx, TestStep(stepLabel, strRes(R.string.network_test_tcp_too_fast, ms), StepStatus.WARN))
            } else {
                addStep(idx, TestStep(stepLabel, strRes(R.string.network_test_tcp_ok, ms), StepStatus.OK))
            }
            log("TCP $port: ${ms}ms")
        } catch (e: Exception) {
            addStep(
                idx,
                TestStep(strRes(R.string.network_test_tcp_step, port), strRes(R.string.network_test_tcp_fail, e.message), StepStatus.FAIL),
            )
            log("TCP $port 不可达: ${e.message}")
        }
    }

    /** 仅直连模式做 —— 代理下测 ICMP 无意义（ICMP 会透过代理）。 */
    private fun icmpPing(idx: Int, ip: Inet4Address) {
        val samples = mutableListOf<Long>()
        repeat(3) {
            try {
                val t0 = System.currentTimeMillis()
                if (ip.isReachable(2000)) samples.add(System.currentTimeMillis() - t0)
            } catch (_: Exception) {
            }
        }
        val stepLabel = strRes(R.string.network_test_icmp_step)
        if (samples.isNotEmpty()) {
            val avg = samples.average().toInt()
            addStep(idx, TestStep(stepLabel, strRes(R.string.network_test_icmp_ok, samples.size, avg), StepStatus.OK))
            log("ICMP: ${samples.size}/3 avg ${avg}ms")
        } else {
            addStep(idx, TestStep(stepLabel, strRes(R.string.network_test_icmp_warn), StepStatus.WARN))
            log("ICMP: 0/3")
        }
    }

    /**
     * 为目标构建与线上同源的 OkHttpClient —— 测什么路径就用 app 真实连这个域名时的那套
     * （见 [Client] 的 createAPPAPI / createPixshaftService 与 Shaft 图片 client）：
     *   · APP_API / PIXSHAFT：H2+H1；直连开启时挂 [CronetInterceptor]（请求转 QUIC，绕 SNI 阻断）。
     *   · IMAGE：直连开启时无 SNI（[RubySSLSocketFactory]）+ 信任所有证书 + 关主机名校验 + 强制 HTTP/1.1。
     * 连接池 0 空闲 → 每次调用都重新握手；默认用 [pinnedDns] 把域名钉到本次选定的 IP；
     * 未钉 IP（fake-ip / 代理 / Cronet 绕过）时用 [PixivIpv4OnlyDns] 过滤 Pixiv 两个域名的污染 IPv6。
     * Cronet 路径除外，其走自身 host-resolver 规则，固定到 Cloudflare IP。fake-ip 模式传 pin=false 不钉 IP，
     * 交给系统 DNS + 代理接管路由。
     */
    private fun buildHandshakeClient(cfg: TargetConfig, ip: Inet4Address?, direct: Boolean, pin: Boolean = true): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectionPool(ConnectionPool(0, 1, TimeUnit.SECONDS))
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .writeTimeout(5, TimeUnit.SECONDS)
            .dns(PixivIpv4OnlyDns)
        if (pin && ip != null) {
            val pinnedDns = object : Dns {
                override fun lookup(hostname: String): List<InetAddress> = listOf(ip)
            }
            builder.dns(pinnedDns)
        }
        when (cfg.kind) {
            TargetKind.APP_API -> {
                builder.protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
                builder.addInterceptor(HeaderInterceptor())
                if (direct) addCronet(builder)
            }
            TargetKind.IMAGE -> {
                // 直连覆写（无 SNI / HttpDns 硬编码 IP）只对官方 i.pximg.net 有效；
                // 反代模式（pixiv.cat / 自定义反代）必须走系统 DNS + 标准 TLS。
                if (direct && !ImageHostManager.requiresStandardClient()) {
                    try {
                        builder.sslSocketFactory(RubySSLSocketFactory(), TrustAllCertManager())
                        builder.hostnameVerifier { _, _ -> true }
                    } catch (e: Exception) {
                        Timber.e(e, "image no-SNI SSL init error")
                    }
                }
                // Shaft 的图片客户端全局强制 HTTP/1.1，握手也要复刻这条路径。
                builder.protocols(listOf(Protocol.HTTP_1_1))
            }
            TargetKind.PIXSHAFT -> {
                builder.protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
                if (direct) addCronet(builder)
            }
            TargetKind.WEB_API -> {
                // 镜像 createWebAPIService：H1 + Web 头 + 直连 Cronet。
                builder.protocols(listOf(Protocol.HTTP_1_1))
                builder.addInterceptor(WebHeaderInterceptor())
                if (direct) addCronet(builder)
            }
        }
        return builder.build().also { activeClients.add(it) }
    }

    private fun addCronet(builder: OkHttpClient.Builder) {
        builder.addInterceptor(CronetInterceptor(CronetInterceptor.getEngine(Shaft.getContext())))
    }

    /** 该目标本次握手实际走的路径，标在步骤 label 上让用户看清测的是哪条链路。 */
    private fun handshakePathDesc(cfg: TargetConfig, direct: Boolean): String = when (cfg.kind) {
        TargetKind.IMAGE -> {
            if (direct && !ImageHostManager.requiresStandardClient()) strRes(R.string.network_test_path_no_sni)
            else strRes(R.string.network_test_path_standard_tls)
        }
        else -> if (direct) strRes(R.string.network_test_path_cronet) else strRes(R.string.network_test_path_standard_tls)
    }

    /**
     * HTTPS 握手：用 [buildHandshakeClient] 复刻该目标的真实连接，持续 5s 反复建连
     * （连接池 0 空闲，每次都重新握手），实时刷新 min/avg/max/抖动。
     */
    private fun httpsHandshakeSampled(
        idx: Int,
        cfg: TargetConfig,
        ip: Inet4Address?,
        direct: Boolean,
        fakeIp: Boolean = false,
        bypassDns: Boolean = false,
    ): HandshakeResult {
        val pathDesc = when {
            fakeIp -> strRes(R.string.network_test_path_fakeip)
            bypassDns -> strRes(R.string.network_test_path_cronet_bypass)
            else -> handshakePathDesc(cfg, direct)
        }
        val stepIdx = work[idx].steps.size
        addStep(
            idx,
            TestStep(strRes(R.string.network_test_hs_step, pathDesc), strRes(R.string.network_test_sampling), StepStatus.RUNNING),
        )
        // fake-ip：不走直连覆写（Cronet / 无 SNI / HttpDns 对代理无意义），标准 TLS + 系统 DNS。
        // bypassDns：本机 DNS 不可信，但直连下域名由 Cronet host_resolver_rules 钉 IP——
        // 不把（被污染的）解析结果钉进 OkHttp，交给 Cronet 自己的规则解析。
        val client = try {
            buildHandshakeClient(cfg, ip, if (fakeIp) false else direct, pin = !fakeIp && !bypassDns)
        } catch (e: Exception) {
            // 客户端构建失败（如 Cronet 引擎初始化异常）也要落成步骤失败，而不是中断整轮测试。
            log("握手客户端构建失败: ${e.javaClass.simpleName}: ${e.message}")
            updateStep(idx, stepIdx, strRes(R.string.network_test_hs_build_failed, "${e.javaClass.simpleName}: ${e.message}"), StepStatus.FAIL)
            return HandshakeResult(false, 0, 0)
        }
        // SNI 由 socket 工厂控制，与 URL 无关；统一用域名即可（无 SNI 路径仍会被 RubySSLSocketFactory 抹掉）。
        val request = Request.Builder().url("https://${cfg.host}/").head().build()

        val samples = mutableListOf<Long>()
        var fail = 0
        var tls: String? = null
        var cipher: String? = null
        var proto: String? = null
        var firstErr: String? = null

        try {
            val deadline = System.currentTimeMillis() + 5000
            var n = 0
            // viewModelScope.isActive：退出页面后立刻停采样，不再等到 5s 窗口耗尽。
            while (System.currentTimeMillis() < deadline && n < 15 && viewModelScope.isActive) {
                n++
                val t0 = System.currentTimeMillis()
                try {
                    client.newCall(request).execute().use { resp ->
                        samples.add(System.currentTimeMillis() - t0)
                        proto = resp.protocol.toString()
                        resp.handshake?.let {
                            tls = it.tlsVersion.javaName
                            cipher = it.cipherSuite.javaName
                        }
                    }
                } catch (e: Exception) {
                    fail++
                    if (firstErr == null) firstErr = e.javaClass.simpleName + (e.message?.let { ": $it" } ?: "")
                }
                updateStep(idx, stepIdx, handshakeDetail(samples, fail, tls, cipher, proto, firstErr), StepStatus.RUNNING)
            }
        } finally {
            activeClients.remove(client)
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }

        val ok = samples.isNotEmpty()
        val avgMs = if (samples.isNotEmpty()) samples.average().toInt() else 0
        val maxMs = if (samples.isNotEmpty()) samples.max().toInt() else 0
        val st = when {
            !ok -> StepStatus.FAIL
            fail > 0 -> StepStatus.WARN
            else -> StepStatus.OK
        }
        updateStep(idx, stepIdx, handshakeDetail(samples, fail, tls, cipher, proto, firstErr), st)
        log(
            "HTTPS($pathDesc): 成功 ${samples.size} / 失败 $fail" +
                if (ok) " · min ${samples.min()} avg $avgMs max ${samples.max()} ms · ${tls ?: proto}"
                else " · ${firstErr ?: ""}",
        )
        return HandshakeResult(ok, avgMs, maxMs)
    }

    private fun handshakeDetail(
        samples: List<Long>,
        fail: Int,
        tls: String?,
        cipher: String?,
        proto: String?,
        firstErr: String?,
    ): String {
        val sb = StringBuilder()
        if (samples.isNotEmpty()) {
            val min = samples.min()
            val max = samples.max()
            val avg = samples.average().toInt()
            sb.append(strRes(R.string.network_test_hs_success, samples.size))
            if (fail > 0) sb.append(strRes(R.string.network_test_hs_fail_count, fail))
            sb.append(strRes(R.string.network_test_hs_stats, min, avg, max, max - min))
            if (tls != null) {
                sb.append(strRes(R.string.network_test_hs_tls, tls))
                cipher?.let { sb.append(strRes(R.string.network_test_hs_cipher, it)) }
            } else {
                // Cronet/QUIC 路径短路了 OkHttp 的 TLS 层，没有握手对象，只能报协商出的协议。
                proto?.let { sb.append(strRes(R.string.network_test_hs_proto_direct, it)) }
            }
        } else {
            sb.append(strRes(R.string.network_test_hs_all_failed, fail))
            firstErr?.let { sb.append(strRes(R.string.network_test_hs_first_err, it)) }
            sb.append(strRes(R.string.network_test_hs_reasons))
        }
        return sb.toString()
    }

    /**
     * www.pixiv.net 的真实网页请求：GET /ajax/illust/{样例作品}（匿名 SFW 可用），
     * 校验返回 error=false 且带图片地址；顺带把地址/作者 ID 存下来给图片下载阶段复用。
     * @return 是否成功拿到有效响应（失败只把目标降为 DEGRADED，不算握手失败）。
     */
    private fun probeWebEndpoint(idx: Int, cfg: TargetConfig, ip: Inet4Address, direct: Boolean): Boolean {
        val stepIdx = work[idx].steps.size
        addStep(
            idx,
            TestStep(strRes(R.string.network_test_web_step, SAMPLE_ILLUST_ID), strRes(R.string.network_test_requesting), StepStatus.RUNNING),
        )
        var client: OkHttpClient? = null
        var ok = false
        try {
            client = buildHandshakeClient(cfg, ip, direct)
            val t0 = System.currentTimeMillis()
            val request = Request.Builder()
                .url("https://www.pixiv.net/ajax/illust/$SAMPLE_ILLUST_ID?lang=zh")
                .get()
                .build()
            client.newCall(request).execute().use { resp ->
                val ms = System.currentTimeMillis() - t0
                val json = runCatching { Gson().fromJson(resp.body?.string(), JsonObject::class.java) }.getOrNull()
                val error = json?.get("error")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: true
                val bodyObj = json?.getAsJsonObject("body")
                val urls = bodyObj?.getAsJsonObject("urls")
                val small = urls?.get("small")?.takeIf { it.isJsonPrimitive }?.asString
                val thumb = urls?.get("thumb")?.takeIf { it.isJsonPrimitive }?.asString
                val regular = urls?.get("regular")?.takeIf { it.isJsonPrimitive }?.asString
                val url = small ?: thumb ?: regular
                probeIllustUrl = url
                probeUserId = bodyObj?.get("userId")?.takeIf { it.isJsonPrimitive }?.asString
                ok = resp.code in 200..299 && !error && url != null
                if (ok) {
                    updateStep(idx, stepIdx, strRes(R.string.network_test_web_ok, resp.code, ms, SAMPLE_ILLUST_ID), StepStatus.OK)
                    log("网页请求: HTTP ${resp.code} ${ms}ms url=$url")
                } else {
                    updateStep(idx, stepIdx, strRes(R.string.network_test_web_degraded, resp.code, error), StepStatus.WARN)
                    log("网页请求异常: HTTP ${resp.code} error=$error")
                }
            }
        } catch (e: Exception) {
            updateStep(idx, stepIdx, strRes(R.string.network_test_web_failed, "${e.javaClass.simpleName}: ${e.message}"), StepStatus.WARN)
            log("网页请求失败: ${e.message}")
        } finally {
            client?.let {
                activeClients.remove(it)
                it.connectionPool.evictAll()
                it.dispatcher.executorService.shutdown()
            }
        }
        return ok
    }

    // ---- PxveAPI 代理转发路径探测（仅开启代理时） ----

    /**
     * PxveAPI 代理开启时，验证代理的 /pixiv-app-api 与 /pixiv-oauth 两条转发路径有正确响应。
     * 探测 URL 一律由 [AppApiProxyInterceptor.rewrite] 生成 —— 和生产请求同一套拼接逻辑，
     * 自己手拼 `root + "/pixiv-app-api"` 会绕开它的双前缀去重：用户把根地址按
     * 「误填完整 PxveAPI 地址」的形态填成 https://proxy/pixiv-app-api（拦截器专门兼容、有单测
     * 覆盖）时，手拼会多出一层前缀打成 404，把一个实际能用的配置诬告成代理异常。
     * 用真实子路径请求（而不是只打前缀）避免 Hono 通配路由对裸前缀返回 404 的误报。
     * 判定：200..499 且非 404 视为链路通（400/401/403 是无凭证时 Pixiv 的预期响应）；
     * 404 / 5xx / 连接失败视为代理异常，并把该目标置为失败。
     * Debug 模式允许 http 代理：此时不测 HTTPS 握手，客户端也退回标准 HTTP，不走 Cronet。
     */
    private fun probePxveEndpoints(idx: Int, cfg: TargetConfig, direct: Boolean) {
        val root = cfg.rootUrl ?: return
        val plainHttp = root.startsWith("http://", ignoreCase = true)
        val appStepIdx = work[idx].steps.size
        addStep(
            idx,
            TestStep(strRes(R.string.network_test_pxve_app_api_step), strRes(R.string.network_test_requesting), StepStatus.RUNNING),
        )
        val oauthStepIdx = work[idx].steps.size
        addStep(
            idx,
            TestStep(strRes(R.string.network_test_pxve_oauth_step), strRes(R.string.network_test_requesting), StepStatus.RUNNING),
        )

        var client: OkHttpClient? = null
        try {
            // http 代理只做明文请求验证，不套 Cronet/QUIC；https 代理继续复刻真实握手客户端。
            client = buildHandshakeClient(cfg, null, if (plainHttp) false else direct, pin = false)
            val appOk = runPxveRequest(
                idx,
                appStepIdx,
                client,
                AppApiProxyInterceptor.rewrite(APP_API_PROBE_URL.toHttpUrl(), root),
                R.string.network_test_pxve_app_api_path,
            )
            val oauthOk = runPxveRequest(
                idx,
                oauthStepIdx,
                client,
                AppApiProxyInterceptor.rewrite(OAUTH_PROBE_URL.toHttpUrl(), root),
                R.string.network_test_pxve_oauth_path,
                method = "POST",
            )
            if (!appOk || !oauthOk) {
                setStatus(idx, TargetStatus.FAILED)
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            val msg = e.javaClass.simpleName + (e.message?.let { ": $it" } ?: "")
            updateStep(idx, appStepIdx, strRes(R.string.network_test_pxve_failed, msg), StepStatus.FAIL)
            updateStep(idx, oauthStepIdx, strRes(R.string.network_test_pxve_failed, msg), StepStatus.FAIL)
            log("PxveAPI 转发探测异常终止: $msg")
            setStatus(idx, TargetStatus.FAILED)
        } finally {
            client?.let {
                activeClients.remove(it)
                it.connectionPool.evictAll()
                it.dispatcher.executorService.shutdown()
            }
        }
    }

    private fun runPxveRequest(
        idx: Int,
        stepIdx: Int,
        client: OkHttpClient,
        url: HttpUrl?,
        pathLabelRes: Int,
        method: String = "GET",
    ): Boolean {
        if (url == null) {
            // root 来自 normalizeBase，正常不可能拼不出来；真拼不出来就是地址非法，如实报错。
            updateStep(idx, stepIdx, strRes(R.string.network_test_pxve_failed, "invalid proxy url"), StepStatus.FAIL)
            log("PxveAPI ${strRes(pathLabelRes)}: 代理地址非法，无法拼出探测 URL")
            return false
        }
        val t0 = System.currentTimeMillis()
        try {
            val requestBuilder = Request.Builder().url(url)
            // /pixiv-oauth/auth/token 的真实语义是 POST；用 GET 会被部分后端直接 404，造成误报。
            if (method == "POST") {
                requestBuilder.post(ByteArray(0).toRequestBody())
            } else {
                requestBuilder.get()
            }
            client.newCall(requestBuilder.build()).execute().use { resp ->
                val ms = System.currentTimeMillis() - t0
                val code = resp.code
                val ok = code in 200..499 && code != 404
                val detail = when {
                    code == 404 -> strRes(R.string.network_test_pxve_not_found, ms, code, strRes(pathLabelRes))
                    code in 200..299 -> strRes(R.string.network_test_pxve_ok_2xx, ms, code)
                    code == 400 || code == 401 || code == 403 -> strRes(R.string.network_test_pxve_ok_auth, ms, code)
                    ok -> strRes(R.string.network_test_pxve_ok_other, ms, code)
                    else -> strRes(R.string.network_test_pxve_fail_status, ms, code)
                }
                updateStep(idx, stepIdx, detail, if (ok) StepStatus.OK else StepStatus.FAIL)
                log("PxveAPI ${strRes(pathLabelRes)}: $method HTTP $code · ${ms}ms")
                return ok
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            val msg = e.javaClass.simpleName + (e.message?.let { ": $it" } ?: "")
            updateStep(idx, stepIdx, strRes(R.string.network_test_pxve_failed, msg), StepStatus.FAIL)
            log("PxveAPI ${strRes(pathLabelRes)} 连接失败: $msg")
            return false
        }
    }

    // ---- 图片下载探测（插画 + 头像，联动图片加载代理）----

    /**
     * 所有目标握手测完后调用：对样例作品的插画图与画师头像各下一张真图。
     * URL 优先复用 www.pixiv.net 探测的实时结果，否则现场抓，再不行用内置样例地址；
     * 下载前统一经 [ImageHostManager.rewrite] 重写，客户端按 [buildImageDownloadClient] 复刻
     * Shaft 的图片客户端（反代模式下走系统 DNS + 标准 TLS）。
     * @return (是否下载缓慢, 尺寸探测是否失败)，供总览小字在同一线程直接使用，
     *         避免跨线程读 LiveData.value 拿到 postValue 派发前的旧值。
     */
    private suspend fun runImageDownloadPhase(): Pair<Boolean, Boolean> {
        if (imageDownloadRunning.value == true) return false to false
        // 本函数跑在 Dispatchers.IO，LiveData 只能用 postValue 更新。
        imageDownloadRunning.postValue(true)
        imageDownloadFailed = false

        val title = strRes(R.string.network_test_dl_title)
        val sub = strRes(R.string.network_test_dl_sub, SAMPLE_ILLUST_ID)
        val steps = mutableListOf<TestStep>()
        fun push(step: TestStep) {
            steps.add(step)
            imageDownloadReport.postValue(TargetReport(title, sub, TargetStatus.RUNNING, steps.toList()))
        }
        /** 原位替换最后一步：running 占位 → 最终结果，保持卡片渐进更新。 */
        fun replaceLast(step: TestStep) {
            if (steps.isNotEmpty()) steps[steps.size - 1] = step
            imageDownloadReport.postValue(TargetReport(title, sub, TargetStatus.RUNNING, steps.toList()))
        }
        // 预置空白卡片：地址准备 + 尺寸探测在慢网上可能耗时十几秒，先让卡片立刻出现
        // （RUNNING + 空步骤），步骤随后逐条填充，不再“干等半天后啪一下整张弹出”。
        imageDownloadReport.postValue(TargetReport(title, sub, TargetStatus.RUNNING, emptyList()))
        var dimOk = false
        var downloadSlow = false
        try {
            // 1. 地址准备：优先复用 www.pixiv.net 探测结果，否则现场抓，再不行用内置样例。
            push(TestStep(strRes(R.string.network_test_dl_prepare), strRes(R.string.network_test_fetching), StepStatus.RUNNING))
            var illustUrl = probeIllustUrl
            var avatarUrl: String? = null
            var dimSource = strRes(R.string.network_test_dl_source_probe)
            val fallbackSource = strRes(R.string.network_test_dl_source_fallback)
            if (illustUrl != null) {
                avatarUrl = probeUserId?.let { fetchAvatarUrl(it) }
            } else {
                val fetched = fetchSampleUrls()
                illustUrl = fetched.first
                avatarUrl = fetched.second
                dimSource = if (illustUrl != null) strRes(R.string.network_test_dl_source_live) else fallbackSource
            }
            if (illustUrl == null) illustUrl = FALLBACK_ILLUST_URL
            if (avatarUrl == null) avatarUrl = FALLBACK_AVATAR_URL
            replaceLast(
                TestStep(
                    strRes(R.string.network_test_dl_prepare),
                    strRes(R.string.network_test_dl_prepare_done, dimSource),
                    if (dimSource == fallbackSource) StepStatus.WARN else StepStatus.OK,
                ),
            )
            log("图片下载地址: $dimSource")
            log("  插画: $illustUrl")
            log("  头像: $avatarUrl")
            // 退出页面：地址准备（含现场抓取）可能耗时，段间检查取消及时收尾。
            coroutineContext.ensureActive()

            // 2. 探测图片尺寸：网页 ajax 拿第一页真实宽高；拿不到就并列黄底「探测失败」。
            push(TestStep(strRes(R.string.network_test_dl_dim), strRes(R.string.network_test_fetching), StepStatus.RUNNING))
            val (dimStep, dimProbeOk) = probeImageDimensions(dimSource)
            dimOk = dimProbeOk
            replaceLast(dimStep)
            imageDimensionFailed.postValue(!dimOk)
            coroutineContext.ensureActive()

            val hostDesc = imageHostDesc()
            log("图片代理路由: $hostDesc")
            push(TestStep(strRes(R.string.network_test_dl_route), hostDesc, StepStatus.INFO))

            push(TestStep(strRes(R.string.network_test_dl_illust), strRes(R.string.network_test_downloading), StepStatus.RUNNING))
            val (illustStep, illustSlow) = downloadImageStep(strRes(R.string.network_test_dl_illust), illustUrl)
            replaceLast(illustStep)
            coroutineContext.ensureActive()
            push(TestStep(strRes(R.string.network_test_dl_avatar), strRes(R.string.network_test_downloading), StepStatus.RUNNING))
            // 头像只有几 KB，传输耗时恒小，不做「下载缓慢」判定（吞吐对小文件无意义）。
            val (avatarStep, avatarSlow) = downloadImageStep(strRes(R.string.network_test_dl_avatar), avatarUrl, judgeSpeed = false)
            replaceLast(avatarStep)

            downloadSlow = illustSlow || avatarSlow
            imageDownloadSlow.postValue(downloadSlow)

            val cardStatus = when {
                steps.any { it.status == StepStatus.FAIL } -> TargetStatus.FAILED
                steps.any { it.status == StepStatus.EXTREME_LATENCY } -> TargetStatus.EXTREME_LATENCY
                steps.any { it.status == StepStatus.HIGH_LATENCY } -> TargetStatus.HIGH_LATENCY
                downloadSlow -> TargetStatus.DEGRADED
                else -> TargetStatus.OK
            }
            imageDownloadFailed = cardStatus == TargetStatus.FAILED
            imageDownloadLatency = cardStatus.takeIf {
                it == TargetStatus.HIGH_LATENCY || it == TargetStatus.EXTREME_LATENCY
            }
            log(
                "图片下载结果: $cardStatus · 尺寸探测=${if (dimOk) "成功" else "失败"}" +
                    " · 下载缓慢=$downloadSlow",
            )
            imageDownloadReport.postValue(
                TargetReport(
                    title,
                    sub,
                    cardStatus,
                    steps.toList(),
                    extraPill = if (dimOk) null else Shaft.getContext().getString(R.string.network_test_dim_probe_failed),
                ),
            )
            // http 反代等跳过连通性的图片目标：卡片判定以图片下载探测为准。
            if (imageCardRelyOnDownload) syncImageTargetCard(cardStatus)
        } catch (e: Exception) {
            // 取消必须放行（退出页面 → scope 取消），不能当成「下载阶段异常」落失败卡。
            if (e is CancellationException) throw e
            log("图片下载阶段异常: ${e.javaClass.simpleName}: ${e.message}")
            val failStep = TestStep(
                strRes(R.string.network_test_dl_fail),
                e.javaClass.simpleName + (e.message?.let { ": $it" } ?: ""),
                StepStatus.FAIL,
            )
            // 最后一步若还是 running 占位，原位替换成失败，避免占位与失败两条并存。
            if (steps.isNotEmpty() && steps.last().status == StepStatus.RUNNING) {
                replaceLast(failStep)
            } else {
                push(failStep)
            }
            imageDownloadFailed = true
            imageDownloadLatency = null
            imageDownloadSlow.postValue(false)
            imageDownloadReport.postValue(
                TargetReport(
                    title,
                    sub,
                    TargetStatus.FAILED,
                    steps.toList(),
                    extraPill = if (!dimOk) Shaft.getContext().getString(R.string.network_test_dim_probe_failed) else null,
                ),
            )
            if (imageCardRelyOnDownload) syncImageTargetCard(TargetStatus.FAILED)
            return false to !dimOk
        } finally {
            imageDownloadRunning.postValue(false)
        }
        return downloadSlow to !dimOk
    }

    /**
     * 图片下载专用客户端，镜像 Shaft.onCreate 的图片 OkHttpClient：
     * 全局强制 HTTP/1.1；「PIXIV 官方 + (直连 或 安全 DNS)」装 HttpDns 覆写，
     * 其中直连还额外装无 SNI / 信任所有证书，
     * 其它模式（pixiv.cat 等反代、自定义反代）退回系统 DNS + 标准 TLS。
     */
    private fun buildImageDownloadClient(): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectionPool(ConnectionPool(0, 1, TimeUnit.SECONDS))
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(8, TimeUnit.SECONDS)
            .protocols(listOf(Protocol.HTTP_1_1))
        if ((directConnect || dohEnabled) && !ImageHostManager.requiresStandardClient()) {
            if (directConnect) {
                try {
                    builder.sslSocketFactory(RubySSLSocketFactory(), TrustAllCertManager())
                    builder.hostnameVerifier { _, _ -> true }
                } catch (e: Exception) {
                    Timber.e(e, "image download no-SNI SSL init error")
                }
            }
            builder.dns(HttpDns.getInstance())
        }
        return builder.build().also { activeClients.add(it) }
    }

    /** 网页探测专用客户端，镜像 createWebAPIService：H1.1 + Web 头；直连开启时经 Cronet(QUIC)。 */
    private fun buildWebProbeClient(): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .dns(PixivIpv4OnlyDns)
            .protocols(listOf(Protocol.HTTP_1_1))
            .addInterceptor(WebHeaderInterceptor())
        if (directConnect) addCronet(builder)
        return builder.build().also { activeClients.add(it) }
    }

    /** 下一张真图：HTTP 200 + magic bytes 校验，报告字节数 / TTFB / 数据传输耗时 / 吞吐 / 实际请求 host。
     *  @param judgeSpeed 是否做「下载缓慢」判定：头像只有 ~10KB，测得吞吐几乎全是 RTT 噪声，传 false 跳过。
     *  @return (步骤, 是否下载缓慢)。 */
    private fun downloadImageStep(label: String, rawUrl: String, judgeSpeed: Boolean = true): Pair<TestStep, Boolean> {
        val realUrl = ImageHostManager.rewrite(rawUrl)
        val customImageProxy = ImageHostManager.getMode() == ImageHostManager.Mode.CUSTOM
        val displayRealUrl = if (customImageProxy) maskProxyUrl(realUrl) else realUrl
        val client = buildImageDownloadClient()
        val t0 = System.currentTimeMillis()
        var result: Pair<TestStep, Boolean>
        try {
            val pixivHeaders = PixivHeaders()
            val request = Request.Builder()
                .url(realUrl)
                .header("referer", Params.IMAGE_REFERER)
                .header("user-agent", Params.PHONE_MODEL)
                .header("x-client-time", pixivHeaders.xClientTime)
                .header("x-client-hash", pixivHeaders.xClientHash)
                .get()
                .build()
            result = client.newCall(request).execute().use { resp ->
                // TTFB：响应头到达（execute 返回）。注意连接池为 0，每次都是新建连接，
                // 所以这个 TTFB 天然含 TCP+TLS 握手再加一个往返，必然大于握手采样值——
                // 不能套握手的 500/1000ms 阈值，用 IMAGE_TTFB_* 单独一套（见常量注释）。
                val ttfb = System.currentTimeMillis() - t0
                val code = resp.code
                val body = resp.body
                if (body == null) {
                    log("$label: HTTP $code 无响应体")
                    TestStep(label, strRes(R.string.network_test_dl_no_body, code), StepStatus.FAIL) to false
                } else {
                    val (data, truncated) = readCapped(body.byteStream(), MAX_IMAGE_DOWNLOAD_BYTES)
                    val ms = System.currentTimeMillis() - t0
                    val format = imageMagic(data)
                    val sizeTxt = formatBytes(data.size) + if (truncated) "+" else ""
                    // 数据传输时间 = 整包耗时 - TTFB（不含服务端首字节前的处理），吞吐按它算。
                    val transferMs = (ms - ttfb).coerceAtLeast(0)
                    val speedKBs = if (transferMs > 0) data.size * 1000L / transferMs / 1024 else -1L
                    val speedTxt = if (speedKBs >= 0) "${speedKBs}KB/s" else "-"
                    val host = realUrl.substringAfter("://").substringBefore('/')
                    val displayHost = if (customImageProxy) maskProxyUrl(host) else host
                    val ok = code in 200..299 && format != null

                    // 「下载缓慢」按吞吐判，不按绝对毫秒：样例插画只有 ~39KB，新建连接的
                    // TCP 慢启动下光是收完 body 就要 1~2 个 RTT，绝对毫秒阈值等于在量 RTT
                    // 而不是量带宽——RTT 稍高的线路（代理、移动网络）会恒亮「下载缓慢」。
                    // 体积不足 MIN_SPEED_SAMPLE_BYTES（头像 ~10KB）测得的吞吐几乎全是噪声，跳过判定。
                    val slow = judgeSpeed &&
                        data.size >= MIN_SPEED_SAMPLE_BYTES &&
                        speedKBs >= 0 && speedKBs < SLOW_THROUGHPUT_KBS
                    val tags = mutableListOf<String>()
                    if (ok) {
                        if (ttfb > IMAGE_TTFB_EXTREME_MS) {
                            tags.add(strRes(R.string.network_test_dl_tag_extreme))
                        } else if (ttfb > IMAGE_TTFB_HIGH_MS) {
                            tags.add(strRes(R.string.network_test_dl_tag_high))
                        }
                        if (slow) tags.add(strRes(R.string.network_test_dl_tag_slow))
                    }
                    val tagTxt = if (tags.isNotEmpty()) " · " + tags.joinToString(" · ") else ""
                    log(
                        "$label: HTTP $code · ${data.size}B · TTFB ${ttfb}ms · 传输 ${transferMs}ms · $speedTxt · $format$tagTxt · " +
                            "$rawUrl -> $displayRealUrl",
                    )
                    val st = when {
                        !ok -> StepStatus.FAIL
                        ttfb > IMAGE_TTFB_EXTREME_MS -> StepStatus.EXTREME_LATENCY
                        ttfb > IMAGE_TTFB_HIGH_MS -> StepStatus.HIGH_LATENCY
                        slow -> StepStatus.WARN
                        else -> StepStatus.OK
                    }
                    val detail = if (ok) {
                        strRes(R.string.network_test_dl_detail_ok, code, sizeTxt, ttfb, transferMs, speedTxt, format, tagTxt, displayHost)
                    } else {
                        strRes(
                            R.string.network_test_dl_detail_fail,
                            code, sizeTxt, ms,
                            if (format != null) " · $format" else "",
                            displayHost,
                        )
                    }
                    TestStep(label, detail, st) to slow
                }
            }
        } catch (e: Exception) {
            Timber.e(e)
            log("$label: 请求失败 ${e.message}")
            result = TestStep(label, strRes(R.string.network_test_dl_request_failed, "${e.javaClass.simpleName}: ${e.message}"), StepStatus.FAIL) to false
        } finally {
            activeClients.remove(client)
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
        return result
    }

    /** 现场抓样例作品的插画地址 + 画师头像地址（匿名 SFW 可用），失败返回 null。 */
    private fun fetchSampleUrls(): Pair<String?, String?> {
        val client = buildWebProbeClient()
        return try {
            var illustUrl: String? = null
            var userId: String? = null
            client.newCall(
                Request.Builder().url("https://www.pixiv.net/ajax/illust/$SAMPLE_ILLUST_ID?lang=zh").get().build(),
            ).execute().use { resp ->
                val json = runCatching { Gson().fromJson(resp.body?.string(), JsonObject::class.java) }.getOrNull()
                val bodyObj = json?.getAsJsonObject("body")
                val urls = bodyObj?.getAsJsonObject("urls")
                val small = urls?.get("small")?.takeIf { it.isJsonPrimitive }?.asString
                val thumb = urls?.get("thumb")?.takeIf { it.isJsonPrimitive }?.asString
                val regular = urls?.get("regular")?.takeIf { it.isJsonPrimitive }?.asString
                illustUrl = small ?: thumb ?: regular
                userId = bodyObj?.get("userId")?.takeIf { it.isJsonPrimitive }?.asString
            }
            val avatarUrl = userId?.let { fetchAvatarUrl(it, client) }
            illustUrl to avatarUrl
        } catch (e: Exception) {
            Timber.e(e)
            log("现场抓取样例地址失败: ${e.message}")
            null to null
        } finally {
            activeClients.remove(client)
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
    }

    /** GET /ajax/user/{id} 拿画师头像地址；[client] 为空时自建一个。 */
    private fun fetchAvatarUrl(userId: String, shared: OkHttpClient? = null): String? {
        val client = shared ?: buildWebProbeClient()
        return try {
            client.newCall(
                Request.Builder().url("https://www.pixiv.net/ajax/user/$userId?full=1&lang=zh").get().build(),
            ).execute().use { resp ->
                val json = runCatching { Gson().fromJson(resp.body?.string(), JsonObject::class.java) }.getOrNull()
                val bodyObj = json?.getAsJsonObject("body")
                bodyObj?.get("image")?.takeIf { it.isJsonPrimitive }?.asString
            }
        } catch (e: Exception) {
            Timber.e(e)
            log("获取画师头像地址失败: ${e.message}")
            null
        } finally {
            if (shared == null) {
                activeClients.remove(client)
                client.connectionPool.evictAll()
                client.dispatcher.executorService.shutdown()
            }
        }
    }

    /**
     * 探测图片尺寸：走网页 ajax /ajax/illust/{id}/pages（每页真实宽高），取第一页即可，
     * 不拉图片本身。@return (步骤, 是否拿到尺寸)。
     */
    private fun probeImageDimensions(source: String): Pair<TestStep, Boolean> {
        val client = buildWebProbeClient()
        val t0 = System.currentTimeMillis()
        return try {
            val request = Request.Builder()
                .url("https://www.pixiv.net/ajax/illust/$SAMPLE_ILLUST_ID/pages?lang=zh")
                .get()
                .build()
            client.newCall(request).execute().use { resp ->
                val ms = System.currentTimeMillis() - t0
                val json = runCatching { Gson().fromJson(resp.body?.string(), JsonObject::class.java) }.getOrNull()
                val error = json?.get("error")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: true
                val firstPage = json?.getAsJsonArray("body")?.firstOrNull()?.asJsonObject
                val w = firstPage?.get("width")?.takeIf { it.isJsonPrimitive }?.asInt
                val h = firstPage?.get("height")?.takeIf { it.isJsonPrimitive }?.asInt
                if (!error && w != null && h != null && w > 0 && h > 0) {
                    log("探测图片尺寸: 宽 $w · 高 $h · 第一页 · $source · ${ms}ms")
                    TestStep(strRes(R.string.network_test_dl_dim), strRes(R.string.network_test_dim_ok, w, h, source, ms), StepStatus.OK) to true
                } else {
                    log("探测图片尺寸失败: HTTP ${resp.code} · ${ms}ms · 未拿到第一页宽高")
                    TestStep(
                        strRes(R.string.network_test_dl_dim),
                        strRes(R.string.network_test_dim_no_page, resp.code, ms),
                        StepStatus.WARN,
                    ) to false
                }
            }
        } catch (e: Exception) {
            Timber.e(e)
            log("探测图片尺寸失败: ${e.javaClass.simpleName}: ${e.message}")
            TestStep(
                strRes(R.string.network_test_dl_dim),
                strRes(R.string.network_test_dim_failed, "${e.javaClass.simpleName}: ${e.message}"),
                StepStatus.WARN,
            ) to false
        } finally {
            activeClients.remove(client)
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
    }

    private fun imageHostDesc(): String = when (ImageHostManager.getMode()) {
        ImageHostManager.Mode.PIXIV -> strRes(R.string.network_test_route_official)
        ImageHostManager.Mode.PIXIV_CAT -> strRes(R.string.network_test_route_cat)
        ImageHostManager.Mode.PIXIV_RE -> strRes(R.string.network_test_route_re)
        ImageHostManager.Mode.PIXIV_NL -> strRes(R.string.network_test_route_nl)
        ImageHostManager.Mode.CUSTOM -> {
            val host = ImageHostManager.getCustomHost()
            if (host.isEmpty()) strRes(R.string.network_test_route_custom_none) else strRes(R.string.network_test_route_custom, maskProxyUrl(host))
        }
    }

    /** 当前图片代理的完整域名；官方模式返回 null。自定义反代从 URL 前缀抽取 host[:port]。 */
    private fun imageProxyDomain(): String? = when (ImageHostManager.getMode()) {
        ImageHostManager.Mode.PIXIV -> null
        ImageHostManager.Mode.PIXIV_CAT -> "i.pixiv.cat"
        ImageHostManager.Mode.PIXIV_RE -> "i.pixiv.re"
        ImageHostManager.Mode.PIXIV_NL -> "i.pixiv.nl"
        ImageHostManager.Mode.CUSTOM -> {
            val host = ImageHostManager.getCustomHost().trim()
            if (host.isEmpty()) {
                null
            } else {
                host.substringAfter("://").substringBefore('/').substringBefore('?')
            }
        }
    }

    /** 最多读 [max] 字节，返回 (数据, 是否被截断)。 */
    private fun readCapped(input: InputStream, max: Int): Pair<ByteArray, Boolean> {
        val out = ByteArrayOutputStream(minOf(max, 8192))
        val buf = ByteArray(8192)
        var truncated = false
        var total = 0
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            if (total + n > max) {
                out.write(buf, 0, max - total)
                truncated = true
                break
            }
            out.write(buf, 0, n)
            total += n
        }
        return out.toByteArray() to truncated
    }

    /** 根据 magic bytes 识别图片格式，不是图片返回 null。 */
    private fun imageMagic(b: ByteArray): String? {
        if (b.size >= 3 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() && b[2] == 0xFF.toByte()) return "JPEG"
        if (b.size >= 8 && b[0] == 0x89.toByte() && b[1] == 0x50.toByte() && b[2] == 0x4E.toByte() && b[3] == 0x47.toByte()) return "PNG"
        if (b.size >= 12 && b[0] == 'R'.code.toByte() && b[1] == 'I'.code.toByte() && b[2] == 'F'.code.toByte() && b[3] == 'F'.code.toByte()
            && b[8] == 'W'.code.toByte() && b[9] == 'E'.code.toByte() && b[10] == 'B'.code.toByte() && b[11] == 'P'.code.toByte()) return "WebP"
        if (b.size >= 4 && b[0] == 'G'.code.toByte() && b[1] == 'I'.code.toByte() && b[2] == 'F'.code.toByte() && b[3] == '8'.code.toByte()) return "GIF"
        return null
    }

    private fun formatBytes(n: Int): String = when {
        n >= 1024 * 1024 -> String.format("%.1fMB", n / 1024f / 1024f)
        n >= 1024 -> "${n / 1024}KB"
        else -> "${n}B"
    }

    // ---- 作品 API 探测（TODO #6b）----

    fun probeIllust(id: Long) {
        if (illustRunning.value == true) return
        illustRunning.value = true

        viewModelScope.launch(Dispatchers.IO) {
            val title = strRes(R.string.network_test_illust_title, id)
            val sub = strRes(R.string.network_test_illust_sub)
            val steps = mutableListOf<TestStep>()
            fun push(step: TestStep) {
                steps.add(step)
                illustReport.postValue(TargetReport(title, sub, TargetStatus.RUNNING, steps.toList()))
            }
            try {
                val t0 = System.currentTimeMillis()
                val resp = Client.appApi.getIllust(id)
                val ms = System.currentTimeMillis() - t0
                val il = resp.illust
                if (il == null) {
                    push(TestStep(strRes(R.string.network_test_illust_api_step), strRes(R.string.network_test_illust_no_illust, ms), StepStatus.FAIL))
                    illustReport.postValue(TargetReport(title, sub, TargetStatus.FAILED, steps.toList()))
                    return@launch
                }
                push(TestStep(strRes(R.string.network_test_illust_api_step), strRes(R.string.network_test_illust_ok, ms), StepStatus.OK))
                push(
                    TestStep(
                        strRes(R.string.network_test_illust_title_type),
                        "${il.title ?: "—"} · ${typeLabel(il.type)}",
                        StepStatus.INFO,
                    ),
                )
                val captionLen = il.caption?.replace(Regex("<[^>]*>"), "")?.trim()?.length ?: 0
                push(
                    TestStep(
                        strRes(R.string.network_test_illust_caption),
                        if (captionLen > 0) strRes(R.string.network_test_illust_caption_yes, captionLen) else strRes(R.string.network_test_illust_caption_no),
                        if (captionLen > 0) StepStatus.OK else StepStatus.INFO,
                    ),
                )
                push(TestStep(strRes(R.string.network_test_illust_pages), strRes(R.string.network_test_illust_pages_n, il.page_count), StepStatus.INFO))
                push(
                    TestStep(
                        strRes(R.string.network_test_illust_resolution),
                        strRes(R.string.network_test_illust_resolution_n, il.width, il.height),
                        StepStatus.INFO,
                    ),
                )
                val orig = il.meta_single_page?.original_image_url
                    ?: il.meta_pages?.firstOrNull()?.image_urls?.original
                    ?: il.image_urls?.original
                val ext = orig?.substringAfterLast('.', "")?.takeIf { it.isNotBlank() }?.uppercase()
                    ?: strRes(R.string.network_test_unknown)
                push(
                    TestStep(
                        strRes(R.string.network_test_illust_format),
                        if (orig != null) strRes(R.string.network_test_illust_format_yes, ext)
                        else strRes(R.string.network_test_illust_format_no, ext),
                        if (orig != null) StepStatus.OK else StepStatus.WARN,
                    ),
                )
                val flags = buildList {
                    if (il.illust_ai_type == 2) add(strRes(R.string.network_test_illust_flag_ai))
                    if ((il.x_restrict ?: 0) > 0) add(strRes(R.string.network_test_illust_flag_r18))
                    if (il.is_muted == true) add(strRes(R.string.network_test_illust_flag_muted))
                }
                if (flags.isNotEmpty()) push(TestStep(strRes(R.string.network_test_illust_flags), flags.joinToString(" · "), StepStatus.INFO))
                illustReport.postValue(TargetReport(title, sub, TargetStatus.OK, steps.toList()))
            } catch (e: Exception) {
                // Client.appApi.getIllust 是 suspend，onCleared 取消时会真的走到这里——
                // 必须放行 CancellationException，不能 postValue「API 请求失败: CancellationException」。
                if (e is CancellationException) throw e
                push(
                    TestStep(
                        strRes(R.string.network_test_illust_api_failed),
                        e.javaClass.simpleName + (e.message?.let { ": $it" } ?: ""),
                        StepStatus.FAIL,
                    ),
                )
                illustReport.postValue(TargetReport(title, sub, TargetStatus.FAILED, steps.toList()))
            } finally {
                illustRunning.postValue(false)
            }
        }
    }

    private fun typeLabel(type: String?): String = when (type) {
        "illust" -> strRes(R.string.network_test_type_illust)
        "manga" -> strRes(R.string.network_test_type_manga)
        "ugoira" -> strRes(R.string.network_test_type_ugoira)
        else -> type ?: strRes(R.string.network_test_unknown)
    }

    // ---- 状态发布 ----

    private fun addTarget(report: TargetReport): Int {
        work.add(report)
        publish()
        return work.size - 1
    }

    private fun addStep(idx: Int, step: TestStep) {
        work[idx] = work[idx].copy(steps = work[idx].steps + step)
        publish()
    }

    private fun updateStep(idx: Int, stepIdx: Int, detail: String?, status: StepStatus) {
        val report = work[idx]
        if (stepIdx !in report.steps.indices) return
        val steps = report.steps.toMutableList()
        steps[stepIdx] = steps[stepIdx].copy(detail = detail, status = status)
        work[idx] = report.copy(steps = steps)
        publish()
    }

    private fun setStatus(idx: Int, status: TargetStatus) {
        work[idx] = work[idx].copy(status = status)
        publish()
    }

    /** 图片目标跳过连通性（http 反代）时，把该卡状态回写为图片下载探测的结果。 */
    private fun syncImageTargetCard(status: TargetStatus) {
        val title = imageTargetTitle ?: return
        val imageIdx = work.indexOfFirst { it.title == title }
        if (imageIdx >= 0) {
            // 下载成功 → 卡仍显示「跳过」（连通性没测）；失败 / 降级 / 延迟 → 清掉「跳过」
            // 覆盖，用标准文案（失败时总览随后覆盖为红底「图片无法加载」）。
            val override = if (status == TargetStatus.OK) strRes(R.string.network_test_pill_skipped) else null
            work[imageIdx] = work[imageIdx].copy(status = status, statusPillOverride = override)
            publish()
        }
    }

    /** 把握手 avg/max 耗时标到卡片副标题，方便一眼看到每个端点的延迟。 */
    private fun appendLatencyToSubtitle(idx: Int, avgMs: Int, maxMs: Int) {
        work[idx] = work[idx].copy(subtitle = work[idx].subtitle + " · avg ${avgMs}ms · max ${maxMs}ms")
        publish()
    }

    private fun publish() {
        targets.postValue(work.toList())
    }

    private fun log(line: String) {
        rawBuilder.append(line).append('\n')
        rawLog.postValue(rawBuilder.toString())
    }

    private fun onOff(v: Boolean) = if (v) "开" else "关"

    /** 取字符串资源：本页用户可见文案已全部抽到 res，多语言时逐条翻译即可。 */
    private fun strRes(id: Int, vararg args: Any?): String = Shaft.getContext().getString(id, *args)

    private fun buildPollutionMessage(domains: List<String>, doh: Boolean, direct: Boolean): String {
        val head = strRes(R.string.network_test_pollution_dialog_head) +
            domains.joinToString("\n") { strRes(R.string.network_test_pollution_dialog_item, it) }
        val tail = if (doh && direct) {
            strRes(R.string.network_test_pollution_dialog_tail_both)
        } else if (direct) {
            strRes(R.string.network_test_pollution_dialog_tail_direct)
        } else {
            strRes(R.string.network_test_pollution_dialog_tail_other)
        }
        return head + tail
    }

    companion object {
        /** app 主 API 域名：它失败即总览判「网络不可用」，且否决「网络勉强可用」。 */
        private const val APP_API_HOST = "app-api.pixiv.net"

        /** 转发路径探测用的原始 pixiv URL：交给 [AppApiProxyInterceptor.rewrite] 改写成代理地址。 */
        private const val APP_API_PROBE_URL = "https://app-api.pixiv.net/v1/illust/ranking?mode=day"
        private const val OAUTH_PROBE_URL = "https://oauth.secure.pixiv.net/auth/token"

        // 代理 fake-ip 模式返回的占位段：不可路由，命中即说明 DNS 被代理接管，测连通无意义。
        private val FAKE_IP_CIDRS = listOf(
            "198.18.0.0/15",   // RFC 2544 基准测试段（Clash / Surge / sing-box 默认 fake-ip 段）
            "192.0.2.0/24",    // RFC 5737 TEST-NET-1
            "198.51.100.0/24", // RFC 5737 TEST-NET-2
            "203.0.113.0/24",  // RFC 5737 TEST-NET-3
        )

        private fun isFakeIp(ip: String): Boolean = FAKE_IP_CIDRS.any { isIpInCidr(ip, it) }

        /** 是否官方 Pixiv 域名：只有这些域名在无代理时不该出现公网 IPv6。 */
        private fun isPixivDomain(host: String): Boolean =
            host == APP_API_HOST || host == "www.pixiv.net"

        /**
         * 是否公网 IPv6。
         *
         * 用于「官方 Pixiv 域名出现 IPv6 即视为 DNS 污染」的判断；先排除回环 / 链路本地 /
         * 站点本地 / 组播 / ULA / 文档段 / NAT64 等非公网地址，避免 fake-ip 给的假 IPv6
         * 被误判成污染。
         */
        private fun isPublicIpv6(addr: InetAddress): Boolean {
            if (addr !is Inet6Address) return false
            if (addr.isAnyLocalAddress || addr.isLoopbackAddress ||
                addr.isLinkLocalAddress || addr.isSiteLocalAddress || addr.isMulticastAddress
            ) {
                return false
            }
            val host = addr.hostAddress?.lowercase() ?: return false
            return !host.startsWith("fc") && !host.startsWith("fd") &&
                !host.startsWith("64:ff9b:") && !host.startsWith("2001:db8:") &&
                !host.startsWith("::ffff:")
        }

        /** 一次解析系统 DNS 结果，统一拆出后面各步要用的字段。 */
        private fun analyzeSysDns(addrs: List<InetAddress>): SysDnsResult {
            val ipv4 = addrs.filterIsInstance<Inet4Address>()
            val ipv6 = addrs.filterIsInstance<Inet6Address>()
            return SysDnsResult(
                all = addrs,
                ipv4 = ipv4,
                ipv6 = ipv6,
                fakeIps = ipv4.mapNotNull { it.hostAddress }.filter { isFakeIp(it) },
                publicIpv6 = ipv6.filter { isPublicIpv6(it) },
            )
        }

        /** 握手 avg 超过该阈值判定为「高延迟」。 */
        private const val HIGH_LATENCY_MS = 500

        /** 握手 max 超过该阈值判定为「超高延迟」（红底）。 */
        private const val EXTREME_LATENCY_MS = 1000

        // 图片下载的 TTFB 单独一套阈值，不复用上面的握手阈值：下载客户端连接池为 0，
        // 每次都新建连接，TTFB = TCP+TLS 握手 + 请求往返 + 服务端处理，天然比握手采样值大
        // 一个量级。实测健康线路（本机直出）到 i.pximg.net 的 TTFB 约 400ms，
        // 套 500/1000ms 会让正常用户满屏「高延迟」。取约 2.5 倍余量。
        /** 图片下载 TTFB 超过该阈值判定为「高延迟」。 */
        private const val IMAGE_TTFB_HIGH_MS = 1000

        /** 图片下载 TTFB 超过该阈值判定为「超高延迟」（红底）。 */
        private const val IMAGE_TTFB_EXTREME_MS = 2000

        /** 图片下载吞吐低于该值（KB/s）判定为「下载缓慢」。样本只有 ~39KB 且连接是新建的，
         *  TCP 慢启动会明显压低测得吞吐，阈值取得足够低——只抓真正糟糕的链路，不抓 RTT 抖动。 */
        private const val SLOW_THROUGHPUT_KBS = 50

        /** 小于该体积的响应不做吞吐判定：头像仅 ~10KB，测出来的 KB/s 几乎全是 RTT 噪声。 */
        private const val MIN_SPEED_SAMPLE_BYTES = 20 * 1024

        /**
         * 图片下载探测用的内置样例作品（仓库既有数据，SFW、长期稳定）与其兜底地址。
         *
         * 注意：这是硬编码的第三方内容依赖，若该作品将来被删除 / R-18 化 / 改版式，
         * 网页探测（/ajax/illust/{id}）与尺寸探测（/ajax/illust/{id}/pages）会拿不到
         * 实时数据，退化到下面的内置兜底地址 —— 探测仍能跑，但「图片代理路由」卡片
         * 的样本代表性下降。更换样例时需同步三处：SAMPLE_ILLUST_ID、
         * FALLBACK_ILLUST_URL、FALLBACK_AVATAR_URL。
         */
        private const val SAMPLE_ILLUST_ID = 73949833L
        private const val MAX_IMAGE_DOWNLOAD_BYTES = 1024 * 1024
        private const val FALLBACK_ILLUST_URL =
            "https://i.pximg.net/c/540x540_70/img-master/img/2019/03/30/16/33/50/73949833_p0_master1200.jpg"
        private const val FALLBACK_AVATAR_URL =
            "https://i.pximg.net/user-profile/img/2017/04/27/10/00/38/12474975_a0a699ea19f387df0f98bc5a9b7d26d3_170.png"

        // app-api.pixiv.net 在 Cloudflare CDN 后，这批是 Cloudflare 公布的 IPv4 段。
        private val PIXIV_CIDRS = listOf(
            "173.245.48.0/20", "103.21.244.0/22", "103.22.200.0/22", "103.31.4.0/22",
            "141.101.64.0/18", "108.162.192.0/18", "190.93.240.0/20", "188.114.96.0/20",
            "197.234.240.0/22", "198.41.128.0/17", "162.158.0.0/15", "104.16.0.0/13",
            "104.24.0.0/14", "172.64.0.0/13", "131.0.72.0/22",
        )

        // i.pximg.net 仍在 Pixiv Japan 自有基础设施（210.140.x）。
        private val PXIMG_CIDRS = listOf(
            "210.140.92.0/24", "210.140.131.0/24", "210.140.139.0/24", "210.140.140.0/24",
            "210.140.141.0/24", "210.140.142.0/24", "210.140.143.0/24", "210.140.144.0/24",
            "210.140.145.0/24", "210.140.146.0/24", "210.140.147.0/24", "210.140.148.0/24",
            "210.140.149.0/24", "210.140.150.0/24",
        )

        private fun isIpInCidr(ip: String, cidr: String): Boolean {
            return try {
                val parts = cidr.split("/")
                if (parts.size != 2) return false
                val prefix = parts[1].toIntOrNull() ?: return false
                val ipInt = ipToInt(ip) ?: return false
                val netInt = ipToInt(parts[0]) ?: return false
                val mask = if (prefix == 0) 0 else (-1 shl (32 - prefix))
                (ipInt and mask) == (netInt and mask)
            } catch (e: Exception) {
                false
            }
        }

        private fun ipToInt(ip: String): Int? {
            val octets = ip.split(".")
            if (octets.size != 4) return null
            var result = 0
            for (part in octets) {
                val v = part.toIntOrNull() ?: return null
                if (v !in 0..255) return null
                result = (result shl 8) or v
            }
            return result
        }
    }
}
