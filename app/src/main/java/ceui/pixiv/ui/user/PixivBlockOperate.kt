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
import ceui.loxia.BlockSaveRequest
import ceui.loxia.Client
import ceui.loxia.CsrfTokenProvider
import ceui.pixiv.chat.base.toUserMessage
import ceui.pixiv.session.SessionManager
import com.qmuiteam.qmui.skin.QMUISkinManager
import com.qmuiteam.qmui.widget.dialog.QMUIDialog
import com.qmuiteam.qmui.widget.dialog.QMUIDialogAction
import com.qmuiteam.qmui.widget.dialog.QMUITipDialog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import retrofit2.HttpException

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
 *    和 [ceui.loxia.ClientManager.createWebAPIService] 里挂上的直连拦截器。
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
     */
    fun showBlockDialog(activity: AppCompatActivity, userId: Long, userName: String) {
        if (!activity.isAlive()) return
        if (!SessionManager.hasWebCookie) {
            showWebLoginNeeded(activity)
            return
        }
        // 名字空着时用 ID 兜,免得确认框读成「拉黑「」后…」。
        val name = userName.ifBlank { userId.toString() }

        val loading = QMUITipDialog.Builder(activity)
            .setIconType(QMUITipDialog.Builder.ICON_TYPE_LOADING)
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
            showConfirm(activity, userId, name, isBlocked)
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
        userId: Long,
        userName: String,
        isBlocked: Boolean,
    ) {
        QMUIDialog.MessageDialogBuilder(activity)
            .setTitle(R.string.pixiv_block_title)
            .setMessage(
                activity.getString(
                    if (isBlocked) R.string.pixiv_unblock_message else R.string.pixiv_block_message,
                    userName,
                )
            )
            .setSkinManager(QMUISkinManager.defaultInstance(activity))
            .addAction(R.string.cancel) { dialog, _ -> dialog.dismiss() }
            .addAction(
                0,
                if (isBlocked) R.string.pixiv_unblock_action else R.string.pixiv_block_action,
                QMUIDialogAction.ACTION_PROP_POSITIVE,
            ) { dialog, _ ->
                dialog.dismiss()
                performSave(activity, userId, userName, block = !isBlocked)
            }
            .create()
            .show()
    }

    private fun performSave(
        activity: AppCompatActivity,
        userId: Long,
        userName: String,
        block: Boolean,
    ) {
        val loading = QMUITipDialog.Builder(activity)
            .setIconType(QMUITipDialog.Builder.ICON_TYPE_LOADING)
            .setTipWord(activity.getString(R.string.pixiv_block_submitting))
            .create()
        loading.show()

        activity.lifecycleScope.launch {
            var failure: Throwable? = null
            try {
                withContext(Dispatchers.IO) { saveBlock(userId, block, retried = false) }
            } catch (ce: CancellationException) {
                throw ce
            } catch (ex: Throwable) {
                failure = ex
            } finally {
                loading.safeDismiss()
            }
            if (!activity.isAlive()) return@launch
            val err = failure
            if (err != null) {
                reportFailure(activity, err)
                return@launch
            }
            Common.showToast(
                activity.getString(
                    if (block) R.string.pixiv_block_done else R.string.pixiv_unblock_done,
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
        QMUIDialog.MessageDialogBuilder(activity)
            .setTitle(R.string.pixiv_block_title)
            .setMessage(R.string.pixiv_block_need_web_login)
            .setSkinManager(QMUISkinManager.defaultInstance(activity))
            .addAction(R.string.cancel) { dialog, _ -> dialog.dismiss() }
            .addAction(0, R.string.street_web_login_confirm, QMUIDialogAction.ACTION_PROP_POSITIVE) { dialog, _ ->
                dialog.dismiss()
                activity.startActivity(
                    Intent(activity, TemplateActivity::class.java).apply {
                        putExtra(TemplateActivity.EXTRA_FRAGMENT, "Web首页")
                        putExtra(Params.AUTO_WEB_LOGIN, true)
                    }
                )
            }
            .create()
            .show()
    }
}
