package ceui.pixiv.ui.referral

import ceui.lisa.BuildConfig
import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.pixiv.api.Client
import ceui.pixiv.services.appServices
import ceui.pixiv.shaftapi.ReferralActionResponse
import ceui.pixiv.shaftapi.ReferralActivateReq
import ceui.pixiv.shaftapi.ReferralActivityReq
import ceui.pixiv.shaftapi.ReferralBindReq
import ceui.pixiv.shaftapi.ReferralClaimReq
import ceui.pixiv.shaftapi.ReferralSubmitReq
import com.google.gson.Gson
import kotlinx.coroutines.CancellationException
import retrofit2.Response
import java.io.IOException

/**
 * 推介计划的数据层。
 *
 * 两条铁律：
 *
 *  1. **每次写操作都用服务端回来的整页状态覆盖本地。** 服务端在每个写响应里都带回
 *     `state`，所以「领了一张卡」之后页面上的一切（卡包、进度、剩余可领）都是服务端
 *     说的，而不是本地推的。这一页对应的是真的 PRO 天数，本地推演一旦和服务端错开，
 *     用户看到的就是一个不存在的奖励。
 *  2. **拒绝要能被说清楚。** 服务端每种拒绝都有自己的 error 串，[ReferralFailure] 把它
 *     翻成一句话。笼统的「操作失败」会让一个「你不是新用户」的人一直重试。
 */
internal sealed interface ReferralResult<out T> {
    data class Success<T>(val value: T) : ReferralResult<T>
    data class Failure(val failure: ReferralFailure) : ReferralResult<Nothing>
}

/**
 * 一次失败。[messageRes] 是给用户看的那句话，[code] 留给需要分支的调用方
 * （比如 `higher_tier_active` 要额外说明卡片有效期已顺延）。
 */
internal data class ReferralFailure(
    val code: String?,
    val messageRes: Int,
    /** `higher_tier_active` 时服务端顺延之后的卡片有效期。 */
    val expiresAt: Long? = null,
    /**
     * 这次失败说明**服务端的状态和用户眼前这一屏已经不一样了**，页面得重新拉一次。
     *
     * 每一种 409 都属于这类：「已经领过了」「已经激活过了」「还没达到条件」——它们
     * 的共同含义就是「你以为的和我这里的对不上」。最典型的是 `higher_tier_active`：
     * 服务端**确实改了数据**（把卡的有效期顺延到 Max 之后）才拒绝的，不重拉的话
     * 弹窗说着「有效期已顺延」，后面那张卡上印的还是旧日期。
     */
    val stale: Boolean = false,
)

