package ceui.lisa.http

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CfBlockGuideSessionTest {
    @Test
    fun `没有前台宿主时不查询也不占提示机会`() = runTest {
        val session = CfBlockGuideSession<String>()
        session.run({ null }, { error("后台不应查询 IP") }) { _, _ ->
            error("后台不应弹窗")
        }
        var shown = false
        session.run({ "foreground" }, { null }) { host, ip ->
            assertEquals("foreground", host)
            assertEquals(null, ip)
            shown = true
            true
        }
        assertTrue(shown)
    }

    @Test
    fun `查询期间宿主重建后使用新宿主且只弹一次`() = runTest {
        val session = CfBlockGuideSession<String>()
        val ip = CompletableDeferred<String?>()
        var host = "old"
        val shown = mutableListOf<String>()
        launch {
            session.run({ host }, { ip.await() }) { current, address ->
                shown += current
                assertEquals("203.0.113.*", address)
                true
            }
        }
        runCurrent()
        session.run({ host }, { error("并发错误不应重复查询") }) { _, _ ->
            error("并发错误不应重复弹窗")
        }
        host = "new"
        ip.complete("203.0.113.*")
        runCurrent()
        session.run({ host }, { error("已提示后不应再查询") }) { _, _ ->
            error("每进程只弹一次")
        }
        assertEquals(listOf("new"), shown)
    }

    @Test
    fun `查询期间退到后台时保留提示机会`() = runTest {
        val session = CfBlockGuideSession<String>()
        val ip = CompletableDeferred<String?>()
        var host: String? = "foreground"
        launch {
            session.run({ host }, { ip.await() }) { _, _ ->
                error("退到后台后不应弹窗")
            }
        }
        runCurrent()
        host = null
        ip.complete(null)
        runCurrent()
        var shown = false
        host = "resumed"
        session.run({ host }, { null }) { _, _ -> shown = true; true }
        assertTrue(shown)
    }

    @Test
    fun `窗口未成功显示时下次仍可提示`() = runTest {
        val session = CfBlockGuideSession<String>()
        var attempts = 0
        repeat(2) {
            session.run({ "foreground" }, { null }) { _, _ ->
                attempts++
                attempts == 2
            }
        }
        assertEquals(2, attempts)
    }

    @Test
    fun `查询取消后下次仍可提示`() = runTest {
        val session = CfBlockGuideSession<String>()
        val job = launch {
            session.run({ "foreground" }, { CompletableDeferred<String?>().await() }) { _, _ ->
                error("取消的查询不应弹窗")
            }
        }
        runCurrent()
        job.cancel()
        job.join()
        var shown = false
        session.run({ "foreground" }, { null }) { _, _ -> shown = true; true }
        assertTrue(shown)
    }
}
