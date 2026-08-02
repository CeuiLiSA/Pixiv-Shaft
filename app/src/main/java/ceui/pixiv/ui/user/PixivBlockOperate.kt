package ceui.pixiv.ui.user

import android.app.Activity
import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import ceui.lisa.R
import ceui.lisa.activities.TemplateActivity
import ceui.lisa.utils.Common
import ceui.loxia.BlockSaveRequest
import ceui.loxia.Client
import ceui.loxia.CsrfTokenProvider
import ceui.pixiv.chat.base.toUserMessage
import ceui.pixiv.session.SessionManager
import com.qmuiteam.qmui.skin.QMUISkinManager
import com.qmuiteam.qmui.widget.dialog.QMUIDialog
import com.qmuiteam.qmui.widget.dialog.QMUIDialogAction
import com.qmuiteam.qmui.widget.dialog.QMUITipDialog
import com.tencent.mmkv.MMKV
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

    private fun hasWebCookie(): Boolean {
        val cookie = MMKV.defaultMMKV().getString(SessionManager.COOKIE_KEY, "")
        return cookie?.contains("PHPSESSID") == true
    }

    /**
     * 画师页「更多」菜单里的入口：先读当前拉黑态,再按状态弹确认框。
     *
     * 拉黑态不在进页面时预取 —— 那要给每次打开画师页多加一次网络请求,而这个功能是低频操作,
     * 点开菜单再查够用。
     */
    fun showBlockDialog(activity: AppCompatActivity, userId: Long, userName: String) {
        if (!activity.isAlive()) return
        if (!hasWebCookie()) {
            showWebLoginNeeded(activity)
            return
        }

        val loading = QMUITipDialog.Builder(activity)
            .setIconType(QMUITipDialog.Builder.ICON_TYPE_LOADING)
            .setTipWord(activity.getString(R.string.pixiv_block_checking))
            .create()
        loading.show()

        activity.lifecycleScope.launch {
            val isBlocked = try {
                withContext(Dispatchers.IO) { queryBlocked(userId) }
            } catch (ce: CancellationException) {
                throw ce
            } catch (ex: Throwable) {
                if (activity.isAlive()) Common.showToast(ex.toUserMessage(activity))
                null
            } finally {
                if (loading.isShowing && activity.isAlive()) loading.dismiss()
            }
            if (isBlocked == null || !activity.isAlive()) return@launch
            showConfirm(activity, userId, userName, isBlocked)
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
            val ok = try {
                withContext(Dispatchers.IO) { saveBlock(userId, block, retried = false) }
                true
            } catch (ce: CancellationException) {
                throw ce
            } catch (ex: Throwable) {
                if (activity.isAlive()) Common.showToast(ex.toUserMessage(activity))
                false
            } finally {
                if (loading.isShowing && activity.isAlive()) loading.dismiss()
            }
            if (!ok || !activity.isAlive()) return@launch
            Common.showToast(
                activity.getString(
                    if (block) R.string.pixiv_block_done else R.string.pixiv_unblock_done,
                    userName,
                )
            )
        }
    }

    /** token 过期时清缓存重抓一次再试(对齐 StreetMainViewModel.callApi 的做法)。 */
    private suspend fun saveBlock(userId: Long, block: Boolean, retried: Boolean) {
        val csrf = CsrfTokenProvider.get()
            ?: CsrfTokenProvider.fetch()
            ?: throw RuntimeException("CSRF token 未就绪，请重新同步网页登录")

        val response = Client.webApi.saveBlock(
            csrf,
            BlockSaveRequest(user_id = userId.toString(), action = if (block) "block" else "unblock"),
        )
        if (response.error == true) {
            if (!retried) {
                CsrfTokenProvider.clear()
                return saveBlock(userId, block, retried = true)
            }
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
                    }
                )
            }
            .create()
            .show()
    }
}
