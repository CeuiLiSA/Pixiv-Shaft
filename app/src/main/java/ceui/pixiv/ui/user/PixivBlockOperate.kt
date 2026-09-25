package ceui.pixiv.ui.user

import android.app.Activity
import android.app.Dialog
import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import ceui.lisa.R
import ceui.lisa.activities.TemplateActivity
import ceui.lisa.utils.Common
import ceui.lisa.utils.Params
import ceui.lisa.utils.PixivOperate
import ceui.loxia.User
import ceui.pixiv.api.model.BlockSaveRequest
import ceui.pixiv.api.Client
import ceui.pixiv.api.CsrfTokenProvider
import ceui.pixiv.chat.base.toUserMessage
import ceui.pixiv.session.SessionManager
import ceui.pixiv.witstudio.dialog.WitDialog
import ceui.pixiv.witstudio.dialog.WitDialogAction
import ceui.pixiv.witstudio.dialog.WitDialogBuilder
import ceui.pixiv.witstudio.dialog.WitTipDialog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import retrofit2.HttpException
import ceui.pixiv.ui.navigation.TemplateRoute

/**
 * issue #959: pixiv 官方「拉黑」(网页端 ブロック)。
 *
 * **和「屏蔽」不是一回事**：屏蔽([ceui.lisa.utils.PixivOperate.muteUser])是纯本地过滤,只让自己
 * 看不见对方作品;拉黑是写到 pixiv 账号上的,对方从此无法关注 / 收藏 / 评论 / 私信你。所以这里必须
 * 真的打网络接口,而不是往本地库里塞一条。
 *
 * 官方 App 没有这个功能,只有网页端有,接口是 `/ajax/block/save`(POST,JSON)——因此:
 *  - 需要**网页 cookie**(PHPSESSID),没有就把用户引到「Web 首页」的网页登录流程去同步一次;
 *  - 需要 **x-csrf-token**,缺失时走 [CsrfTokenProvider.fetch] 现抓;
 *  - 需要 www.pixiv.net 通 —— 直连支持见 [ceui.lisa.http.CronetInterceptor] 的 host 映射,
 *    和 [ceui.pixiv.api.ClientManager.createWebAPIService] 里挂上的直连拦截器。
 *
 * issue #1162: 拉黑不影响 pixiv 的推荐流，对方作品照样会刷到,所以确认框里多给一个「拉黑并屏蔽其作品」,
 * 拉黑成功后顺手写一条本地屏蔽。拉黑(黑名单)普通会员也能加很多个,本地屏蔽不占 pixiv 的屏蔽(ミュート)名额,
 * 两者合并不会挤掉任何官方额度。
 *
 * V2([ceui.lisa.activities.UActivity])和 V3([ceui.lisa.activities.UserActivityV3])两棵树共用本
 * 文件的入口,不要各自复制一份。
 */
object PixivBlockOperate {

    private fun Activity.isAlive(): Boolean = !isFinishing && !isDestroyed

    /**
     * 请求飞在半空时 Activity 被销毁 → 协程取消 → 走到这里。此时窗口已经没了,dismiss 会抛
     * `IllegalArgumentException: View not attached to window manager`;但**不** dismiss 又会留下
     * 一条 WindowLeaked。所以照常 dismiss,只把这一类拆窗异常吞掉 —— 吞的是清理动作，不是业务错误。
     */
    private fun Dialog.safeDismiss() {
        runCatching { if (isShowing) dismiss() }
    }

