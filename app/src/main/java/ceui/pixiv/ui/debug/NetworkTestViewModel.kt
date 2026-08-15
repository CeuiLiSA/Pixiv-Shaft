package ceui.pixiv.ui.debug

import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.lisa.http.CronetInterceptor
import ceui.lisa.http.HttpDns
import ceui.lisa.http.RubySSLSocketFactory
import ceui.lisa.http.TrustAllCertManager
import ceui.loxia.Client
import ceui.loxia.HeaderInterceptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import okhttp3.ConnectionPool
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import timber.log.Timber
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/** 单条步骤的语义状态，决定圆点 / pill 的颜色（在 Fragment 里按状态染 v3 颜色）。 */
enum class StepStatus { INFO, OK, WARN, FAIL, RUNNING }

/** 目标卡里的一行：label + 可选等宽 detail（可多行）。 */
data class TestStep(
    val label: String,
    val detail: String? = null,
    val status: StepStatus = StepStatus.INFO,
)

/**
 * 单个目标（域名）的整体判定，决定卡片右上角 pill。
 * SKIPPED：测试被取消（如代理 fake-ip 下解析结果不可路由，测了无意义），不算失败/污染。
 */
enum class TargetStatus { RUNNING, OK, DEGRADED, POLLUTED, FAILED, SKIPPED }

data class TargetReport(
    val title: String,
    val subtitle: String,
    val status: TargetStatus = TargetStatus.RUNNING,
    val steps: List<TestStep> = emptyList(),
)

/** 全局总览判定，决定顶部总览卡。 */
enum class OverallStatus { CLEAN, DEGRADED, POLLUTED }

/** 一次性弹窗事件的载荷：标题资源 + 已组好的正文（污染 / fake-ip 共用一条通道）。 */
data class NetworkAlert(val titleRes: Int, val message: String)

/**
 * 网络测试页的全部状态与测试逻辑（按项目约定，网络/异步状态归 ViewModel，
 * Fragment 只渲染）。承载 [NetworkTestFragment]。
 *
 * 该页用于「无代理环境下」诊断 Pixiv 连通性；代理（尤其 fake-ip 模式）下测试无参考价值，
 * 检测到 fake-ip 即取消该目标并提示用户改 DNS 模式。
 *
 * 单次诊断对 app-api.pixiv.net / i.pximg.net / pixshaft.com 依次做：
 *   1. 系统 DNS 解析 + CIDR 比对（检测本机 DNS 污染 / fake-ip；命中弹 [pollutionAlert]）
 *   2. DoH / 直连开启时，额外展示并校验应用内 [HttpDns] 实际解析路径（污染时改走此路）
 *   3. TCP 443 连通性（延迟过低标注无参考性）
 *   4. 直连开启时额外做 ICMP/echo 可达性（代理下无意义，故仅直连）
 *   5. HTTPS 握手：持续 5s 多次采样，给 min/avg/max/抖动 + TLS/协议信息
 *
 * 关键约定（来自 PR #895 的方向）：第 5 步**按目标各自复刻线上真实连接路径**，否则测出来的
 * 「通/不通」对用户没有参考价值——见 [buildHandshakeClient]：
 *   · app-api / pixshaft：H2+H1；直连开启时经 [CronetInterceptor]（QUIC，绕 SNI 阻断）
 *   · i.pximg.net：直连开启时无 SNI + 信任所有证书 + 强制 HTTP/1.1（图片服务器按 IP 路由）
 *
 * 另含独立的「作品 API 探测」：填插画/漫画 ID，测 /v1/illust/detail 响应时间与内容。
 */
class NetworkTestViewModel : ViewModel() {

    val running = MutableLiveData(false)
    val illustRunning = MutableLiveData(false)
    val targets = MutableLiveData<List<TargetReport>>(emptyList())
    val overall = MutableLiveData<OverallStatus?>(null)
    val rawLog = MutableLiveData("")
    val illustReport = MutableLiveData<TargetReport?>(null)

