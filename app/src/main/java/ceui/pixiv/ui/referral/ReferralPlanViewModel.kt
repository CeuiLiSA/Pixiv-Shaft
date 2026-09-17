package ceui.pixiv.ui.referral

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ceui.lisa.R
import ceui.pixiv.session.SessionManager
import kotlinx.coroutines.launch

internal enum class ReferralTab { TASKS, WALLET }
internal enum class ReferralFilter { ALL, PROGRESS, READY }

internal data class ReferralUiState(
    val snapshot: ReferralSnapshot,
    val tab: ReferralTab,
    val filter: ReferralFilter,
    val darkOverride: Boolean?,
    val accentOverride: Int?,
    /** 首次加载还没回来。此时页面画骨架，而不是画一个「零奖励」的空活动。 */
    val loading: Boolean,
    /** 加载失败时那句话；成功后清掉。 */
    val error: ReferralFailure?,
)

/**
 * 推介页的状态持有者。
 *
 * 所有奖励判定都在服务端，这里只负责「什么时候去问」和「把答案发出去」。每个写操作的
 * 响应都带回整页状态，所以成功后直接覆盖，从不本地推演 —— 这一页对应的是真的 PRO
 * 天数，本地推演一旦和服务端错开，用户看到的就是一个不存在的奖励。
 *
 * tab / filter / 配色存在 [SavedStateHandle] 里（进程死掉也还在），快照不存：它是服务端
 * 的答案，重建时重新问一次即可，存下来只会在下次打开时先闪一帧过期数据。
 */
internal class ReferralPlanViewModel(private val saved: SavedStateHandle) : ViewModel() {
    private val repository = ReferralRepository()
    private var snapshot = ReferralSnapshot()
    private var loading = true
    private var error: ReferralFailure? = null
    private val mutable = MutableLiveData(current())
    val state: LiveData<ReferralUiState> = mutable
    val value: ReferralUiState get() = mutable.value!!

    init {
        refresh()
    }

    private fun current() = ReferralUiState(
        snapshot,
        enumValueOr(saved["tab"], ReferralTab.TASKS),
        enumValueOr(saved["filter"], ReferralFilter.ALL),
        saved["dark"], saved["accent"],
        loading, error,
    )

    private fun publish() {
        mutable.value = current()
    }

    /**
     * 应用结果。成功就用服务端的整页状态覆盖，失败**不动快照** —— 一次网络抖动不该把
     * 用户已经看到的进度抹掉。
     */
    private fun apply(result: ReferralResult<ReferralSnapshot>): ReferralFailure? {
        loading = false
        return when (result) {
            is ReferralResult.Success -> {
                snapshot = result.value
                error = null
                publish()
                null
            }
            is ReferralResult.Failure -> {
                error = result.failure
                publish()
                // 冲突类失败说明服务端那边已经变了（最典型的是 higher_tier_active：
                // 它是**改完卡的有效期之后**才拒绝的）。不重拉的话，弹窗说着「有效期
                // 已顺延」，后面那张卡上印的还是旧日期。
                if (result.failure.stale) refresh()
                result.failure
            }
        }
    }

    fun refresh() {
        // 没登录就别打这条路由：它只收 Auth V2 的 token，而未登录时拿不到 token，
        // 结果是一个 401 和一句「登录状态还没准备好，请稍后重试」—— 用户会照着它
        // 一直重试一件永远不会成的事。真正该说的是「先登录」。
        if (!SessionManager.isLoggedIn) {
            loading = false
            error = ReferralFailure(LOGIN_REQUIRED, R.string.referral_login_required)
            publish()
            return
        }
        viewModelScope.launch { apply(repository.load()) }
    }

    /** 下面四个都返回「失败原因，或 null 表示成功」，让弹窗能就地说出那一句话。 */
    suspend fun bind(code: String): ReferralFailure? = apply(repository.bind(code))
    suspend fun claim(task: ReferralTask): ReferralFailure? = apply(repository.claim(task))
    suspend fun activate(cardId: Long): ReferralFailure? = apply(repository.activate(cardId))
    suspend fun submit(task: ReferralTask, url: String, description: String): ReferralFailure? =
        apply(repository.submit(task, url, description))

    fun tab(tab: ReferralTab) { saved["tab"] = tab.name; publish() }
    fun filter(filter: ReferralFilter) { saved["filter"] = filter.name; publish() }
    fun appearance(dark: Boolean?, accent: Int?) { saved["dark"] = dark; saved["accent"] = accent; publish() }
    /** 重绘用：卡片有效期倒计时按当前时间算，回到前台时要重算一次。 */
    fun refreshTime() = publish()

    companion object {
        /** 「没登录」不是一次可以重试的失败 —— 页面据此不画重试按钮。 */
        const val LOGIN_REQUIRED = "login_required"

        private inline fun <reified T : Enum<T>> enumValueOr(name: String?, fallback: T): T =
            enumValues<T>().firstOrNull { it.name == name } ?: fallback
    }
}