    /**
     * 画师页「更多」菜单里的入口：先读当前拉黑态,再按状态弹确认框。
     *
     * 拉黑态不在进页面时预取 —— 那要给每次打开画师页多加一次网络请求,而这个功能是低频操作,
     * 点开菜单再查够用。
     *
     * @param isMuted 本地是否已屏蔽该作者;已屏蔽就不再提供「拉黑并屏蔽」。
     * @param onMuted 「拉黑并屏蔽」成功写入本地屏蔽后回调，宿主据此同步屏蔽开关和列表。
     */
    fun showBlockDialog(
        activity: AppCompatActivity,
        user: User,
        isMuted: Boolean,
        onMuted: () -> Unit,
    ) {
        if (!activity.isAlive()) return
        if (!SessionManager.hasWebCookie) {
            showWebLoginNeeded(activity)
            return
        }
        val userId = user.id
        // 名字空着时用 ID 兜,免得确认框读成「拉黑「」后…」。
        val name = user.name.orEmpty().ifBlank { userId.toString() }

        val loading = WitTipDialog.Builder(activity)
            .setTipWord(activity.getString(R.string.pixiv_block_checking))
            .create()
        loading.show()

        activity.lifecycleScope.launch {
            // 失败只记下来、不在 catch 里弹窗:那会和还没收掉的 loading 叠一瞬。
            var failure: Throwable? = null
            val isBlocked = try {
                withContext(Dispatchers.IO) { queryBlocked(userId) }
            } catch (ce: CancellationException) {
                throw ce
            } catch (ex: Throwable) {
                failure = ex
                null
            } finally {
                loading.safeDismiss()
            }
            if (!activity.isAlive()) return@launch
            val err = failure
            if (err != null) {
                reportFailure(activity, err)
                return@launch
            }
            if (isBlocked == null) return@launch
            showConfirm(activity, user, name, isBlocked, isMuted, onMuted)
        }
    }

    /**
     * 401 / 403 在这条链路上基本只有一个原因:网页 cookie 过期或失效。与其丢一句
     * 「没有权限执行此操作」让用户自己猜,不如直接把他送回网页登录 —— 那正是唯一的修法。
     */
    private fun reportFailure(activity: AppCompatActivity, ex: Throwable) {
        if (!activity.isAlive()) return
        when ((ex as? HttpException)?.code()) {
            401, 403 -> showWebLoginNeeded(activity)
            else -> Common.showToast(ex.toUserMessage(activity))
        }
    }

    private suspend fun queryBlocked(userId: Long): Boolean {
        val response = Client.webApi.getBlockList(targetId = userId)
        if (response.error == true) {
            throw RuntimeException(response.message.orEmpty().ifEmpty { "block/list failed" })
        }
        // target_id 查询下目标本人必在返回里(isTarget=true)。真拿不到时按「未拉黑」处理:
        // 用户点确认后接口会自己拒绝,总好过卡在这里什么都做不了。
        return response.body?.block_items
            ?.firstOrNull { it.isTarget || it.userId == userId.toString() }
            ?.isBlocked == true
    }

    private fun showConfirm(
        activity: AppCompatActivity,
        user: User,
        userName: String,
        isBlocked: Boolean,
        isMuted: Boolean,
        onMuted: () -> Unit,
    ) {
        val builder = WitDialog.MessageDialogBuilder(activity)
            .setTitle(R.string.pixiv_block_title)
            .setMessage(
                activity.getString(
                    if (isBlocked) R.string.pixiv_unblock_message else R.string.pixiv_block_message,
                    userName,
                )
            )
        if (!isBlocked && !isMuted) {
            // 三个按钮横排放不下，竖排时主操作放最上面。
            builder
                .setActionContainerOrientation(WitDialogBuilder.VERTICAL)
                .addAction(0, R.string.pixiv_block_and_mute_action, WitDialogAction.ACTION_PROP_POSITIVE) { dialog, _ ->
                    dialog.dismiss()
                    performSave(activity, user, userName, block = true, onMuted = onMuted)
                }
                .addAction(R.string.pixiv_block_action) { dialog, _ ->
                    dialog.dismiss()
                    performSave(activity, user, userName, block = true, onMuted = null)
                }
                .addAction(R.string.cancel) { dialog, _ -> dialog.dismiss() }
        } else {
            builder
                .addAction(R.string.cancel) { dialog, _ -> dialog.dismiss() }
                .addAction(
                    0,
                    if (isBlocked) R.string.pixiv_unblock_action else R.string.pixiv_block_action,
                    WitDialogAction.ACTION_PROP_POSITIVE,
                ) { dialog, _ ->
                    dialog.dismiss()
                    performSave(activity, user, userName, block = !isBlocked, onMuted = null)
                }
        }
        builder.create().show()
    }

