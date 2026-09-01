package ceui.pixiv.ui.account

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ceui.lisa.utils.Local
import ceui.pixiv.api.Client
import ceui.pixiv.api.model.AccountResponse
import ceui.pixiv.session.SessionManager
import ceui.pixiv.shaftapi.BindConfirmReq
import ceui.pixiv.shaftapi.EmailReq
import ceui.pixiv.shaftapi.RestoreConfirmReq
import ceui.pixiv.shaftapi.UidReq
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import retrofit2.HttpException
import timber.log.Timber

/**
 * Email-bound account backup / restore. Talks to pixshaft-api's account
 * endpoints (signed automatically by the OkHttp interceptor). This holds ALL of the
 * feature's behaviour — the fragment only renders [state] and reacts to
 * [effects]; it carries no business logic of its own.
 *
 *  - BACKUP  (logged-in): request code → confirm → upload the current
 *    [AccountResponse] (encrypted at rest server-side).
 *  - RESTORE (login page): request code → confirm → server returns the stored
 *    account JSON, which we persist exactly like a fresh OAuth login, then ask
 *    the UI to relaunch.
 */
class EmailBackupV3ViewModel(initialMode: Mode) : ViewModel() {

    enum class Mode { BACKUP, RESTORE }

    data class UiState(
        val mode: Mode,
        val email: String = "",
        val code: String = "",
        val codeSent: Boolean = false,
        val resendCountdown: Int = 0,
        val loading: Boolean = false,
        val status: String? = null,
        /** True when [status] describes a failure (rendered in red), false for info/success. */
        val statusIsError: Boolean = false,
        // BACKUP-only: the logged-in account already has a cloud backup. While
        // [checkingStatus] is true we're probing the server; once [bound] is true
        // the UI shows the "已备份" card instead of the backup/restore form.
        val checkingStatus: Boolean = false,
        val bound: Boolean = false,
        val boundEmail: String? = null, // masked, e.g. f***s@gmail.com
        val boundAt: Long = 0L,
    ) {
        val canRequestCode: Boolean
            get() = !loading && resendCountdown == 0 && isValidEmail(email)
        val canSubmit: Boolean
            get() = !loading && codeSent && code.length == CODE_LENGTH
    }

    sealed class Effect {
        data class Toast(val msg: String) : Effect()
        object Finish : Effect()        // backup done → close the page
        // restore done → pull cloud settings (ask to apply) like the web login, then relaunch
        data class RestoreLoggedIn(val uid: Long) : Effect()
        object HideKeyboard : Effect()  // any failure → drop the IME so the red error is visible
        object FocusCode : Effect()     // code sent → focus the code field and pop the numeric IME
    }

    private val gson = Gson()

    private val _state = MutableLiveData(UiState(mode = initialMode))
    val state: LiveData<UiState> = _state

    private val _effects = MutableSharedFlow<Effect>(extraBufferCapacity = 8)
    val effects: SharedFlow<Effect> = _effects.asSharedFlow()

    private var countdownJob: Job? = null

    init {
        if (initialMode == Mode.BACKUP) checkBoundStatus()
    }

    private fun cur() = _state.value!!
    private fun update(block: (UiState) -> UiState) {
        _state.value = block(cur())
    }

    /** Clears the spinner, shows [msg] as a failure (red), and drops the keyboard. */
    private fun showError(msg: String) {
        update { it.copy(loading = false, status = msg, statusIsError = true) }
        emit(Effect.HideKeyboard)
    }

    /** Clears the spinner and shows [msg] as neutral info/success. */
    private fun showInfo(msg: String) =
        update { it.copy(loading = false, status = msg, statusIsError = false) }

    fun setMode(mode: Mode) {
        if (cur().mode == mode) return
        countdownJob?.cancel()
        _state.value = UiState(mode = mode) // mode switch resets the whole flow
        if (mode == Mode.BACKUP) checkBoundStatus()
    }

    /** BACKUP only: ask the server whether this logged-in account is already backed up. */
    private fun checkBoundStatus() {
        val uid = SessionManager.loggedInUid
        if (uid <= 0L) return // not logged in → leave the empty form as-is
        update { it.copy(checkingStatus = true) }
        viewModelScope.launch {
            try {
                val st = Client.pixshaft.bindStatus(UidReq(uid))
                if (cur().mode != Mode.BACKUP) return@launch // mode switched mid-probe
                update {
                    it.copy(
                        checkingStatus = false,
                        bound = st.bound,
                        boundEmail = st.emailMasked,
                        boundAt = st.updatedAt,
                    )
                }
            } catch (e: Exception) {
                // Background probe; on failure fall back to the form silently.
                Timber.w(e, "bind status check failed")
                if (cur().mode != Mode.BACKUP) return@launch
                update { it.copy(checkingStatus = false, bound = false) }
            }
        }
    }

    /** Unbind: delete this account's cloud backup, then fall back to the empty form. */
    fun unbind() {
        val uid = SessionManager.loggedInUid
        if (uid <= 0L || cur().loading) return
        update { it.copy(loading = true, status = null, statusIsError = false) }
        viewModelScope.launch {
            try {
                Client.pixshaft.bindDelete(UidReq(uid))
                update { it.copy(loading = false, bound = false, boundEmail = null, boundAt = 0L) }
                emit(Effect.Toast("已解绑，云端备份已删除"))
            } catch (e: Exception) {
                // The bound card has no inline status row → surface failures as a toast.
                update { it.copy(loading = false) }
                emit(Effect.Toast(errorText(e)))
            }
        }
    }

    fun onEmailChanged(value: String) = update { it.copy(email = value.trim()) }

