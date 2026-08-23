package ceui.pixiv.push

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.browser.customtabs.CustomTabsIntent
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import ceui.lisa.BuildConfig
import ceui.lisa.R
import ceui.lisa.utils.Common
import ceui.loxia.Client
import ceui.loxia.InAppPush
import ceui.loxia.acknowledgeInAppPush
import ceui.pixiv.session.SessionManager
import ceui.pixiv.widgets.RateAppDialog
import ceui.pixiv.witstudio.dialog.WitDialog
import ceui.pixiv.witstudio.dialog.WitDialogAction
import com.tencent.mmkv.MMKV
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicBoolean

/** `/v1/config` 捎回来的一条推送，连同它是给哪个 uid 的。 */
data class InAppPushArrival(val uid: Long, val push: InAppPush)

/**
 * 应用内推送：后台写一条公告，付费用户下次打开 app 时看到**一次**，之后不再打扰。
 *
 * 没有推送通道（没有 FCM、没有长连接），「推」其实是搭冷启动那一次 `GET /v1/config` 的车：
 * 服务端只给**没回执过的、最新的一条**，这里弹一个 [WitDialog]，用户关掉（点按钮 / 返回 /
 * 点外面）那一刻：
 *
 *  1. 本地 MMKV 记下 `uid:id` 已看 —— 哪怕回执发不出去、服务端下次又把它给回来，这台设备
 *     也不再弹；
 *  2. 发 `POST /v1/push/ack` —— 服务端记下这个**账号**看过了，换设备、重装都不再下发。
 *     没发成就留在「待回执」名单里，下次配置到达时补发。
 *
 * 「看到一次」是按关掉算、不是按弹出算：转屏把 Activity 重建、或者进程被杀时框还开着，
 * 都不算看过，下次会原样再弹 —— 要保证的是「确实看到了」，要避免的是「看完了还反复提」。
 *
 * 只在 [MainActivity][ceui.lisa.activities.MainActivity] 上弹：那是登录后的第一屏，而推送
 * 只发给签了名的登录 uid。Lite 包和 Free 用户一样在服务端就拿不到推送（Lite 连 plan 都没有），
 * 客户端再拦一道只是保险。
 */
object InAppPushCenter {

    private const val TAG = "InAppPushCenter"
    private const val MMKV_ID = "inapp-push-v1"
    private const val KEY_SEEN = "seen:"
    private const val KEY_UNACKED = "unacked:"

    /** 评分框占着时最多等这么多轮（×[YIELD_INTERVAL_MS]）；之后放弃，留给下次冷启动。 */
    private const val YIELD_MAX_ROUNDS = 30
    private const val YIELD_INTERVAL_MS = 2_000L

