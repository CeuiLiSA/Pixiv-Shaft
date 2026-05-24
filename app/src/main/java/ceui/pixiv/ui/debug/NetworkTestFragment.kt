package ceui.pixiv.ui.debug

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.widget.Toolbar
import androidx.fragment.app.Fragment
import ceui.lisa.R
import kotlinx.coroutines.*
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.IOException
import java.net.InetAddress
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.UnknownHostException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import kotlin.math.ln

/**
 * 网络测试页。暂时只有DNS污染检测、TCP ping、https握手测试。
 * 测试逻辑：
 * 1. 检测DNS污染（如有污染跳过2和3）
 * 2. 检测443端口可达性（TCP Ping）
 * 3. HTTPS握手测试
 * 后续可以考虑增加：
 * 1. 动态CIDR列表
 * 2. 联动设置页的安全DNS（DoH），使用DoH解析
 * 3. UI实时滚动的日志，和白天黑夜主题适应
 * 4. 实时https握手延迟显示（持续测试5秒）（目前仅单次）
 * 5. 联动设置开启直连额外的ping(ICMP)测试（代理下测ping无意义，ICMP会透过代理）
 * 6. PixshaftApi的相关测试（PixshaftApi.kt）
 * 6. 可填入插画/漫画ID，测试单个插画/漫画的API响应时、响应体内容（如是否有简介）、图片数量、图片分辨率、图片格式、图片质量等。
 * @author wangwang-code & deepseek & gemini
 */
class NetworkTestFragment : Fragment(R.layout.fragment_network_perf_test) {

    private lateinit var textView: TextView
    private lateinit var button: Button
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // 预设的CIDR列表
    private val pixivCIDRs = listOf(
        "173.245.48.0/20",
        "103.21.244.0/22",
        "103.22.200.0/22",
        "103.31.4.0/22",
        "141.101.64.0/18",
        "108.162.192.0/18",
        "190.93.240.0/20",
        "188.114.96.0/20",
        "197.234.240.0/22",
        "198.41.128.0/17",
        "162.158.0.0/15",
        "104.16.0.0/13",
        "104.24.0.0/14",
        "172.64.0.0/13",
        "131.0.72.0/22"
    )

    private val pximgCIDRs = listOf(
        "210.140.92.0/24",
        "210.140.131.0/24",
        "210.140.139.0/24",
        "210.140.140.0/24",
        "210.140.141.0/24",
        "210.140.142.0/24",
        "210.140.143.0/24",
        "210.140.144.0/24",
        "210.140.145.0/24",
        "210.140.146.0/24",
        "210.140.147.0/24",
        "210.140.148.0/24",
        "210.140.149.0/24",
        "210.140.150.0/24"
    )

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.findViewById<Toolbar>(R.id.toolbar)
            .setNavigationOnClickListener { activity?.finish() }
        textView = view.findViewById(R.id.outputText)
        button = view.findViewById(R.id.button)

