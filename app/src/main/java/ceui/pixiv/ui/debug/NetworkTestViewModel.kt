package ceui.pixiv.ui.debug

import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ceui.lisa.R
import ceui.lisa.activities.Shaft
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
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/** 单条步骤的语义状态，决定圆点 / pill 的颜色（在 Fragment 里按状态染 v3 颜色）。 */
enum class StepStatus { INFO, OK, WARN, FAIL, RUNNING, HIGH_LATENCY, EXTREME_LATENCY }

/** 目标卡里的一行：label + 可选等宽 detail（可多行）。 */
data class TestStep(
    val label: String,
    val detail: String? = null,
    val status: StepStatus = StepStatus.INFO,
)

/**
 * 单个目标（域名）的整体判定，决定卡片右上角 pill。
 */
enum class TargetStatus { RUNNING, OK, HIGH_LATENCY, EXTREME_LATENCY, DEGRADED, POLLUTED, FAILED }

data class TargetReport(
    val title: String,
    val subtitle: String,
    val status: TargetStatus = TargetStatus.RUNNING,
    val steps: List<TestStep> = emptyList(),
    /** 卡片状态 pill 右侧可选的并列提示（如图片尺寸探测失败），黄底。 */
    val extraPill: String? = null,
)

/** 全局总览判定，决定顶部总览卡。 */
enum class OverallStatus { CLEAN, HIGH_LATENCY, EXTREME_LATENCY, DEGRADED, POLLUTED }

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

    val dohEnabled: Boolean get() = Shaft.sSettings?.isUseSecureDns == true
    val directConnect: Boolean get() = Shaft.sSettings?.isDirectConnect == true

    /** 一次性事件：检测到 DNS 污染 / fake-ip 时弹窗提醒（PR #894 的核心诉求）。 */
    private val _pollutionAlert = MutableSharedFlow<NetworkAlert>(extraBufferCapacity = 1)
    val pollutionAlert = _pollutionAlert.asSharedFlow()

    private val work = mutableListOf<TargetReport>()
    private val rawBuilder = StringBuilder()

    /** 图片下载阶段是否有步骤失败（供总体判定降级）。 */
    private var imageDownloadFailed = false

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
        imageDownloadFailed = false
        fakeIpDialogShown = false
        fakeIpDetected = false
        probeIllustUrl = null
        probeUserId = null
        rawBuilder.setLength(0)
        rawLog.value = ""

        val doh = dohEnabled
        val direct = directConnect

        viewModelScope.launch(Dispatchers.IO) {
            try {
                log("环境: 安全 DNS(DoH) ${onOff(doh)} · 直连 ${onOff(direct)}")
                log("")

                // 图片目标联动当前图片代理：反代模式下直接把目标域名换成反代域名来测。
                val imageProxy = imageProxyDomain()
                val imageCfg = if (imageProxy != null) {
                    TargetConfig(imageProxy, "图片服务器 · 反代 $imageProxy", null, TargetKind.IMAGE)
                } else {
                    TargetConfig("i.pximg.net", "图片服务器 · Pixiv Japan", PXIMG_CIDRS, TargetKind.IMAGE)
                }
                val configs = listOf(
                    TargetConfig("app-api.pixiv.net", "pixiv API · Cloudflare CDN", PIXIV_CIDRS, TargetKind.APP_API),
                    TargetConfig("www.pixiv.net", "网页端点 · Cloudflare CDN", PIXIV_CIDRS, TargetKind.WEB_API),
                    imageCfg,
                    TargetConfig("pixshaft.com", "Shaft 云服务 · 浏览记录同步", null, TargetKind.PIXSHAFT),
                )
                val polluted = mutableListOf<String>()
                for (cfg in configs) {
                    val idx = addTarget(TargetReport(cfg.host, cfg.subtitle))
                    if (testTarget(idx, cfg, doh, direct)) polluted.add(cfg.host)
                }

                // 所有目标握手测完之后，再做真实图片下载（插画 + 头像），联动当前图片代理。
                val (imageSlow, imageDimFailed) = runImageDownloadPhase()

                val anyFailed = work.any { it.status == TargetStatus.FAILED }
                val anyHighLatency = work.any { it.status == TargetStatus.HIGH_LATENCY }
                val anyExtremeLatency = work.any { it.status == TargetStatus.EXTREME_LATENCY }
                val anyDegraded = work.any { it.status == TargetStatus.DEGRADED } || imageDownloadFailed
                // 高延迟与超高延迟都算「延迟高」，用于小字提示的判断。
                val latencyHosts = work.filter {
                    it.status == TargetStatus.HIGH_LATENCY || it.status == TargetStatus.EXTREME_LATENCY
                }.map { it.title }
                val imageHighLatency = latencyHosts.contains(imageCfg.host)
                val otherHighLatency = latencyHosts.any { it != imageCfg.host }
                val ov = when {
                    polluted.isNotEmpty() -> OverallStatus.POLLUTED
                    anyFailed || anyDegraded -> OverallStatus.DEGRADED
                    anyExtremeLatency -> OverallStatus.EXTREME_LATENCY
                    anyHighLatency -> OverallStatus.HIGH_LATENCY
                    else -> OverallStatus.CLEAN
                }
                overall.postValue(ov)
                val ctx = Shaft.getContext()
                val subText: String? = when {
                    ov == OverallStatus.HIGH_LATENCY || ov == OverallStatus.EXTREME_LATENCY -> {
                        val generic = ctx.getString(R.string.network_test_overall_high_latency_sub)
                        val imageHint = ctx.getString(R.string.network_test_high_latency_sub_image)
                        // 图片代理与其他端点都高延迟：通用提示在上，图片代理提示在下面一行。
                        when {
                            imageHighLatency && otherHighLatency -> "$generic\n$imageHint"
                            imageHighLatency -> imageHint
                            else -> generic
                        }
                    }
                    ov == OverallStatus.CLEAN -> {
                        var base = ctx.getString(R.string.network_test_overall_clean_sub)
                        // fake-ip 下没有 DNS 解析，删掉「DNS解析」。
                        if (fakeIpDetected) base = base.replace("DNS解析、", "")
                        // 尺寸探测失败：删掉「尺寸探测」并追加说明。
                        if (imageDimFailed) {
                            base = base.replace("尺寸探测、", "") + "\n" + ctx.getString(R.string.network_test_dim_probe_failed_impact)
                        }
                        // 下载缓慢：与「下载缓慢」pill 保持一致，别再说「一切正常」。
                        if (imageSlow) {
                            base = base.replace("图片下载一切正常。", "图片下载缓慢。")
                        }
                        base
                    }
                    else -> null
                }
                overallSub.postValue(subText)
                log(
                    "总体判定: " + when (ov) {
                        OverallStatus.CLEAN -> "网络通畅"
                        OverallStatus.HIGH_LATENCY -> "有端点高延迟"
                        OverallStatus.EXTREME_LATENCY -> "有端点延迟极高"
                        OverallStatus.DEGRADED -> "部分异常"
                        OverallStatus.POLLUTED -> "DNS 污染"
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
                log("测试异常终止: ${e.javaClass.simpleName}: ${e.message}")
                Timber.e(e, "network test aborted")
                overall.postValue(OverallStatus.DEGRADED)
                overallSub.postValue(Shaft.getContext().getString(R.string.network_test_aborted_sub))
            } finally {
                running.postValue(false)
            }
        }
    }

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
        // fake-ip 检测：代理接管 DNS 时返回保留地址。不取消目标——跳过 DNS/ping，仅测握手，
        // 且不再把解析出的 IP 喂给 OkHttp（让代理接管路由）。
        val fakeIps = ipv4.mapNotNull { it.hostAddress }.filter { isFakeIp(it) }
        if (fakeIps.isNotEmpty()) {
            addStep(
                idx,
                TestStep(
                    "系统 DNS 解析",
                    "返回保留地址（疑似代理 fake-ip）: ${fakeIps.joinToString()}\n跳过 DNS 校验与 ping，仅测握手",
                    StepStatus.WARN,
                ),
            )
            log("检测到 fake-ip: ${fakeIps.joinToString()}，跳过 DNS/ping，仅测握手")
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
                    _pollutionAlert.emit(NetworkAlert(R.string.network_test_fakeip_dialog_title, FAKE_IP_DIALOG_MESSAGE))
                }
            }
            log("")
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
        val hs = httpsHandshakeSampled(idx, cfg, targetIp, direct)
        // www.pixiv.net 握手后再发一次真实网页请求，验证 web 端点；失败只降级不算握手失败。
        var webDegraded = false
        if (cfg.kind == TargetKind.WEB_API && hs.ok) {
            webDegraded = !probeWebEndpoint(idx, cfg, targetIp, direct)
        }

        val status = when {
            polluted -> TargetStatus.POLLUTED
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
     * 连接池 0 空闲 → 每次调用都重新握手；默认用 [pinnedDns] 把域名钉到本次选定的 IP（Cronet 路径除外，
     * 其走自身 host-resolver 规则，固定到 Cloudflare IP）。fake-ip 模式传 pin=false 不钉 IP，
     * 交给系统 DNS + 代理接管路由。
     */
    private fun buildHandshakeClient(cfg: TargetConfig, ip: InetAddress?, direct: Boolean, pin: Boolean = true): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectionPool(ConnectionPool(0, 1, TimeUnit.SECONDS))
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .writeTimeout(5, TimeUnit.SECONDS)
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
        return builder.build()
    }

    private fun addCronet(builder: OkHttpClient.Builder) {
        builder.addInterceptor(CronetInterceptor(CronetInterceptor.getEngine(Shaft.getContext())))
    }

    /** 该目标本次握手实际走的路径，标在步骤 label 上让用户看清测的是哪条链路。 */
    private fun handshakePathDesc(cfg: TargetConfig, direct: Boolean): String = when (cfg.kind) {
        TargetKind.IMAGE -> {
            if (direct && !ImageHostManager.requiresStandardClient()) "无 SNI · HTTP/1.1"
            else "标准 TLS · HTTP/1.1"
        }
        else -> if (direct) "直连 Cronet/QUIC" else "标准 TLS"
    }

    /**
     * HTTPS 握手：用 [buildHandshakeClient] 复刻该目标的真实连接，持续 5s 反复建连
     * （连接池 0 空闲，每次都重新握手），实时刷新 min/avg/max/抖动。
     */
    private fun httpsHandshakeSampled(
        idx: Int,
        cfg: TargetConfig,
        ip: InetAddress?,
        direct: Boolean,
        fakeIp: Boolean = false,
    ): HandshakeResult {
        val pathDesc = if (fakeIp) "标准 TLS · 代理接管（fake-ip）" else handshakePathDesc(cfg, direct)
        val stepIdx = work[idx].steps.size
        addStep(idx, TestStep("HTTPS 握手 · 持续 5s 采样 · $pathDesc", "采样中…", StepStatus.RUNNING))
        // fake-ip：不走直连覆写（Cronet / 无 SNI / HttpDns 对代理无意义），标准 TLS + 系统 DNS。
        val client = try {
            buildHandshakeClient(cfg, ip, if (fakeIp) false else direct, pin = !fakeIp)
        } catch (e: Exception) {
            // 客户端构建失败（如 Cronet 引擎初始化异常）也要落成步骤失败，而不是中断整轮测试。
            log("握手客户端构建失败: ${e.javaClass.simpleName}: ${e.message}")
            updateStep(idx, stepIdx, "客户端构建失败: ${e.javaClass.simpleName}: ${e.message}", StepStatus.FAIL)
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

    /**
     * www.pixiv.net 的真实网页请求：GET /ajax/illust/{样例作品}（匿名 SFW 可用），
     * 校验返回 error=false 且带图片地址；顺带把地址/作者 ID 存下来给图片下载阶段复用。
     * @return 是否成功拿到有效响应（失败只把目标降为 DEGRADED，不算握手失败）。
     */
    private fun probeWebEndpoint(idx: Int, cfg: TargetConfig, ip: InetAddress, direct: Boolean): Boolean {
        val stepIdx = work[idx].steps.size
        addStep(idx, TestStep("网页请求 · /ajax/illust/$SAMPLE_ILLUST_ID", "请求中…", StepStatus.RUNNING))
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
                    updateStep(idx, stepIdx, "HTTP ${resp.code} · ${ms}ms · 作品 #$SAMPLE_ILLUST_ID 已返回图片地址", StepStatus.OK)
                    log("网页请求: HTTP ${resp.code} ${ms}ms url=$url")
                } else {
                    updateStep(idx, stepIdx, "HTTP ${resp.code} · error=$error · 未拿到图片地址", StepStatus.WARN)
                    log("网页请求异常: HTTP ${resp.code} error=$error")
                }
            }
        } catch (e: Exception) {
            updateStep(idx, stepIdx, "请求失败: ${e.javaClass.simpleName}: ${e.message}", StepStatus.WARN)
            log("网页请求失败: ${e.message}")
        } finally {
            client?.let {
                it.connectionPool.evictAll()
                it.dispatcher.executorService.shutdown()
            }
        }
        return ok
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

        val title = "图片下载探测"
        val sub = "样例作品 #$SAMPLE_ILLUST_ID · 插画 + 头像"
        val steps = mutableListOf<TestStep>()
        fun push(step: TestStep) {
            steps.add(step)
            imageDownloadReport.postValue(TargetReport(title, sub, TargetStatus.RUNNING, steps.toList()))
        }
        var dimOk = false
        var downloadSlow = false
        try {
            // 1. 地址准备：优先复用 www.pixiv.net 探测结果，否则现场抓，再不行用内置样例。
            var illustUrl = probeIllustUrl
            var avatarUrl: String? = null
            var dimSource = "复用 www.pixiv.net 探测"
            if (illustUrl != null) {
                avatarUrl = probeUserId?.let { fetchAvatarUrl(it) }
            } else {
                val fetched = fetchSampleUrls()
                illustUrl = fetched.first
                avatarUrl = fetched.second
                dimSource = if (illustUrl != null) "现场获取" else "内置样例兜底"
            }
            if (illustUrl == null) illustUrl = FALLBACK_ILLUST_URL
            if (avatarUrl == null) avatarUrl = FALLBACK_AVATAR_URL
            log("图片下载地址: $dimSource")
            log("  插画: $illustUrl")
            log("  头像: $avatarUrl")

            // 2. 探测图片尺寸：网页 ajax 拿第一页真实宽高；拿不到就并列黄底「探测失败」。
            val (dimStep, dimProbeOk) = probeImageDimensions(dimSource)
            dimOk = dimProbeOk
            push(dimStep)
            imageDimensionFailed.postValue(!dimOk)

            val hostDesc = imageHostDesc()
            log("图片代理路由: $hostDesc")
            push(TestStep("图片代理路由", hostDesc, StepStatus.INFO))

            val (illustStep, illustSlow) = downloadImageStep("插画图片下载", illustUrl)
            push(illustStep)
            val (avatarStep, avatarSlow) = downloadImageStep("头像图片下载", avatarUrl)
            push(avatarStep)

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
        } catch (e: Exception) {
            log("图片下载阶段异常: ${e.javaClass.simpleName}: ${e.message}")
            push(TestStep("图片下载失败", e.javaClass.simpleName + (e.message?.let { ": $it" } ?: ""), StepStatus.FAIL))
            imageDownloadFailed = true
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
            return false to !dimOk
        } finally {
            imageDownloadRunning.postValue(false)
        }
        return downloadSlow to !dimOk
    }

    /**
     * 图片下载专用客户端，镜像 Shaft.onCreate 的图片 OkHttpClient：
     * 全局强制 HTTP/1.1；仅「PIXIV 官方 + 直连」装无 SNI / 信任所有证书 / HttpDns 覆写，
     * 其它模式（pixiv.cat 等反代、自定义反代）退回系统 DNS + 标准 TLS。
     */
    private fun buildImageDownloadClient(): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectionPool(ConnectionPool(0, 1, TimeUnit.SECONDS))
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(8, TimeUnit.SECONDS)
            .protocols(listOf(Protocol.HTTP_1_1))
        if (directConnect && !ImageHostManager.requiresStandardClient()) {
            try {
                builder.sslSocketFactory(RubySSLSocketFactory(), TrustAllCertManager())
                builder.hostnameVerifier { _, _ -> true }
            } catch (e: Exception) {
                Timber.e(e, "image download no-SNI SSL init error")
            }
            builder.dns(HttpDns.getInstance())
        }
        return builder.build()
    }

    /** 下一张真图：HTTP 200 + magic bytes 校验，报告字节数 / 耗时 / 吞吐 / 实际请求 host。
     *  @return (步骤, 是否下载缓慢)。 */
    private fun downloadImageStep(label: String, rawUrl: String): Pair<TestStep, Boolean> {
        val realUrl = ImageHostManager.rewrite(rawUrl)
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
                val code = resp.code
                val body = resp.body
                if (body == null) {
                    log("$label: HTTP $code 无响应体")
                    TestStep(label, "HTTP $code · 无响应体", StepStatus.FAIL) to false
                } else {
                    val (data, truncated) = readCapped(body.byteStream(), MAX_IMAGE_DOWNLOAD_BYTES)
                    val ms = System.currentTimeMillis() - t0
                    val format = imageMagic(data)
                    val sizeTxt = formatBytes(data.size) + if (truncated) "+" else ""
                    val speedTxt = if (ms > 0) "${data.size * 1000L / ms / 1024}KB/s" else "-"
                    val host = realUrl.substringAfter("://").substringBefore('/')
                    val ok = code in 200..299 && format != null

                    // 延迟/缓慢判定：与连通性诊断同阈值；下载缓慢 = 均速 < 1.2 × 大小(KB)。
                    val sizeKB = data.size / 1024f
                    val speedKBs = if (ms > 0) data.size * 1000f / ms / 1024f else 0f
                    val slow = speedKBs < 1.2f * sizeKB
                    val tags = mutableListOf<String>()
                    if (ok) {
                        if (ms > EXTREME_LATENCY_MS) {
                            tags.add("超高延迟")
                        } else if (ms > HIGH_LATENCY_MS) {
                            tags.add("高延迟")
                        }
                        if (slow) tags.add("下载缓慢")
                    }
                    val tagTxt = if (tags.isNotEmpty()) " · " + tags.joinToString(" · ") else ""
                    log(
                        "$label: HTTP $code · ${data.size}B · ${ms}ms · $speedTxt · $format$tagTxt · " +
                            "$rawUrl -> $realUrl",
                    )
                    val st = when {
                        !ok -> StepStatus.FAIL
                        ms > EXTREME_LATENCY_MS -> StepStatus.EXTREME_LATENCY
                        ms > HIGH_LATENCY_MS -> StepStatus.HIGH_LATENCY
                        slow -> StepStatus.WARN
                        else -> StepStatus.OK
                    }
                    val detail = if (ok) {
                        "HTTP $code · $sizeTxt · ${ms}ms · $speedTxt · $format ✓$tagTxt\n实际请求: $host"
                    } else {
                        "HTTP $code · $sizeTxt · ${ms}ms${if (format != null) " · $format" else ""}\n实际请求: $host"
                    }
                    TestStep(label, detail, st) to slow
                }
            }
        } catch (e: Exception) {
            log("$label: 请求失败 ${e.message}")
            result = TestStep(label, "请求失败: ${e.javaClass.simpleName}: ${e.message}", StepStatus.FAIL) to false
        } finally {
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
        return result
    }

    /** 现场抓样例作品的插画地址 + 画师头像地址（匿名 SFW 可用），失败返回 null。 */
    private fun fetchSampleUrls(): Pair<String?, String?> {
        val client = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .protocols(listOf(Protocol.HTTP_1_1))
            .build()
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
            log("现场抓取样例地址失败: ${e.message}")
            null to null
        } finally {
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
    }

    /** GET /ajax/user/{id} 拿画师头像地址；[client] 为空时自建一个。 */
    private fun fetchAvatarUrl(userId: String, shared: OkHttpClient? = null): String? {
        val client = shared ?: OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .protocols(listOf(Protocol.HTTP_1_1))
            .build()
        return try {
            client.newCall(
                Request.Builder().url("https://www.pixiv.net/ajax/user/$userId?full=1&lang=zh").get().build(),
            ).execute().use { resp ->
                val json = runCatching { Gson().fromJson(resp.body?.string(), JsonObject::class.java) }.getOrNull()
                val bodyObj = json?.getAsJsonObject("body")
                bodyObj?.get("image")?.takeIf { it.isJsonPrimitive }?.asString
            }
        } catch (e: Exception) {
            log("获取画师头像地址失败: ${e.message}")
            null
        } finally {
            if (shared == null) {
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
        val client = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .protocols(listOf(Protocol.HTTP_1_1))
            .build()
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
                    TestStep("探测图片尺寸", "宽 $w · 高 $h · 第一页 · $source · ${ms}ms", StepStatus.OK) to true
                } else {
                    log("探测图片尺寸失败: HTTP ${resp.code} · ${ms}ms · 未拿到第一页宽高")
                    TestStep(
                        "探测图片尺寸",
                        "HTTP ${resp.code} · ${ms}ms · 未拿到第一页宽高（该接口需网页 cookie）",
                        StepStatus.WARN,
                    ) to false
                }
            }
        } catch (e: Exception) {
            log("探测图片尺寸失败: ${e.javaClass.simpleName}: ${e.message}")
            TestStep("探测图片尺寸", "请求失败: ${e.javaClass.simpleName}: ${e.message}", StepStatus.WARN) to false
        } finally {
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
    }

    private fun imageHostDesc(): String = when (ImageHostManager.getMode()) {
        ImageHostManager.Mode.PIXIV -> "Pixiv 官方 · i.pximg.net 直连"
        ImageHostManager.Mode.PIXIV_CAT -> "pixiv.cat 反代"
        ImageHostManager.Mode.PIXIV_RE -> "pixiv.re 反代"
        ImageHostManager.Mode.PIXIV_NL -> "pixiv.nl 反代"
        ImageHostManager.Mode.CUSTOM -> {
            val host = ImageHostManager.getCustomHost()
            if (host.isEmpty()) "自定义反代（未配置）" else "自定义反代: $host"
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

        /** 握手 avg 超过该阈值判定为「高延迟」。 */
        private const val HIGH_LATENCY_MS = 500

        /** 握手 max 超过该阈值判定为「超高延迟」（红底）。 */
        private const val EXTREME_LATENCY_MS = 1000

        /** fake-ip 提示弹窗文案（每轮只弹一次）。 */
        private const val FAKE_IP_DIALOG_MESSAGE =
            "当前网络启用了VPN / 代理，且DNS模式为fake-ip\n" +
                "部分测试将会跳过\n" +
                "建议将DNS模式改为redir-host或normal完善跳过的部分"

        /** 图片下载探测用的内置样例作品（仓库既有数据，SFW、长期稳定）与其兜底地址。 */
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