    private val store: MMKV by lazy { MMKV.mmkvWithID(MMKV_ID) }
    private val main = Handler(Looper.getMainLooper())
    private val flushing = AtomicBoolean(false)
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO +
                CoroutineExceptionHandler { _, error ->
                    Timber.tag(TAG).e(error, "push worker crashed")
                },
    )

    /** 正在展示的那一个（只允许一个）。Activity 销毁时跟着清掉，不然会永远「正在展示」。 */
    private class Showing(val key: String, val dialog: WitDialog)

    @Volatile
    private var showing: Showing? = null

    /** 有推送框正在显示。首页那 2 秒后的评分框靠它让路。 */
    @JvmStatic
    fun isShowing(): Boolean = showing != null

    /**
     * 配置落地时调用（MainActivity 观察 [ceui.pixiv.config.RemoteAppConfig.inAppPushLive]）。
     * [arrival] 为 null 时只是顺手补发积压的回执。主线程。
     */
    @JvmStatic
    fun onConfigArrived(activity: FragmentActivity, arrival: InAppPushArrival?) {
        if (BuildConfig.IS_LITE) return
        val uid = SessionManager.loggedInUid
        if (uid <= 0L) return
        flushPendingAcks(uid)
        // 切号窗口：拉取是按旧 uid 发的，回来时已经是另一个人了，不能把给 A 的公告弹给 B。
        val push = arrival?.takeIf { it.uid == uid }?.push ?: return
        maybeShow(activity, uid, push, round = 0)
    }

    private fun maybeShow(activity: FragmentActivity, uid: Long, push: InAppPush, round: Int) {
        val id = push.id ?: return
        if (id <= 0L) return
        if (push.title.isNullOrBlank() && push.body.isNullOrBlank()) return
        val key = "$uid:$id"
        if (isSeen(key)) return
        if (showing != null) return
        if (activity.isFinishing || activity.isDestroyed) return
        if (SessionManager.loggedInUid != uid) return

        // 评分框先到了就等它走：两个框叠着谁都看不清。等不到就算了，服务端下次冷启动还会给。
        // 弱引用：这条轮询最长挂一分钟，不能让一个已经转屏销毁的 Activity 被 Handler 拖着不放。
        if (RateAppDialog.isShowing(activity.supportFragmentManager)) {
            if (round < YIELD_MAX_ROUNDS) {
                val ref = WeakReference(activity)
                main.postDelayed({ ref.get()?.let { maybeShow(it, uid, push, round + 1) } }, YIELD_INTERVAL_MS)
            }
            return
        }

        val dialog = try {
            buildDialog(activity, push)
        } catch (t: Throwable) {
            Timber.tag(TAG).w(t, "build dialog failed id=%d", id)
            return
        }
        val current = Showing(key, dialog)
        showing = current
        // 转屏 / 被系统回收：框跟着 Activity 没了，但没经过 dismiss 回调。这里把「正在展示」
        // 清掉（不算看过），否则 showing 卡住，之后永远不弹。先置空再 dismiss，让下面那个
        // dismiss 回调认不出它，从而不去记已看。
        activity.lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                owner.lifecycle.removeObserver(this)
                if (showing === current) {
                    showing = null
                    runCatching { dialog.dismiss() }
                }
            }
        })
        dialog.setOnDismissListener {
            if (showing === current) {
                showing = null
                markSeen(uid, id)
            }
        }
        try {
            dialog.show()
            Timber.tag(TAG).i("showing in-app push uid=%d id=%d", uid, id)
        } catch (t: Throwable) {
            // 窗口 token 没了之类 —— 当没弹过，下次再说。
            showing = null
            Timber.tag(TAG).w(t, "show failed id=%d", id)
        }
    }

    private fun buildDialog(activity: FragmentActivity, push: InAppPush): WitDialog {
        val builder = WitDialog.MessageDialogBuilder(activity)
            .setTitle(push.title?.takeIf { it.isNotBlank() } ?: activity.getString(R.string.shaft_hint))
            .setMessage(push.body?.takeIf { it.isNotBlank() })
        val actionUrl = push.actionUrl?.trim()?.takeIf { it.isNotEmpty() }
        if (actionUrl == null) {
            builder.addAction(
                0,
                activity.getString(R.string.inapp_push_dismiss),
                WitDialogAction.ACTION_PROP_POSITIVE,
            ) { d, _ -> d.dismiss() }
        } else {
            builder.addAction(activity.getString(R.string.inapp_push_dismiss)) { d, _ -> d.dismiss() }
            builder.addAction(
                0,
                push.actionLabel?.takeIf { it.isNotBlank() }
                    ?: activity.getString(R.string.inapp_push_default_action),
                WitDialogAction.ACTION_PROP_POSITIVE,
            ) { d, _ ->
                d.dismiss()
                openAction(activity, actionUrl)
            }
        }
        return builder.create()
    }

    /**
     * 按钮跳转。先试 app 自己（pixiv.net 作品/用户链接、`pixiv://`、`shaftintent://` 都有
     * intent-filter），app 接不住的 http(s) 再用 Custom Tab 开 —— 和详情页「打开链接」同一条路。
     */
    private fun openAction(activity: FragmentActivity, url: String) {
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return
        val own = Intent(Intent.ACTION_VIEW, uri).setPackage(activity.packageName)
        try {
            activity.startActivity(own)
            return
        } catch (_: ActivityNotFoundException) {
            // 自己接不住，往下走
        } catch (t: Throwable) {
            Timber.tag(TAG).w(t, "in-app open failed %s", url)
        }
        val scheme = uri.scheme?.lowercase()
        if (scheme == "http" || scheme == "https") {
            try {
                CustomTabsIntent.Builder().build().launchUrl(activity, uri)
                return
            } catch (_: ActivityNotFoundException) {
                Common.showToast("未找到浏览器")
                return
            }
        }
        try {
            activity.startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (_: ActivityNotFoundException) {
            Common.showToast("没有应用能打开这个链接")
        }
    }

    // ── 已看 / 回执 ────────────────────────────────────────────────────────

    private fun isSeen(key: String): Boolean =
        runCatching { store.getBoolean(KEY_SEEN + key, false) }.getOrDefault(false)

    /** 先落本地（同步、不可逆），再发回执。两步顺序不能反：回执可能发不出去，本地不能漏。 */
    private fun markSeen(uid: Long, id: Long) {
        val key = "$uid:$id"
        runCatching {
            store.putBoolean(KEY_SEEN + key, true)
            store.putBoolean(KEY_UNACKED + key, true)
        }
        Timber.tag(TAG).i("in-app push dismissed uid=%d id=%d", uid, id)
        scope.launch { sendAck(uid, id) }
    }

    private suspend fun sendAck(uid: Long, id: Long) {
        val settled = Client.pixshaft.acknowledgeInAppPush(uid, id)
        if (settled) {
            runCatching { store.removeValueForKey(KEY_UNACKED + "$uid:$id") }
            Timber.tag(TAG).i("in-app push acked uid=%d id=%d", uid, id)
        } else {
            Timber.tag(TAG).w("in-app push ack deferred uid=%d id=%d", uid, id)
        }
    }

    /** 把这个 uid 上次没发出去的回执补上。一次只跑一个，失败的留着下次。 */
    private fun flushPendingAcks(uid: Long) {
        val prefix = KEY_UNACKED + "$uid:"
        val pending = runCatching {
            store.allKeys().orEmpty()
                .filter { it.startsWith(prefix) }
                .mapNotNull { it.removePrefix(prefix).toLongOrNull() }
        }.getOrDefault(emptyList())
        if (pending.isEmpty()) return
        if (!flushing.compareAndSet(false, true)) return
        scope.launch {
            try {
                for (id in pending) sendAck(uid, id)
            } finally {
                flushing.set(false)
            }
        }
    }
}
