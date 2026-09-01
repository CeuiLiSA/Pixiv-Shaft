package ceui.pixiv.ui.usage

import android.content.Context
import android.content.Intent
import androidx.fragment.app.Fragment
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import ceui.lisa.R
import ceui.lisa.activities.TemplateActivity
import ceui.pixiv.shaftapi.Nana7miResult
import com.google.android.material.snackbar.Snackbar
import ceui.pixiv.ui.navigation.TemplateRoute

/**
 * 「热度排序额度用完了」的一次性提示。
 *
 * 撞额度时搜索**不会失败** —— 仓库会静默降级到热度预览，用户照样拿到结果，只是结果变差了
 * 而他不知道为什么。这个通道就是把那个「为什么」送到 UI 层。
 *
 * 几条刻意的规矩：
 *
 *  - **只报配额，不报限流**（[Nana7miResult.RateLimited.isQuota]）。每分钟限流器最多等 60 秒，
 *    等一下就好，为它弹提示纯属打扰。
 *  - **同一只桶只报一次**。桶的身份是 `scope + resetsAt`；不去重的话，用户每翻一页、每换一个
 *    关键词都会再弹一次 —— 线上真有设备九分钟里撞了 23 次。
 *  - **算不出「多久之后恢复」就不报**。一句「额度用完了」而不说什么时候好，只会让人反复重试。
 *  - **不自动跳页**。用户刚搜完正在看结果，把他弹走到另一个页面是抢方向盘；跳转挂在
 *    Snackbar 的按钮上，他想看才看。
 *
 * 仓库层只管 [report]，跳哪个页面是 UI 层的事 —— 数据层不碰导航。
 */
object Nana7miQuotaNotice {

    data class Notice(
        /** `uid_5h` / `uid_weekly`，决定提示文案说的是哪一档。 */
        val scope: String,
        /** 整份额度回满还有多久（毫秒）。一定为正，算不出来就不会走到这里。 */
        val resetInMs: Long,
    )

    private val _pending = MutableLiveData<Notice?>()
    val pending: LiveData<Notice?> = _pending

    private var announcedBucket: String? = null

    /** 借号被配额拒了。可以从任意线程调用（仓库在 IO 线程上）。 */
    fun report(result: Nana7miResult.RateLimited) {
        if (!result.isQuota) return
        val scope = result.scope ?: return
        val bucket = "$scope:${result.resetsAt ?: 0L}"
        if (bucket == announcedBucket) return

        // resetsAt 是服务端时刻，必须减服务端的 serverTime，不能减本机时钟；两者都缺时退回
        // Retry-After 的秒数。
        val resetInMs = when {
            result.resetsAt != null && result.serverTime != null ->
                result.resetsAt - result.serverTime
            result.retryAfterSeconds != null -> result.retryAfterSeconds * 1000L
            else -> return
        }
        if (resetInMs <= 0L) return

        announcedBucket = bucket
        _pending.postValue(Notice(scope, resetInMs))
    }

    /** UI 展示后调用，避免下一个订阅者又弹一次。只能在主线程调。 */
    fun consume() {
        _pending.value = null
    }
}

/**
 * 「还有多久」的人话，和用量页共用同一批字符串，免得两处对同一段时间说法不一样。
 */
object Nana7miQuotaFormat {

    fun duration(context: Context, ms: Long): String {
        val totalMinutes = (ms + 59_999L) / 60_000L // 向上取整：别显示「0 分后恢复」
        val days = totalMinutes / (24 * 60)
        val hours = (totalMinutes % (24 * 60)) / 60
        val minutes = totalMinutes % 60
        return when {
            days > 0L -> context.getString(R.string.nana7mi_usage_dur_dh, days.toInt(), hours.toInt())
            hours > 0L -> context.getString(R.string.nana7mi_usage_dur_hm, hours.toInt(), minutes.toInt())
            else -> context.getString(R.string.nana7mi_usage_dur_m, minutes.toInt())
        }
    }
}

/**
 * 在搜索结果页显示额度提示。放在 Fragment 扩展里而不是各页各写一遍：插画和小说两个 tab
 * 撞的是同一份额度，提示也该长得一样。
 */
fun Fragment.observeNana7miQuotaNotice() {
    Nana7miQuotaNotice.pending.observe(viewLifecycleOwner) { notice ->
        val root = view ?: return@observe
        if (notice == null) return@observe
        Nana7miQuotaNotice.consume()

        val duration = Nana7miQuotaFormat.duration(requireContext(), notice.resetInMs)
        val message = if (notice.scope == "uid_weekly") {
            getString(R.string.nana7mi_quota_snack_weekly, duration)
        } else {
            getString(R.string.nana7mi_quota_snack_session, duration)
        }
        // 12 秒：这条要读完一句话再点按钮，LENGTH_LONG 的 3.5 秒不够；又不用 INDEFINITE ——
        // 那会一直压着结果页底部，用户不点就不走。
        Snackbar.make(root, message, SNACK_DURATION_MS)
            .setAction(R.string.nana7mi_quota_snack_action) {
                startActivity(
                    Intent(requireContext(), TemplateActivity::class.java)
                        .putExtra(TemplateActivity.EXTRA_FRAGMENT, TemplateRoute.NANA7MI_USAGE.key),
                )
            }
            .show()
    }
}

private const val SNACK_DURATION_MS = 12_000
