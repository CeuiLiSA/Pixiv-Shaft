package ceui.pixiv.ui.debug

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [parseCdnTrace] / [maskTraceIps] 的回归。
 *
 * 这两个函数只服务网络测试页里 app-api.pixiv.net **握手之后**追加的 `/cdn-cgi/trace` 步骤。
 * 这一步按设计「失败不参与任何判定」，所以它的正确性只体现在两件事上：
 *   · 解析错了 —— 卡片会显示一个假节点，比不显示更糟；
 *   · 脱敏漏了 —— 用户出口 IP 会被原样写进可一键复制的原始日志。
 *
 * 样本按实测响应的字段结构构造；其中的地址与 UA 都是通用示例值（RFC 5737 / RFC 3849
 * 文档段），不含任何真实出口信息。
 */
class CdnTraceTest {

    private val sample = listOf(
        "fl=966f46",
        "h=app-api.pixiv.net",
        "ip=203.0.113.45",
        "ts=1758444000.123",
        "visit_scheme=https",
        "uag=Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36",
        "colo=NRT",
        "sliver=none",
        "http=http/2",
        "loc=JP",
        "tls=TLSv1.3",
        "sni=plaintext",
        "warp=off",
        "gateway=off",
        "rbi=off",
        "kex=X25519",
    ).joinToString("\n")

    // ── 解析：取 loc 与 colo 的值 ──────────────────────────────────────

    @Test
    fun `取 loc 与 colo 的值`() {
        val info = parseCdnTrace(sample)
        assertEquals("JP", info.loc)
        assertEquals("NRT", info.colo)
    }

    @Test
    fun `字段缺失或值为空一律 null`() {
        assertNull(parseCdnTrace("").colo)
        assertNull(parseCdnTrace("").loc)
        assertNull(parseCdnTrace("colo=\nloc=\n").colo)
        assertNull(parseCdnTrace("fl=1\nh=app-api.pixiv.net\n").colo)
    }

    @Test
    fun `值里的等号不截断取值`() {
        // uag 的值本身可能带 =；按 split("=") 取值会把后面的 loc/colo 一起带歪。
        val info = parseCdnTrace("uag=Mozilla/5.0 (a=b)\nloc=JP\ncolo=LAX")
        assertEquals("JP", info.loc)
        assertEquals("LAX", info.colo)
    }

    @Test
    fun `键名大小写不敏感`() {
        assertEquals("NRT", parseCdnTrace("COLO=NRT").colo)
    }

    @Test
    fun `非 key=value 的行不参与解析`() {
        // 正文可能混进拦截页片段；既不能崩，也不能从这些行里取到值。
        assertNull(parseCdnTrace("=NRT\nno-equals-here").colo)
    }

    // ── 脱敏：只动 ip 键 ───────────────────────────────────────────────

    @Test
    fun `只脱敏 ip 行，其余字段原样`() {
        val masked = maskTraceIps(sample)
        assertTrue(masked.contains("ip=203.0.113.*"))
        assertTrue(masked.contains("colo=NRT"))
        assertTrue(masked.contains("loc=JP"))
        assertTrue(masked.contains("h=app-api.pixiv.net"))
    }

    @Test
    fun `IPv6 出口地址同样脱敏`() {
        assertTrue(maskTraceIps("ip=2001:db8::f03c:91ff:fe1e:1234").startsWith("ip=2001:db8::*"))
    }

    @Test
    fun `含冒号的值不会被当成 IPv6 抹掉`() {
        // 回归：若按「值看起来像不像 IP」判断，maskIp 的 IPv6 分支会把这种 UA 毁成 a:b:c:*。
        val line = "uag=Mozilla/5.0 (X11; rv:1.0) a:b:c:d"
        assertEquals(line, maskTraceIps(line))
    }

    @Test
    fun `colo 与 loc 不会被盲脱敏抹成星号`() {
        // maskIp 对非地址输入直接返回 "*"，所以只能按键名脱敏，不能拿它当 IP 探测器。
        assertEquals("colo=NRT", maskTraceIps("colo=NRT"))
        assertEquals("loc=JP", maskTraceIps("loc=JP"))
    }

    @Test
    fun `没有 ip 行时正文逐字返回`() {
        val body = "colo=NRT\nloc=JP"
        assertEquals(body, maskTraceIps(body))
    }

    @Test
    fun `脱敏不改变行数`() {
        assertEquals(sample.lines().size, maskTraceIps(sample).lines().size)
    }
}