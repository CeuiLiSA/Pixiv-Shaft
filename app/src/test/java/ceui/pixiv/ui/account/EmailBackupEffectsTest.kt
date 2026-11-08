package ceui.pixiv.ui.account

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.*
import org.junit.Test

class EmailBackupEffectsTest {
    @Test fun `restore completion waits for a returning page`() = runBlocking {
        val vm = EmailBackupV3ViewModel(EmailBackupV3ViewModel.Mode.RESTORE)
        val stopped = launch(start = CoroutineStart.UNDISPATCHED) { vm.effects.first() }
        stopped.cancelAndJoin()
        val completion = EmailBackupV3ViewModel.Effect.RestoreLoggedIn(123L)
        publish(vm, completion)
        assertEquals(completion, withTimeout(1_000) { vm.effects.first() })
        assertNull(withTimeoutOrNull(50) { vm.effects.first() })
    }

    @Test fun `backup completion preserves order while the page is stopped`() = runBlocking {
        val vm = EmailBackupV3ViewModel(EmailBackupV3ViewModel.Mode.RESTORE)
        val toast = EmailBackupV3ViewModel.Effect.Toast("done")
        publish(vm, toast)
        publish(vm, EmailBackupV3ViewModel.Effect.Finish)
        assertEquals(toast, withTimeout(1_000) { vm.effects.first() })
        assertEquals(EmailBackupV3ViewModel.Effect.Finish, withTimeout(1_000) { vm.effects.first() })
    }

    // 仅注入网络完成边界的事件；使用生产 ViewModel 的真实事件出口和公开接收流。
    private fun publish(vm: EmailBackupV3ViewModel, effect: EmailBackupV3ViewModel.Effect) {
        val emit = EmailBackupV3ViewModel::class.java.getDeclaredMethod(
            "emit", EmailBackupV3ViewModel.Effect::class.java,
        )
        emit.isAccessible = true
        emit.invoke(vm, effect)
    }
}