    val dohEnabled: Boolean get() = Shaft.sSettings?.isUseSecureDns == true
    val directConnect: Boolean get() = Shaft.sSettings?.isDirectConnect == true

    /** 一次性事件：检测到 DNS 污染 / fake-ip 时弹窗提醒（PR #894 的核心诉求）。 */
    private val _pollutionAlert = MutableSharedFlow<NetworkAlert>(extraBufferCapacity = 1)
    val pollutionAlert = _pollutionAlert.asSharedFlow()

    private val work = mutableListOf<TargetReport>()
    private val rawBuilder = StringBuilder()

    /** 决定 [buildHandshakeClient] 用哪套客户端复刻线上连接。 */
    private enum class TargetKind { APP_API, IMAGE, PIXSHAFT }

    private data class TargetConfig(
        val host: String,
        val subtitle: String,
        val cidrs: List<String>?,
        val kind: TargetKind,
    )

    fun runTests() {
        if (running.value == true) return
        running.value = true
        work.clear()
        targets.value = emptyList()
        overall.value = null
        rawBuilder.setLength(0)
        rawLog.value = ""

        val doh = dohEnabled
        val direct = directConnect

        viewModelScope.launch(Dispatchers.IO) {
            log("环境: 安全 DNS(DoH) ${onOff(doh)} · 直连 ${onOff(direct)}")
            log("")

            val configs = listOf(
                TargetConfig("app-api.pixiv.net", "pixiv API · Cloudflare CDN", PIXIV_CIDRS, TargetKind.APP_API),
                TargetConfig("i.pximg.net", "图片服务器 · Pixiv Japan", PXIMG_CIDRS, TargetKind.IMAGE),
                TargetConfig("pixshaft.com", "Shaft 云服务 · 浏览记录同步", null, TargetKind.PIXSHAFT),
            )
            val polluted = mutableListOf<String>()
            for (cfg in configs) {
                val idx = addTarget(TargetReport(cfg.host, cfg.subtitle))
                if (testTarget(idx, cfg, doh, direct)) polluted.add(cfg.host)
            }

            val anyFailed = work.any { it.status == TargetStatus.FAILED }
            val anyDegraded = work.any { it.status == TargetStatus.DEGRADED }
            // SKIPPED（fake-ip 取消）也算「没测全」，别给出「网络通畅」的假象。
            val anySkipped = work.any { it.status == TargetStatus.SKIPPED }
            val ov = when {
                polluted.isNotEmpty() -> OverallStatus.POLLUTED
                anyFailed || anyDegraded || anySkipped -> OverallStatus.DEGRADED
                else -> OverallStatus.CLEAN
            }
            overall.postValue(ov)
            if (polluted.isNotEmpty()) {
                _pollutionAlert.emit(
                    NetworkAlert(R.string.network_test_pollution_dialog_title, buildPollutionMessage(polluted, doh, direct)),
                )
            }
            running.postValue(false)
        }
    }

    private fun buildFakeIpMessage(host: String, fakeIps: List<String>): String =
        "域名 $host 返回了保留地址（${fakeIps.joinToString()}），无法路由到真实服务器，可能原因：\n" +
            "• 当前网络启用了 VPN / 代理，且 DNS 处于 fake-ip 模式\n" +
            "• 请将代理工具的 DNS 模式改为 redir-host 或 normal，否则该测试无意义"