internal class ReferralRepository(
    private val api: () -> ceui.pixiv.shaftapi.PixshaftApi = { Client.pixshaft },
    private val updatePlan: (Long, ceui.pixiv.shaftapi.Nana7miPlan) -> Unit = { uid, plan ->
        Shaft.getContext().appServices().remoteAppConfig.updateNana7miPlan(uid, plan)
    },
) {

    /** 渠道随每条请求上报：Lite 不参加这个活动，而那道闸在服务端（见 [PixshaftApi]）。 */
    private val flavor: String get() = BuildConfig.FLAVOR

    suspend fun load(campaign: String? = null): ReferralResult<ReferralSnapshot> =
        call { api().referralState(flavor, campaign) }.map { snapshot(it) }

    suspend fun bind(code: String, campaign: String? = null, uid: Long? = null): ReferralResult<ReferralSnapshot> =
        action { api().referralBind(flavor, ReferralBindReq(code.trim().uppercase(), campaign, uid)) }

    suspend fun claim(task: ReferralTask, campaign: String? = null, uid: Long? = null): ReferralResult<ReferralSnapshot> =
        action { api().referralClaim(flavor, ReferralClaimReq(task.key, campaign, uid)) }

    suspend fun activate(cardId: Long, campaign: String? = null, uid: Long? = null): ReferralResult<ReferralSnapshot> =
        action { api().referralActivate(flavor, ReferralActivateReq(cardId, campaign, uid)) }

    suspend fun submit(task: ReferralTask, url: String, description: String, campaign: String? = null, uid: Long? = null): ReferralResult<ReferralSnapshot> =
        action { api().referralSubmit(flavor, ReferralSubmitReq(task.key, url.trim(), description.trim(), campaign, uid)) }

    /**
     * 「刚收藏了一次」。**静默**：它只是给达标判定补一个位，失败了不该打断用户正在做的事，
     * 也不该弹任何东西。下一次收藏会再报一遍。
     */
    suspend fun reportBookmark(uid: Long): Boolean = try {
        api().referralActivity(flavor, ReferralActivityReq(bookmarked = true, uid = uid)).isSuccessful
    } catch (ce: CancellationException) {
        throw ce
    } catch (_: Exception) {
        false
    }

    private suspend fun action(block: suspend () -> Response<ReferralActionResponse>): ReferralResult<ReferralSnapshot> =
        when (val result = call(block)) {
            is ReferralResult.Failure -> result
            is ReferralResult.Success -> result.value.state?.let { ReferralResult.Success(snapshot(it)) }
                ?: ReferralResult.Failure(ReferralFailure(null, R.string.referral_error_generic, stale = true))
        }

    private fun snapshot(response: ceui.pixiv.shaftapi.ReferralStateResponse): ReferralSnapshot {
        val uid = response.uid
        if (uid != null && uid > 0L) response.plan?.let { updatePlan(uid, it) }
        return response.toSnapshot()
    }

    private inline fun <T, R> ReferralResult<T>.map(transform: (T) -> R): ReferralResult<R> =
        when (this) {
            is ReferralResult.Success -> ReferralResult.Success(transform(value))
            is ReferralResult.Failure -> this
        }

    private suspend fun <T> call(block: suspend () -> Response<T>): ReferralResult<T> {
        val response = try {
            block()
        } catch (ce: CancellationException) {
            throw ce
        } catch (io: IOException) {
            return ReferralResult.Failure(ReferralFailure(null, R.string.referral_error_network))
        } catch (e: Exception) {
            return ReferralResult.Failure(ReferralFailure(null, R.string.referral_error_generic))
        }
        val body = response.body()
        if (response.isSuccessful && body != null) return ReferralResult.Success(body)
        return ReferralResult.Failure(failureOf(response))
    }

    private fun failureOf(response: Response<*>): ReferralFailure {
        // 401 单独说：Auth V2 的 token 还没拿到（刚装、刚登录、或刷新失败）。这不是
        // 「你不能参加活动」，而是「等一下再试」，两句话不能混。
        if (response.code() == 401) return ReferralFailure("unauthorized", R.string.referral_error_auth)
        if (response.code() == 429) return ReferralFailure("rate_limited", R.string.referral_error_busy)
        val parsed = runCatching {
            Gson().fromJson(response.errorBody()?.string().orEmpty(), ReferralActionResponse::class.java)
        }.getOrNull()
        val code = parsed?.error
        return ReferralFailure(
            code,
            messageFor(code),
            parsed?.expiresAt,
            stale = response.code() == 409,
        )
    }

    private fun messageFor(code: String?): Int = when (code) {
        "uid_forbidden" -> R.string.referral_error_auth
        "self_referral" -> R.string.referral_error_self
        "already_bound" -> R.string.referral_error_already_bound
        "unknown_code", "bad_code" -> R.string.referral_error_bad_code
        "not_new_user" -> R.string.referral_error_not_new
        "budget_exhausted" -> R.string.referral_error_budget
        "inviter_full" -> R.string.referral_error_inviter_full
        "referral_disabled", "task_disabled" -> R.string.referral_error_closed
        "not_ready" -> R.string.referral_error_not_ready
        "already_claimed" -> R.string.referral_error_claimed
        "higher_tier_active" -> R.string.referral_error_higher_tier
        "already_activated" -> R.string.referral_error_activated
        "reward_expired" -> R.string.referral_error_card_expired
        "unknown_reward" -> R.string.referral_error_card_missing
        "bad_url" -> R.string.referral_error_bad_url
        "bad_description" -> R.string.referral_error_bad_description
        "already_pending" -> R.string.referral_error_pending
        "already_approved" -> R.string.referral_error_approved
        else -> R.string.referral_error_generic
    }
}
