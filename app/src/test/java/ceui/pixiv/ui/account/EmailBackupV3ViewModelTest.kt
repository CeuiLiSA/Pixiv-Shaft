package ceui.pixiv.ui.account

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.lifecycle.ViewModelStore
import ceui.pixiv.shaftapi.PixshaftApi
import ceui.pixiv.shaftapi.RestoreRequestAck
import ceui.pixiv.ui.account.EmailBackupV3ViewModel.Effect
import ceui.pixiv.ui.account.EmailBackupV3ViewModel.Mode
import java.lang.reflect.Proxy
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.startCoroutine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** 只走公开入口；网络用可控的 [PixshaftApi]（同 ReferralPlanViewModelTest 的 Proxy 写法）。 */
@OptIn(ExperimentalCoroutinesApi::class)
class EmailBackupV3ViewModelTest {
    @get:Rule val executor = InstantTaskExecutorRule()

    /** 每次调用挂起到测试 [answer]；挂在调用方 Job 上，取消语义同 Retrofit 的 suspend 接口。 */
    private class Api {
        val calls = mutableListOf<Pair<String, CompletableDeferred<Any>>>()
        val proxy = Proxy.newProxyInstance(PixshaftApi::class.java.classLoader, arrayOf(PixshaftApi::class.java)) { _, method, args ->
            val answer = CompletableDeferred<Any>()
            calls += method.name to answer
            @Suppress("UNCHECKED_CAST")
            val continuation = args!!.last() as Continuation<Any>
            (suspend { answer.await() }).startCoroutine(continuation)
            COROUTINE_SUSPENDED
        } as PixshaftApi
        fun answer(index: Int, body: Any) { calls[index].second.complete(body) }
    }

    private fun TestScope.withViewModel(block: (EmailBackupV3ViewModel, Api) -> Unit) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val api = Api()
            val vm = EmailBackupV3ViewModel(Mode.RESTORE) { api.proxy }
            store.put("email", vm)
            block(vm, api)
        } finally { store.clear(); Dispatchers.resetMain() }
    }

    @Test fun effectEmittedWhilePageIsStoppedIsDeliveredOnceOnReturn() = runTest {
        withViewModel { vm, api ->
            // 页面先收过一轮再 STOP：repeatOnLifecycle 取消收集后，事件流不能跟着关掉。
            launch { vm.effects.collect {} }.also { runCurrent() }.cancel()
            vm.onEmailChanged("a@b.co")
            vm.requestCode()
            runCurrent()
            assertEquals(listOf("restoreRequest"), api.calls.map { it.first })
            api.answer(0, RestoreRequestAck(ok = true, found = true))
            runCurrent()
            assertTrue(vm.state.value!!.codeSent)

            val delivered = mutableListOf<Effect>()
            repeat(2) { launch { vm.effects.collect { delivered += it } }.also { runCurrent() }.cancel() }
            assertEquals(listOf<Effect>(Effect.FocusCode), delivered)
        }
    }

    @Test fun codeRequestFromAnAbandonedFlowDoesNotLandInTheNextOne() = runTest {
        withViewModel { vm, api ->
            vm.onEmailChanged("a@b.co")
            vm.requestCode()
            runCurrent()
            // 恢复 → 备份 → 恢复：模式回到原值，旧请求的回包仍不属于这一轮。
            vm.setMode(Mode.BACKUP)
            vm.setMode(Mode.RESTORE)
            vm.onEmailChanged("a@b.co")
            api.answer(0, RestoreRequestAck(ok = true, found = true))
            runCurrent()
            vm.state.value!!.let {
                assertFalse(it.codeSent)
                assertFalse(it.loading)
                assertEquals(0, it.resendCountdown)
                assertNull(it.status)
            }

            vm.requestCode()
            runCurrent()
            api.answer(1, RestoreRequestAck(ok = true, found = true))
            runCurrent()
            assertTrue(vm.state.value!!.codeSent)
        }
    }
}
