package ceui.lisa.http

import okhttp3.Call
import okhttp3.Callback
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * [BufferCorruptionGuardInterceptor] 的行为回归。
 *
 * 用一个「必抛 AIOOBE 的 network interceptor」模拟线上 okio buffer 被并发写坏的现场
 * （真实现场是 `Http1ExchangeCodec.writeRequest` 里抛，位置同样在 network 层之内），
 * 断言的是**崩溃形态**：dispatcher 线程不能带着 uncaught 异常死掉。
 */
class BufferCorruptionGuardInterceptorTest {

    private lateinit var server: MockWebServer
    private var previousHandler: Thread.UncaughtExceptionHandler? = null

    /** okhttp dispatcher 线程上逃逸出来的异常（= 线上那条 Fatal Exception） */
    @Volatile
    private var uncaught: Throwable? = null

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, throwable -> uncaught = throwable }
    }

    @After
    fun tearDown() {
        Thread.setDefaultUncaughtExceptionHandler(previousHandler)
        server.shutdown()
    }

    @Test
    fun `guard turns buffer corruption into an IOException failure instead of killing the thread`() {
        val failure = runCall(withGuard = true)

        assertNotNull("回调应该收到失败", failure)
        assertTrue(
            "非 IOException 必须被包成 IOException 才能被 okhttp 的 onFailure 分支接住",
            hasCause(failure!!, ArrayIndexOutOfBoundsException::class.java),
        )
        assertNull("装了守卫就不该再有 uncaught 异常（线上那条 Fatal Exception）", uncaught)
    }

    /** 反面基线：没有守卫时，okhttp 的 `AsyncCall.run` 会 `throw t`，异常从线程池逃逸。 */
    @Test
    fun `without the guard the exception escapes the dispatcher thread`() {
        runCall(withGuard = false)

        assertTrue(
            "基线断言：裸 okhttp 会把非 IOException 原样抛出线程池 —— 这就是要修的崩溃",
            uncaught is ArrayIndexOutOfBoundsException,
        )
    }

    /** 发一发异步请求并等回调；返回 onFailure 收到的异常（成功则返回 null）。 */
    private fun runCall(withGuard: Boolean): IOException? {
        val builder = OkHttpClient.Builder()
            // 关掉重试，避免 corrupter 反复抛出拖长用例；线上重试是想要的行为。
            .retryOnConnectionFailure(false)
        if (withGuard) {
            builder.addNetworkInterceptor(BufferCorruptionGuardInterceptor())
        }
        builder.addNetworkInterceptor(CORRUPTER)
        val client = builder.build()

        server.enqueue(MockResponse().setBody("ok"))

        val latch = CountDownLatch(1)
        var failure: IOException? = null
        client.newCall(Request.Builder().url(server.url("/img.jpg")).build())
            .enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    failure = e
                    latch.countDown()
                }

                override fun onResponse(call: Call, response: Response) {
                    response.close()
                    latch.countDown()
                }
            })
        assertTrue("请求回调超时", latch.await(10, TimeUnit.SECONDS))
        // uncaught 由 dispatcher 线程在回调之后才抛出，给它落地的时间
        Thread.sleep(200)
        return failure
    }

    private fun hasCause(throwable: Throwable, type: Class<out Throwable>): Boolean {
        var current: Throwable? = throwable
        while (current != null) {
            if (type.isInstance(current)) return true
            current = current.cause
        }
        return false
    }

    private companion object {
        /** 复刻线上那一发：`checkOffsetAndCount` 在写请求头时炸掉。 */
        val CORRUPTER = Interceptor {
            throw ArrayIndexOutOfBoundsException("size=128 offset=0 byteCount=7724")
        }
    }
}