    fun onCodeChanged(value: String) {
        val code = value.trim()
        update { it.copy(code = code) }
        // 输满 6 位即自动收键盘并开始校验，省掉手动点「提交」（粘贴验证码同样触发）。
        // 失败后停在 6 位红字态，改一位再补满会自动重试；submit() 的 !loading 保证不会重复提交。
        if (code.length == CODE_LENGTH && cur().canSubmit) {
            emit(Effect.HideKeyboard)
            submit()
        }
    }

    fun requestCode() {
        val st = cur()
        if (!st.canRequestCode) return
        if (st.mode == Mode.BACKUP && !SessionManager.isLoggedIn) {
            emit(Effect.Toast("请先登录 Pixiv 账号后再备份"))
            return
        }
        update { it.copy(loading = true, status = null, statusIsError = false) }
        viewModelScope.launch {
            try {
                if (st.mode == Mode.BACKUP) {
                    Client.pixshaft.bindRequest(EmailReq(st.email))
                    onCodeSent(st.email)
                } else {
                    val ack = Client.pixshaft.restoreRequest(EmailReq(st.email))
                    if (ack.found) {
                        onCodeSent(st.email)
                    } else {
                        showError("该邮箱还没有账号备份")
                    }
                }
            } catch (e: Exception) {
                showError(errorText(e))
            }
        }
    }

    fun submit() {
        val st = cur()
        if (!st.canSubmit) return
        // 自动校验是无声触发的，给一句「正在校验…」配合 progress spinner 让用户有感知。
        update { it.copy(loading = true, status = "正在校验验证码…", statusIsError = false) }
        viewModelScope.launch {
            try {
                if (st.mode == Mode.BACKUP) doBackup(st) else doRestore(st)
            } catch (e: Exception) {
                showError(errorText(e))
            }
        }
    }

    private suspend fun doBackup(st: UiState) {
        val account = SessionManager.loggedInAccount.value
        if (account?.access_token == null) {
            showError("登录状态已失效，请重新登录")
            return
        }
        Client.pixshaft.bindConfirm(BindConfirmReq(st.email, st.code, account))
        // Clear loading before the one-shot effects so the UI never stays stuck
        // on the spinner if the page was backgrounded and missed Finish.
        update { it.copy(loading = false, status = null, statusIsError = false) }
        emit(Effect.Toast("已备份到 ${st.email}"))
        emit(Effect.Finish)
    }

    private suspend fun doRestore(st: UiState) {
        val resp = Client.pixshaft.restoreConfirm(RestoreConfirmReq(st.email, st.code))
        val account = resp.account
        // user 也要有：AccountResponse.user 是可空的，缺了就落不成一个能用的登录态，
        // 得在这里报错，而不是让 persistLogin 静默跳过、最后重启到一个未登录的主界面。
        if (account?.access_token == null || account.user == null) {
            showError("备份数据异常，无法恢复")
            return
        }
        withContext(Dispatchers.IO) { persistLogin(account) }
        showInfo("恢复成功")
        // Hand off to the same cloud-settings sync the OAuth login uses; it (or its
        // onComplete) drives the relaunch. No "正在重新登录" toast here — a settings
        // prompt may appear first.
        emit(Effect.RestoreLoggedIn(account.user?.id ?: 0L))
    }

    /**
     * Reconstruct a fully-logged-in state from a restored [AccountResponse],
     * mirroring the canonical OAuth path in OutWakeActivity: legacy prefs +
     * MMKV session + the account-switcher Room row.
     */
    private fun persistLogin(account: AccountResponse) {
        // saveUser internally calls postUpdateSession (postValue): we run on
        // Dispatchers.IO and setValue off the main thread would throw. It writes
        // MMKV synchronously first, so the upcoming Common.restart() reads it back.
        Local.persistLoggedInUser(account)
    }

    private fun onCodeSent(email: String) {
        update { it.copy(loading = false, codeSent = true, status = "验证码已发送至 $email", statusIsError = false) }
        startCountdown()
        // 验证码框此刻刚由 render() 显示出来，把焦点和数字键盘直接送过去，省一次点击。
        emit(Effect.FocusCode)
    }

    private fun startCountdown() {
        countdownJob?.cancel()
        countdownJob = viewModelScope.launch {
            for (n in RESEND_SECONDS downTo 1) {
                update { it.copy(resendCountdown = n) }
                delay(1000)
            }
            update { it.copy(resendCountdown = 0) }
        }
    }

    private fun emit(effect: Effect) {
        _effects.tryEmit(effect)
    }

    private fun errorText(e: Throwable): String {
        if (e is HttpException) {
            val code = e.code()
            val key = try {
                e.response()?.errorBody()?.string()?.let {
                    gson.fromJson(it, ErrorBody::class.java)?.error
                }
            } catch (_: Exception) {
                null
            }
            return when (key) {
                "bad_code" -> "验证码不正确"
                "expired" -> "验证码已过期，请重新获取"
                "too_many_attempts" -> "尝试次数过多，请重新获取验证码"
                "no_code" -> "请先获取验证码"
                "not_found" -> "该邮箱没有可恢复的备份"
                "bad_email" -> "邮箱格式不正确"
                "account_required" -> "登录状态异常，无法备份"
                "send_failed" -> "验证码发送失败，请稍后再试"
                "rate_limited" -> "操作过于频繁，请稍后再试"
                else -> if (code == 429) "操作过于频繁，请稍后再试" else "请求失败（$code）"
            }
        }
        Timber.w(e, "email-backup request failed")
        return "网络异常，请检查连接后重试"
    }

    private data class ErrorBody(val error: String? = null)
}

private const val RESEND_SECONDS = 60
private const val CODE_LENGTH = 6
private val EMAIL_RE = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")

private fun isValidEmail(e: String): Boolean =
    e.length in 3..254 && EMAIL_RE.matches(e)
