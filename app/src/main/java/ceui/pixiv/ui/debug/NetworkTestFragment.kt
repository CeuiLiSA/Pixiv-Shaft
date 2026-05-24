package ceui.pixiv.ui.debug

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.fragment.app.Fragment
import ceui.lisa.R
import kotlinx.coroutines.*
import java.net.InetAddress
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.UnknownHostException
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.math.ln

/**
 * 网络测试页。暂时只有DNS污染检测和443可达性测试。
 * 测试逻辑：
 * 1. 检测DNS污染
 * 2. 检测443端口可达性
 * 后续可以考虑增加：
 * 1. 动态CIDR列表
 * 2. 安全DNS（DoH）解析测试
 * 3. UI实时滚动的日志
 * 4. 实时https握手延迟显示（持续测试5秒）
 * 5. 可填入插画/漫画ID，测试单个插画/漫画的API响应时、响应体内容（如是否有简介）、图片数量、图片分辨率、图片格式、图片质量等。
 * @author wangwang-code & deepseek
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

        textView = view.findViewById(R.id.textView)
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

            addresses.forEachIndexed { index, address ->
                result.append("  IP ${index + 1}: ${address.hostAddress}\n")
                result.append("  类型: ${if (address is Inet4Address) "IPv4" else "IPv6"}\n")

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
                    }
                } else {
                    result.append("  - IPv6地址，跳过CIDR检查\n")
                }

                // 测试连通性
                testConnectivity(address.hostAddress ?: "", 443, result)

                if (index < addresses.size - 1) {
                    result.append("\n")
                }
            }

            if (addresses.isEmpty()) {
                result.append("  未解析到任何IP地址\n")
            }

        } catch (e: UnknownHostException) {
            result.append("✗ DNS解析失败: ${e.message}\n")
        } catch (e: Exception) {
            result.append("✗ 测试异常: ${e.message}\n")
        }
    }

    private fun testConnectivity(ip: String, port: Int, result: StringBuilder) {
        try {
            val startTime = System.currentTimeMillis()
            val socket = Socket()
            socket.connect(InetSocketAddress(ip, port), 3000)
            val latency = System.currentTimeMillis() - startTime
            socket.close()
            result.append("  连通性: ✓ 可达 (延迟: ${latency}ms)\n")
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