    /** @param onMuted 非空表示「拉黑并屏蔽」:拉黑成功后再写本地屏蔽，并回调它。 */
    private fun performSave(
        activity: AppCompatActivity,
        user: User,
        userName: String,
        block: Boolean,
        onMuted: (() -> Unit)?,
    ) {
        val loading = WitTipDialog.Builder(activity)
            .setTipWord(activity.getString(R.string.pixiv_block_submitting))
            .create()
        loading.show()

        activity.lifecycleScope.launch {
            var failure: Throwable? = null
            try {
                withContext(Dispatchers.IO) {
                    saveBlock(user.id, block, retried = false)
                    // 拉黑一成功就紧跟着落本地屏蔽:放到 withContext 外面的话，页面在请求途中被关掉时
                    // lifecycleScope 已取消,withContext 返回即抛 CancellationException,这一条就丢了。
                    if (onMuted != null) PixivOperate.muteUser(user, false)
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (ex: Throwable) {
                failure = ex
            } finally {
                loading.safeDismiss()
            }
            val alsoMute = failure == null && onMuted != null
            if (!activity.isAlive()) return@launch
            val err = failure
            if (err != null) {
                reportFailure(activity, err)
                return@launch
            }
            if (alsoMute) onMuted?.invoke()
            Common.showToast(
                activity.getString(
                    when {
                        alsoMute -> R.string.pixiv_block_and_mute_done
                        block -> R.string.pixiv_block_done
                        else -> R.string.pixiv_unblock_done
                    },
                    userName,
                )
            )
        }
    }

    /**
     * csrf 失效时 pixiv 回的是 **HTTP 403**(不是 200 + `error:true`),所以清 token 重来只能挂在
     * [HttpException] 上。业务性失败(已拉黑 / 不能拉黑自己 …)才走 `error:true` —— 那种情况**绝不能**
     * 清 token:这份 token 是「Web 首页」等功能共用的,清掉等于顺手把别人也弄坏,而直连下
     * [CsrfTokenProvider.fetch] 未必抓得回来(Cloudflare 可能对裸请求下 JS challenge),
     * 只能重走一次网页登录。
     */
    private suspend fun saveBlock(userId: Long, block: Boolean, retried: Boolean) {
        val csrf = CsrfTokenProvider.get()
            ?: CsrfTokenProvider.fetch()
            ?: throw RuntimeException("CSRF token 未就绪，请重新同步网页登录")

        val response = try {
            Client.webApi.saveBlock(
                csrf,
                BlockSaveRequest(
                    user_id = userId.toString(),
                    action = if (block) "block" else "unblock",
                ),
            )
        } catch (ex: HttpException) {
            if (!retried && (ex.code() == 403 || ex.code() == 400)) {
                CsrfTokenProvider.clear()
                return saveBlock(userId, block, retried = true)
            }
            throw ex
        }
        if (response.error == true) {
            throw RuntimeException(response.message.orEmpty().ifEmpty { "block/save failed" })
        }
    }

    private fun showWebLoginNeeded(activity: Activity) {
        WitDialog.MessageDialogBuilder(activity)
            .setTitle(R.string.pixiv_block_title)
            .setMessage(R.string.pixiv_block_need_web_login)
            .addAction(R.string.cancel) { dialog, _ -> dialog.dismiss() }
            .addAction(0, R.string.street_web_login_confirm, WitDialogAction.ACTION_PROP_POSITIVE) { dialog, _ ->
                dialog.dismiss()
                activity.startActivity(
                    Intent(activity, TemplateActivity::class.java).apply {
                        putExtra(TemplateActivity.EXTRA_FRAGMENT, TemplateRoute.WEB_HOME.key)
                        putExtra(Params.AUTO_WEB_LOGIN, true)
                    }
                )
            }
            .create()
            .show()
    }
}