    /** @return 该目标本机 DNS 是否被判定为污染。 */
    private fun testTarget(idx: Int, cfg: TargetConfig, doh: Boolean, direct: Boolean): Boolean {
        log("========== ${cfg.host} ==========")

        val sysAddrs = try {
            InetAddress.getAllByName(cfg.host).toList()
        } catch (e: UnknownHostException) {
            addStep(idx, TestStep("系统 DNS 解析", "解析失败: ${e.message}", StepStatus.FAIL))
            log("系统 DNS 解析失败: ${e.message}")
            setStatus(idx, TargetStatus.FAILED)
            return false
        }
        val ipv4 = sysAddrs.filterIsInstance<Inet4Address>()
        // fake-ip 检测：代理接管 DNS 时返回的保留地址不可路由，测了无意义 —— 取消该目标（非失败）。
        val fakeIps = ipv4.mapNotNull { it.hostAddress }.filter { isFakeIp(it) }
        if (fakeIps.isNotEmpty()) {
            addStep(idx, TestStep("系统 DNS 解析", "返回保留地址（疑似代理 fake-ip）: ${fakeIps.joinToString()}", StepStatus.WARN))
            log("检测到 fake-ip，取消该目标: ${fakeIps.joinToString()}")
            setStatus(idx, TargetStatus.SKIPPED)
            viewModelScope.launch {
                _pollutionAlert.emit(NetworkAlert(R.string.network_test_fakeip_dialog_title, buildFakeIpMessage(cfg.host, fakeIps)))
            }
            return false
        }
        log("DNS: " + sysAddrs.joinToString(", ") { it.hostAddress ?: "?" })

        var polluted = false
        if (cfg.cidrs != null) {
            val sb = StringBuilder()
            var clean = 0
            for (a in ipv4) {
                val ip = a.hostAddress ?: continue
                val hit = cfg.cidrs.firstOrNull { isIpInCidr(ip, it) }
                if (hit != null) {
                    clean++
                    sb.append("✓ $ip ∈ $hit\n")
                } else {
                    sb.append("✗ $ip 不在任何已知段\n")
                }
            }
            sysAddrs.filter { it !is Inet4Address }.forEach { sb.append("· ${it.hostAddress} (IPv6, 跳过)\n") }
            polluted = ipv4.isNotEmpty() && clean == 0
            val st = when {
                ipv4.isEmpty() -> StepStatus.WARN
                polluted -> StepStatus.FAIL
                clean < ipv4.size -> StepStatus.WARN
                else -> StepStatus.OK
            }
            addStep(idx, TestStep("系统 DNS 解析 · ${sysAddrs.size} 条", sb.toString().trimEnd(), st))
        } else {
            addStep(
                idx,
                TestStep(
                    "系统 DNS 解析 · ${sysAddrs.size} 条",
                    sysAddrs.joinToString("\n") { "· ${it.hostAddress}" },
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
                        sb.append("✓ $ip ∈ $hit\n")
                    } else {
                        sb.append("✗ $ip 不在任何已知段\n")
                    }
                }
                appAddrs.filter { it !is Inet4Address }.forEach { sb.append("· ${it.hostAddress} (IPv6, 跳过)\n") }

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
                        "应用内解析 · HttpDns(DoH/直连) · ${appAddrs.size} 条",
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
                addStep(idx, TestStep("应用内解析 · HttpDns", "失败: ${e.message}", StepStatus.WARN))
            }
        }

        // 选用于后续连通性 / 握手的目标 IP：优先干净的系统解析，污染时退到应用内解析路径。
        val cleanV4 = if (cfg.cidrs != null) {
            ipv4.filter { a -> cfg.cidrs.any { isIpInCidr(a.hostAddress ?: "", it) } }
        } else {
            ipv4
        }
        val targetIp: Inet4Address? = cleanV4.firstOrNull() ?: appIp
        if (targetIp == null) {
            val detail = if (polluted) {
                "DNS 解析不可信，且无可用绕过路径"
            } else {
                "无可用 IPv4 地址"
            }
            addStep(idx, TestStep("跳过连通性 / 握手", detail, StepStatus.WARN))
            log("跳过后续: $detail")
            setStatus(idx, if (polluted) TargetStatus.POLLUTED else TargetStatus.FAILED)
            return polluted
        }
        if (polluted && cleanV4.isEmpty() && targetIp === appIp) {
            addStep(
                idx,
                TestStep(
                    "改走应用内解析路径",
                    "本机 DNS 被污染，以下测试经 ${targetIp.hostAddress}(DoH/直连)",
                    StepStatus.WARN,
                ),
            )
        }

        tcpPing(idx, targetIp.hostAddress ?: "", 443)
        if (direct) icmpPing(idx, targetIp)
        val hsOk = httpsHandshakeSampled(idx, cfg, targetIp, direct)