        button.setOnClickListener {
            button.isEnabled = false
            textView.text = "正在测试..."
            startNetworkTest()
        }
    }

    private fun startNetworkTest() {
        scope.launch {
            val result = StringBuilder()

            // 测试 www.pixiv.net
            result.append("========== www.pixiv.net 测试 ==========\n")
            testDomain("www.pixiv.net", pixivCIDRs, result)

            result.append("\n\n")

            // 测试 i.pximg.net
            result.append("========== i.pximg.net 测试 ==========\n")
            testDomain("i.pximg.net", pximgCIDRs, result)

            // 更新UI
            withContext(Dispatchers.Main) {
                textView.text = result.toString()
                button.isEnabled = true
            }
        }
    }



    private fun testDomain(domain: String, cidrList: List<String>, result: StringBuilder) {
        try {
            // 获取所有IP地址
            val addresses = InetAddress.getAllByName(domain)
            result.append("DNS解析结果：\n")

            var isDnsPolluted = false
            var firstValidIp: InetAddress? = null

            addresses.forEachIndexed { index, address ->
                result.append("  IP ${index + 1}: ${address.hostAddress}\n")
                //result.append("  类型: ${if (address is Inet4Address) "IPv4" else "IPv6"}\n")

                // 只对IPv4进行CIDR检查
                if (address is Inet4Address) {
                    val ip = address.hostAddress ?: ""
                    var inCIDR = false

                    for (cidr in cidrList) {
                        if (isIpInCIDR(ip, cidr)) {
                            result.append("  ✓ 在预设CIDR范围内: $cidr\n")
                            inCIDR = true
                            break
                        }
                    }

                    if (!inCIDR) {
                        result.append("  ✗ 不在任何预设CIDR范围内\n 存在DNS污染\n")
                        isDnsPolluted = true
                    }
                } else {
                    result.append("  - IPv6地址，未适配做跳过\n")
                    return
                }

                // 如果没有ip被判定为污染，才进行连通性测试，并记录第一个可用的有效 IP
                if (!isDnsPolluted) {
                    testConnectivity(address.hostAddress ?: "", 443, result)
                    if (firstValidIp == null) {
                        firstValidIp = address
                    }
                }

                if (index < addresses.size - 1) {
                    result.append("\n")
                }
            }

            if (addresses.isEmpty()) {
                result.append("  未解析到任何IP地址\n")
                return
            }

            // 根据是否有干净的 IP 决定是否进行 HTTPS 握手
            if (firstValidIp != null) {
                result.append("\n开始测试 HTTPS 握手 (强制使用第一个干净的IP: ${firstValidIp!!.hostAddress})...\n")
                testHttpsHandshakeWithOkHttp(domain, firstValidIp!!, result)
            } else {
                result.append("\n! 无有效IP，跳过HTTPS握手测试。\n")
            }

        } catch (e: UnknownHostException) {
            result.append("✗ DNS解析失败: ${e.message}\n")
        } catch (e: Exception) {
            result.append("✗ 测试异常: ${e.message}\n")
        }
    }


    /**
     * 使用 OkHttp 测试 HTTPS 握手并统计耗时
     */
    private fun testHttpsHandshakeWithOkHttp(domain: String, targetIp: InetAddress, result: StringBuilder) {
        // 1. 自定义 DNS 拦截器，强制 OkHttp 只返回指定的第一个 IP
        val singleIpDns = object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                return if (hostname.equals(domain, ignoreCase = true)) {
                    listOf(targetIp)
                } else {
                    Dns.SYSTEM.lookup(hostname)
                }
            }
        }

        // 2. 构造 Client 并设置超时（5秒）
        val timeoutMs = 5000L
        val client = OkHttpClient.Builder()
            .dns(singleIpDns)
            .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .writeTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .build()

        // 3. 构造请求
        val request = Request.Builder()
            .url("https://$domain/")
            .head() // 使用 HEAD 请求减少流量
            .build()

        // 记录开始时间
        val startTime = System.currentTimeMillis()

        try {
            client.newCall(request).execute().use { response ->
                // 计算总耗时（包含 TCP 建连 + TLS 握手）
                val handshakeTime = System.currentTimeMillis() - startTime
                val handshake = response.handshake

                result.append("  ✓ HTTPS 握手成功 (${handshakeTime}ms)\n")
                if (handshake != null) {
                    result.append("    协议: ${handshake.tlsVersion}\n")
                    result.append("    加密套件: ${handshake.cipherSuite}\n")
                }
            }
        } catch (e: SocketTimeoutException) {
            val totalTime = System.currentTimeMillis() - startTime
            result.append("  ✗ HTTPS 握手或连接超时 (超过 ${timeoutMs}ms，实际耗时: ${totalTime}ms)\n")
        } catch (e: SSLHandshakeException) {
            result.append("  ✗ HTTPS 握手失败: ${e.localizedMessage}\n")
            result.append("    (常见原因: 证书过期、根证书不被信任、TLS版本不匹配)\n")
        } catch (e: SSLPeerUnverifiedException) {
            result.append("  ✗ HTTPS 证书校验失败: ${e.localizedMessage}\n")
            result.append("    (常见原因: 证书中的域名与当前访问的域名 $domain 不匹配)\n")
        } catch (e: IOException) {
            result.append("  ✗ HTTPS 网络连接错误: ${e.message}\n")
        } catch (e: Exception) {
            result.append("  ✗ HTTPS 未知异常: ${e.message}\n")
        }
    }



    private fun testConnectivity(ip: String, port: Int, result: StringBuilder) {
        try {
            val startTime = System.currentTimeMillis()
            val socket = Socket()
            socket.connect(InetSocketAddress(ip, port), 3000)
            val latency = System.currentTimeMillis() - startTime
            socket.close()
            if (latency <= 10) {
                result.append("  连通性: 延迟: ${latency}ms) 过低不具备参考性\n应看HTTPS握手耗时\n")
            } else result.append("  连通性: ✓ 可达 (延迟: ${latency}ms)\n")
        } catch (e: Exception) {
            result.append("  连通性: ✗ 不可达 (${e.message})\n")
        }
    }

    private fun isIpInCIDR(ip: String, cidr: String): Boolean {
        try {
            val parts = cidr.split("/")
            if (parts.size != 2) return false

            val networkAddress = parts[0]
            val prefixLength = parts[1].toIntOrNull() ?: return false

            val ipBytes = ipToBytes(ip) ?: return false
            val networkBytes = ipToBytes(networkAddress) ?: return false

            // 计算子网掩码
            val mask = if (prefixLength == 0) 0 else (-1 shl (32 - prefixLength))

            val ipInt = bytesToInt(ipBytes)
            val networkInt = bytesToInt(networkBytes)

            return (ipInt and mask) == (networkInt and mask)
        } catch (e: Exception) {
            return false
        }
    }

    private fun ipToBytes(ip: String): ByteArray? {
        try {
            val octets = ip.split(".")
            if (octets.size != 4) return null

            return ByteArray(4) { i ->
                val octet = octets[i].toIntOrNull()
                if (octet == null || octet !in 0..255) return null
                octet.toByte()
            }
        } catch (e: Exception) {
            return null
        }
    }

    private fun bytesToInt(bytes: ByteArray): Int {
        var result = 0
        for (byte in bytes) {
            result = (result shl 8) or (byte.toInt() and 0xFF)
        }
        return result
    }

    override fun onDestroyView() {
        super.onDestroyView()
        scope.cancel()
    }
}