package ceui.pixiv.transfer

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ceui.lisa.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class DeviceTransferViewModel(application: Application) : AndroidViewModel(application) {
    enum class Role { SEND, RECEIVE }
    enum class Phase {
        HOME, SCAN, STARTING, PAIRING, APPROVE, WAIT, PREPARE, TRANSFER, REMOTE_RESTORE,
        RESTORE, COMPLETE, ERROR,
    }

    data class State(
        val phase: Phase = Phase.HOME,
        val role: Role? = null,
        val code: TransferCode? = null,
        val percent: Int = 0,
        val message: Int = 0,
        val comparison: String? = null,
    )

    private val mutableState = MutableStateFlow(State())
    val state = mutableState.asStateFlow()
    private var operation: Job? = null
    private var approval: CompletableDeferred<Unit>? = null

    fun scan() {
        if (operation?.isCompleted != false) mutableState.value = State(Phase.SCAN, Role.RECEIVE)
    }

    fun scanFailed(message: Int) {
        if (operation?.isCompleted != false) {
            mutableState.value = State(Phase.ERROR, Role.RECEIVE, message = message)
        }
    }

    fun send(uid: Long) {
        start(Role.SEND) {
            val confirmation = CompletableDeferred<Unit>()
            approval = confirmation
            try {
                DeviceTransfer.send(
                    getApplication(), uid,
                    onPair = { show(Phase.PAIRING, Role.SEND, code = it) },
                    awaitApproval = {
                        show(Phase.APPROVE, Role.SEND, comparison = it)
                        confirmation.await()
                    },
                    onPreparing = { show(Phase.PREPARE, Role.SEND) },
                    onProgress = { show(Phase.TRANSFER, Role.SEND, percent = it) },
                    onRemoteRestore = { beginRestore(Phase.REMOTE_RESTORE, Role.SEND) },
                )
            } finally {
                approval = null
            }
        }
    }

    fun approve() {
        approval?.complete(Unit)
    }

    fun receive(value: String, uid: Long) {
        if (mutableState.value.phase != Phase.SCAN) return
        val code = TransferCode.decode(value)
        if (code == null) {
            scanFailed(R.string.transfer_error_code)
            return
        }
        start(Role.RECEIVE) {
            DeviceTransfer.receive(
                getApplication(), uid, code,
                onWaiting = { show(Phase.WAIT, Role.RECEIVE, comparison = it) },
                onPreparing = { show(Phase.PREPARE, Role.RECEIVE) },
                onProgress = { show(Phase.TRANSFER, Role.RECEIVE, percent = it) },
                onRestoring = { beginRestore(Phase.RESTORE, Role.RECEIVE) },
            )
        }
    }

    fun reset() {
        if (mutableState.value.phase in Phase.REMOTE_RESTORE..Phase.RESTORE) return
        if (operation?.isCompleted == false) {
            operation?.cancel()
        } else {
            mutableState.value = State()
        }
    }

    private fun start(role: Role, action: suspend () -> Unit) {
        if (operation?.isCompleted == false) return
        show(Phase.STARTING, role)
        operation = viewModelScope.launch {
            try {
                action()
                show(Phase.COMPLETE, role)
            } catch (_: TimeoutCancellationException) {
                currentCoroutineContext().ensureActive()
                show(Phase.ERROR, role, message = R.string.transfer_error_timeout)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                val message = when ((e as? DeviceTransfer.Failure)?.error) {
                    DeviceTransfer.Error.WIFI -> R.string.transfer_error_wifi
                    DeviceTransfer.Error.ACCOUNT -> R.string.transfer_error_account
                    DeviceTransfer.Error.EXPIRED -> R.string.transfer_error_timeout
                    DeviceTransfer.Error.RESTORE -> R.string.transfer_error_restore
                    DeviceTransfer.Error.STATUS_UNKNOWN -> R.string.transfer_error_status_unknown
                    else -> if (mutableState.value.phase == Phase.RESTORE) {
                        R.string.transfer_error_restore
                    } else {
                        R.string.transfer_error_connection
                    }
                }
                show(Phase.ERROR, role, message = message)
            } finally {
                // Cancellation can race an I/O callback. Publish a final home state after I/O stops.
                if (currentCoroutineContext()[Job]?.isCancelled == true) mutableState.value = State()
            }
        }
    }

    private suspend fun beginRestore(phase: Phase, role: Role) = withContext(Dispatchers.Main.immediate) {
        // Serialize entry into the non-cancellable phase with the UI's cancel action.
        show(phase, role)
    }

    private fun show(
        phase: Phase,
        role: Role,
        code: TransferCode? = null,
        percent: Int = 0,
        message: Int = 0,
        comparison: String? = null,
    ) {
        mutableState.value = State(phase, role, code, percent, message, comparison)
    }
}