        val status = when {
            polluted -> TargetStatus.POLLUTED
            !hsOk -> TargetStatus.FAILED
            cfg.cidrs != null && cleanV4.size < ipv4.size -> TargetStatus.DEGRADED
            else -> TargetStatus.OK
        }
        setStatus(idx, status)
        log("")
        return polluted
    }

    private fun tcpPing(idx: Int, ip: String, port: Int) {
        try {
            val t0 = System.currentTimeMillis()
            Socket().use { it.connect(InetSocketAddress(ip, port), 3000) }
            val ms = System.currentTimeMillis() - t0
            if (ms <= 10) {
                addStep(idx, TestStep("TCP $port 连通性", "${ms}ms · 过低无参考性，以握手耗时为准", StepStatus.WARN))
            } else {
                addStep(idx, TestStep("TCP $port 连通性", "可达 · ${ms}ms", StepStatus.OK))
            }
            log("TCP $port: ${ms}ms")
        } catch (e: Exception) {
            addStep(idx, TestStep("TCP $port 连通性", "不可达: ${e.message}", StepStatus.FAIL))
            log("TCP $port 不可达: ${e.message}")
        }
    }

    /** 仅直连模式做 —— 代理下测 ICMP 无意义（ICMP 会透过代理）。 */
    private fun icmpPing(idx: Int, ip: InetAddress) {
        val samples = mutableListOf<Long>()
        repeat(3) {
            try {
                val t0 = System.currentTimeMillis()
                if (ip.isReachable(2000)) samples.add(System.currentTimeMillis() - t0)
            } catch (_: Exception) {
            }
        }
        if (samples.isNotEmpty()) {
            val avg = samples.average().toInt()
            addStep(idx, TestStep("ICMP/echo Ping · 直连", "${samples.size}/3 可达 · 平均 ${avg}ms", StepStatus.OK))
            log("ICMP: ${samples.size}/3 avg ${avg}ms")
        } else {
            addStep(idx, TestStep("ICMP/echo Ping · 直连", "0/3 可达（部分网络禁用 ICMP，属正常）", StepStatus.WARN))
            log("ICMP: 0/3")
        }
    }

    /**
     * 为目标构建与线上同源的 OkHttpClient —— 测什么路径就用 app 真实连这个域名时的那套
     * （见 [Client] 的 createAPPAPI / createPixshaftService 与 Shaft 图片 client）：
     *   · APP_API / PIXSHAFT：H2+H1；直连开启时挂 [CronetInterceptor]（请求转 QUIC，绕 SNI 阻断）。
     *   · IMAGE：直连开启时无 SNI（[RubySSLSocketFactory]）+ 信任所有证书 + 关主机名校验 + 强制 HTTP/1.1。
     * 连接池 0 空闲 → 每次调用都重新握手；[pinnedDns] 把域名钉到本次选定的 IP（Cronet 路径除外，
     * 其走自身 host-resolver 规则，固定到 Cloudflare IP）。
     */
    private fun buildHandshakeClient(cfg: TargetConfig, ip: InetAddress, direct: Boolean): OkHttpClient {
        val pinnedDns = object : Dns {
            override fun lookup(hostname: String): List<InetAddress> = listOf(ip)
        }
        val builder = OkHttpClient.Builder()
            .dns(pinnedDns)
            .connectionPool(ConnectionPool(0, 1, TimeUnit.SECONDS))
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .writeTimeout(5, TimeUnit.SECONDS)
        when (cfg.kind) {
            TargetKind.APP_API -> {
                builder.protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
                builder.addInterceptor(HeaderInterceptor())
                if (direct) addCronet(builder)
            }
            TargetKind.IMAGE -> {
                if (direct) {
                    try {
                        builder.sslSocketFactory(RubySSLSocketFactory(), TrustAllCertManager())
                        builder.hostnameVerifier { _, _ -> true }
                    } catch (e: Exception) {
                        Timber.e(e, "image no-SNI SSL init error")
                    }
                    builder.protocols(listOf(Protocol.HTTP_1_1))
                } else {
                    builder.protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
                }
            }
            TargetKind.PIXSHAFT -> {
                builder.protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
                if (direct) addCronet(builder)
            }
        }
        return builder.build()
    }

    private fun addCronet(builder: OkHttpClient.Builder) {
        builder.addInterceptor(CronetInterceptor(CronetInterceptor.getEngine(Shaft.getContext())))
    }

    /** 该目标本次握手实际走的路径，标在步骤 label 上让用户看清测的是哪条链路。 */
    private fun handshakePathDesc(cfg: TargetConfig, direct: Boolean): String = when (cfg.kind) {
        TargetKind.IMAGE -> if (direct) "无 SNI · HTTP/1.1" else "标准 TLS"
        else -> if (direct) "直连 Cronet/QUIC" else "标准 TLS"
    }

    /**
     * HTTPS 握手：用 [buildHandshakeClient] 复刻该目标的真实连接，持续 5s 反复建连
     * （连接池 0 空闲，每次都重新握手），实时刷新 min/avg/max/抖动。
     */
    private fun httpsHandshakeSampled(idx: Int, cfg: TargetConfig, ip: InetAddress, direct: Boolean): Boolean {
        val client = buildHandshakeClient(cfg, ip, direct)
        // SNI 由 socket 工厂控制，与 URL 无关；统一用域名即可（无 SNI 路径仍会被 RubySSLSocketFactory 抹掉）。
        val request = Request.Builder().url("https://${cfg.host}/").head().build()

        val samples = mutableListOf<Long>()
        var fail = 0
        var tls: String? = null
        var cipher: String? = null
        var proto: String? = null
        var firstErr: String? = null

        val pathDesc = handshakePathDesc(cfg, direct)
        val stepIdx = work[idx].steps.size
        addStep(idx, TestStep("HTTPS 握手 · 持续 5s 采样 · $pathDesc", "采样中…", StepStatus.RUNNING))

        try {
            val deadline = System.currentTimeMillis() + 5000
            var n = 0
            while (System.currentTimeMillis() < deadline && n < 15) {
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
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }

        val ok = samples.isNotEmpty()
        val st = when {
            !ok -> StepStatus.FAIL
            fail > 0 -> StepStatus.WARN
            else -> StepStatus.OK
        }
        updateStep(idx, stepIdx, handshakeDetail(samples, fail, tls, cipher, proto, firstErr), st)
        log(
            "HTTPS($pathDesc): 成功 ${samples.size} / 失败 $fail" +
                if (ok) " · min ${samples.min()} avg ${samples.average().toInt()} max ${samples.max()} ms · ${tls ?: proto}"
                else " · ${firstErr ?: ""}",
        )
        return ok
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
            sb.append("成功 ${samples.size}")
            if (fail > 0) sb.append(" · 失败 $fail")
            sb.append("\nmin ${min}ms · avg ${avg}ms · max ${max}ms · 抖动 ${max - min}ms")
            if (tls != null) {
                sb.append("\n$tls")
                cipher?.let { sb.append(" · $it") }
            } else {
                // Cronet/QUIC 路径短路了 OkHttp 的 TLS 层，没有握手对象，只能报协商出的协议。
                proto?.let { sb.append("\n协议 $it（经直连，无 TLS 握手详情）") }
            }
        } else {
            sb.append("全部失败（$fail 次）")
            firstErr?.let { sb.append("\n$it") }
            sb.append("\n常见原因: 连接被重置 / 证书不受信 / TLS 版本不匹配")
        }
        return sb.toString()
    }

    // ---- 作品 API 探测（TODO #6b）----

    fun probeIllust(id: Long) {
        if (illustRunning.value == true) return
        illustRunning.value = true

        viewModelScope.launch(Dispatchers.IO) {
            val title = "作品 #$id"
            val sub = "GET /v1/illust/detail"
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
                    push(TestStep("API 响应", "${ms}ms · 返回体无 illust 字段", StepStatus.FAIL))
                    illustReport.postValue(TargetReport(title, sub, TargetStatus.FAILED, steps.toList()))
                    return@launch
                }
                push(TestStep("API 响应", "${ms}ms · HTTP 200", StepStatus.OK))
                push(TestStep("标题 / 类型", "${il.title ?: "—"} · ${typeLabel(il.type)}", StepStatus.INFO))
                val captionLen = il.caption?.replace(Regex("<[^>]*>"), "")?.trim()?.length ?: 0
                push(
                    TestStep(
                        "简介",
                        if (captionLen > 0) "有 · $captionLen 字" else "无",
                        if (captionLen > 0) StepStatus.OK else StepStatus.INFO,
                    ),
                )
                push(TestStep("页数", "${il.page_count} P", StepStatus.INFO))
                push(TestStep("首图分辨率", "${il.width} × ${il.height}", StepStatus.INFO))
                val orig = il.meta_single_page?.original_image_url
                    ?: il.meta_pages?.firstOrNull()?.image_urls?.original
                    ?: il.image_urls?.original
                val ext = orig?.substringAfterLast('.', "")?.takeIf { it.isNotBlank() }?.uppercase() ?: "未知"
                push(
                    TestStep(
                        "图片格式",
                        if (orig != null) "$ext · 原图地址已返回" else "$ext · 无原图地址",
                        if (orig != null) StepStatus.OK else StepStatus.WARN,
                    ),
                )
                val flags = buildList {
                    if (il.illust_ai_type == 2) add("AI 生成")
                    if ((il.x_restrict ?: 0) > 0) add("R-18")
                    if (il.is_muted == true) add("已屏蔽")
                }
                if (flags.isNotEmpty()) push(TestStep("标记", flags.joinToString(" · "), StepStatus.INFO))
                illustReport.postValue(TargetReport(title, sub, TargetStatus.OK, steps.toList()))
            } catch (e: Exception) {
                push(TestStep("API 请求失败", e.javaClass.simpleName + (e.message?.let { ": $it" } ?: ""), StepStatus.FAIL))
                illustReport.postValue(TargetReport(title, sub, TargetStatus.FAILED, steps.toList()))
            } finally {
                illustRunning.postValue(false)
            }
        }
    }

    private fun typeLabel(type: String?): String = when (type) {
        "illust" -> "插画"
        "manga" -> "漫画"
        "ugoira" -> "动图"
        else -> type ?: "未知"
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

    private fun publish() {
        targets.postValue(work.toList())
    }

    private fun log(line: String) {
        rawBuilder.append(line).append('\n')
        rawLog.postValue(rawBuilder.toString())
    }

    private fun onOff(v: Boolean) = if (v) "开" else "关"

    private fun buildPollutionMessage(domains: List<String>, doh: Boolean, direct: Boolean): String {
        val head = "以下是疑似被DNS污染的域名\n（域名解析出的IP不在已知的正确IP列表中）:\n" +
            domains.joinToString("\n") { "· $it" }
        val tail = if (doh && direct) {
            "\n\n当前已同时开启直连模式和「安全 DNS（DoH）」，已尝试绕过污染，具体效果请以实际为准。"
        } else if (direct) {
            "\n\n当前已开启直连模式，但DNS污染仍在，建议同时开启「安全 DNS（DoH）」。"
        } else {
            "\n\n建议在「设置 → 网络」同时开启直连模式和「安全 DNS（DoH）」来绕过污染。"
        }
        return head + tail
    }

    companion object {
        // 代理 fake-ip 模式返回的占位段：不可路由，命中即说明 DNS 被代理接管，测连通无意义。
        private val FAKE_IP_CIDRS = listOf(
            "198.18.0.0/15",   // RFC 2544 基准测试段（Clash / Surge / sing-box 默认 fake-ip 段）
            "192.0.2.0/24",    // RFC 5737 TEST-NET-1
            "198.51.100.0/24", // RFC 5737 TEST-NET-2
            "203.0.113.0/24",  // RFC 5737 TEST-NET-3
        )

        private fun isFakeIp(ip: String): Boolean = FAKE_IP_CIDRS.any { isIpInCidr(ip, it) }

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
