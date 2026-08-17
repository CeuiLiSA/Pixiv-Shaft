package ceui.pixiv.ui.detail

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.content.res.AppCompatResources
import androidx.lifecycle.lifecycleScope
import ceui.lisa.R
import ceui.lisa.activities.TemplateActivity
import ceui.lisa.models.TagsBean
import ceui.lisa.utils.Common
import ceui.lisa.utils.Params
import ceui.lisa.utils.V3Palette
import ceui.loxia.Client
import ceui.loxia.CsrfTokenProvider
import ceui.loxia.ObjectPool
import ceui.loxia.WebResponse
import ceui.loxia.WorkEditableTag
import ceui.loxia.WorkTagEditRequest
import ceui.loxia.WorkTagsBody
import ceui.pixiv.chat.base.toUserMessage
import ceui.pixiv.session.SessionManager
import ceui.pixiv.utils.ppppx
import com.google.android.flexbox.AlignItems
import com.google.gson.Gson
import com.google.android.flexbox.FlexWrap
import com.google.android.flexbox.FlexboxLayout
import com.qmuiteam.qmui.skin.QMUISkinManager
import com.qmuiteam.qmui.widget.dialog.QMUIDialog
import com.qmuiteam.qmui.widget.dialog.QMUIDialogAction
import com.qmuiteam.qmui.widget.dialog.QMUIDialogView
import com.qmuiteam.qmui.widget.dialog.QMUITipDialog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import retrofit2.HttpException

/**
 * issue #1023: pixiv 的**社区标签**编辑 —— 网页版作品页标签行末尾那个「+」。
 *
 * 标签不是作者独占的:作者在投稿时勾了「公开让其他会员编辑标签」,任何登录会员都能给这个作品
 * 补标签;自己加的那条还能删回去(作者指定的标签谁都删不掉)。官方 App 没有这功能,只有网页有,
 * 接口是 `/ajax/tags/illust/{id}` 一组三条(读 / add / delete)——因此和
 * [ceui.pixiv.ui.user.PixivBlockOperate] 同样的三个前提:网页 cookie、x-csrf-token、
 * www.pixiv.net 直连可达。
 *
 * **不预取权限。** 「我能不能编辑这个作品的标签」只有网页那条接口知道,为它给每次打开详情页
 * 多发一个请求不划算(和拉黑态同一笔账),所以入口无条件显示,点开才查,查出来不可编辑再解释。
 *
 * V2([ceui.lisa.fragments.FragmentIllust])和 V3([ArtworkV3Fragment])两棵树共用本文件的入口。
 */
object PixivTagEditOperate {

    private fun Activity.isAlive(): Boolean = !isFinishing && !isDestroyed

    /** 请求在半空时窗口已销毁 —— dismiss 会抛拆窗异常,不 dismiss 又漏窗口。照常 dismiss,吞清理异常。 */
    private fun Dialog.safeDismiss() {
        runCatching { if (isShowing) dismiss() }
    }

    private fun tipDialog(activity: Activity, textRes: Int): QMUITipDialog =
        QMUITipDialog.Builder(activity)
            .setIconType(QMUITipDialog.Builder.ICON_TYPE_LOADING)
            .setTipWord(activity.getString(textRes))
            .create()

    /**
     * 标签区「编辑标签」入口。[onChanged] 在标签真的增删成功后回调,供调用方刷新自己那份 UI
     * ——池里的 [ceui.lisa.models.IllustsBean] 本方法已经就地更新过了(见 [applyToPool])。
     */
    fun showTagEditor(activity: AppCompatActivity, illustId: Long, onChanged: () -> Unit = {}) {
        if (!activity.isAlive()) return
        if (!SessionManager.hasWebCookie) {
            showWebLoginNeeded(activity)
            return
        }

        val loading = tipDialog(activity, R.string.work_tag_edit_loading)
        loading.show()

        activity.lifecycleScope.launch {
            // 失败只记下来、不在 catch 里弹窗:那会和还没收掉的 loading 叠一瞬。
            var failure: Throwable? = null
            val body = try {
                withContext(Dispatchers.IO) { loadTags(illustId) }
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
            val loaded = body ?: return@launch
            if (!loaded.writable) {
                showNotWritable(activity)
                return@launch
            }
            showEditorDialog(activity, illustId, loaded, onChanged)
        }
    }

    // ── 网络 ────────────────────────────────────────────────────────────

    private suspend fun loadTags(illustId: Long): WorkTagsBody {
        val response = Client.webApi.getIllustEditableTags(illustId)
        if (response.error == true) {
            throw RuntimeException(response.message.orEmpty().ifEmpty { "tags/illust failed" })
        }
        return response.body ?: throw RuntimeException("tags/illust empty body")
    }

    /**
     * csrf 失效时 pixiv 不回 200 + `error:true`,而是直接一个 HTTP 4xx,所以「换一份 token 重来」
     * 只能挂在 [HttpException] 上。
     *
     * **但这条接口的 400 不等于 csrf 失效**:标签已满 10 个、编辑过于频繁同样是 400(见
     * [withPixivMessage])。所以重试前**不能** [CsrfTokenProvider.clear] —— 这份 token 是拉黑、
     * Web 首页等功能共用的,而直连下 [CsrfTokenProvider.fetch] 未必抓得回来(Cloudflare 可能对裸
     * 请求下 JS challenge),清完抓不回就等于顺手把别人弄坏了。改成直接现抓一份覆盖:抓到就拿新的
     * 重试,抓不到则旧 token 原样保留,重试照发、失败照样能把 pixiv 的原话带回给用户。
     */
    private suspend fun editTag(illustId: Long, tagName: String, add: Boolean, retried: Boolean) {
        val csrf = CsrfTokenProvider.get()
            ?: CsrfTokenProvider.fetch()
            ?: throw RuntimeException("CSRF token 未就绪，请重新同步网页登录")

        val request = WorkTagEditRequest(tag = tagName)
        val response = try {
            if (add) {
                Client.webApi.addIllustTag(illustId, csrf, request)
            } else {
                Client.webApi.deleteIllustTag(illustId, csrf, request)
            }
        } catch (ex: HttpException) {
            if (!retried && (ex.code() == 403 || ex.code() == 400)) {
                // 现抓覆盖,不 clear:400 多半只是业务拒绝,见本函数 KDoc。
                CsrfTokenProvider.fetch()
                return editTag(illustId, tagName, add, retried = true)
            }
            throw if (ex.code() == 400) ex.withPixivMessage() else ex
        }
        if (response.error == true) {
            throw RuntimeException(response.message.orEmpty().ifEmpty { "tag edit failed" })
        }
    }

    /**
     * 这组接口把失败一律回成 **HTTP 400 + `{"error":true,"message":"…"}`**(实测:标签上限、
     * 编辑过于频繁、csrf 失效都是这一种),Retrofit 在解析前就抛了 [HttpException],那句人话就丢了,
     * 只剩通用的「The request was invalid.」。这里把 errorBody 里的 message 捞出来重新抛。
     *
     * 只对 400 这么做:401 / 403 要保住 [HttpException] 的身份,[reportFailure] 靠它把用户送去
     * 重新网页登录。而能走到 POST 说明 GET 刚刚才回过 writable=true —— 会话是活的,此时的 400
     * 基本都是业务性拒绝,不是登录问题。
     */
    private fun HttpException.withPixivMessage(): Throwable {
        val raw = runCatching { response()?.errorBody()?.string() }.getOrNull() ?: return this
        val message = runCatching {
            Gson().fromJson(raw, WebResponse::class.java)?.message
        }.getOrNull()
        return if (message.isNullOrBlank()) this else RuntimeException(message)
    }

    /**
     * 把编辑结果写回对象池,让 V2 / V3 两棵树各自的观察者自然重画。
     *
     * 译名优先沿用池里已有的那份:app-api 的译名跟着 app 语言走,而网页这条接口全仓统一
     * `lang=zh`(见 [ceui.loxia.PixivWebApi]),直接照抄会把非中文用户的译名冲成中文。
     * 只有新加进来的标签才用网页给的译名。
     */
    private fun applyToPool(illustId: Long, tags: List<WorkEditableTag>) {
        val illust = ObjectPool.getIllust(illustId).value ?: return
        val known = illust.tags.orEmpty().associate { it.name.orEmpty() to it.translated_name }
        illust.tags = tags.mapNotNull { web ->
            val name = web.tag?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            TagsBean().apply {
                this.name = name
                this.translated_name = known[name] ?: web.translatedName
            }
        }
        ObjectPool.updateIllust(illust)
    }

    // ── 编辑弹窗 ────────────────────────────────────────────────────────

    private fun showEditorDialog(
        activity: AppCompatActivity,
        illustId: Long,
        initial: WorkTagsBody,
        onChanged: () -> Unit,
    ) {
        val palette = V3Palette.from(activity)
        var busy = false

        val flow = FlexboxLayout(activity).apply {
            flexWrap = FlexWrap.WRAP
            alignItems = AlignItems.FLEX_START
        }
        val input = EditText(activity).apply {
            setHint(R.string.work_tag_edit_input_hint)
            inputType = InputType.TYPE_CLASS_TEXT
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_DONE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        }
        val addAction = TextView(activity).apply {
            setText(R.string.work_tag_edit_add)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(palette.textAccent)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(12.ppppx, 8.ppppx, 4.ppppx, 8.ppppx)
        }

        // 提交一次增删,成功后重新拉一遍列表 —— pixiv 会对标签名做归一化(全角/半角、
        // 同义跳转),照抄用户输入回填会和服务端实际存的对不上。
        //
        // 返回值 = 这一次提交有没有真的发出去。[busy] 期间挡掉的那次必须让调用方知道,否则
        // [commitInput] 会照样清空输入框,把用户刚打的标签静默吞掉:loading 提示框是可以按
        // 返回键关掉的,而 busy 要等请求回来才复位,这中间输入框是能点的。
        fun submit(tagName: String, add: Boolean): Boolean {
            if (busy || !activity.isAlive()) return false
            busy = true
            val loading = tipDialog(activity, R.string.work_tag_edit_submitting)
            loading.show()
            activity.lifecycleScope.launch {
                var failure: Throwable? = null
                val refreshed = try {
                    withContext(Dispatchers.IO) {
                        editTag(illustId, tagName, add, retried = false)
                        loadTags(illustId)
                    }
                } catch (ce: CancellationException) {
                    throw ce
                } catch (ex: Throwable) {
                    failure = ex
                    null
                } finally {
                    busy = false
                    loading.safeDismiss()
                }
                if (!activity.isAlive()) return@launch
                val err = failure
                if (err != null) {
                    reportFailure(activity, err)
                    return@launch
                }
                val body = refreshed ?: return@launch
                val tags = body.tags.orEmpty()
                renderChips(activity, flow, palette, tags) { name -> submit(name, add = false) }
                applyToPool(illustId, tags)
                onChanged()
                Common.showToast(
                    activity.getString(
                        if (add) R.string.work_tag_edit_added else R.string.work_tag_edit_deleted
                    )
                )
            }
            return true
        }

        fun commitInput() {
            val name = input.text?.toString()?.trim().orEmpty()
            if (name.isEmpty()) return
            // 提交被 busy 挡下时保留输入,让用户重按一次即可,而不是白打一遍。
            if (submit(name, add = true)) {
                input.text = null
            }
        }

        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                commitInput()
                true
            } else {
                false
            }
        }
        addAction.setOnClickListener { commitInput() }

        renderChips(activity, flow, palette, initial.tags.orEmpty()) { name ->
            submit(name, add = false)
        }

        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20.ppppx, 4.ppppx, 20.ppppx, 4.ppppx)
            addView(
                TextView(activity).apply {
                    setText(R.string.work_tag_edit_hint)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    setTextColor(palette.textSecondary)
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
            )
            addView(
                flow,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = 12.ppppx }
            )
            addView(
                LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(
                        input,
                        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    )
                    addView(
                        addAction,
                        LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                        )
                    )
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = 4.ppppx }
            )
        }

        object : QMUIDialog.CustomDialogBuilder(activity) {
            override fun onCreateContent(
                dialog: QMUIDialog,
                parent: QMUIDialogView,
                context: Context,
            ): View = container
        }
            .setTitle(R.string.work_tag_edit_title)
            .setSkinManager(QMUISkinManager.defaultInstance(activity))
            .addAction(R.string.sure) { dialog, _ -> dialog.dismiss() }
            .create()
            .show()
    }

    /**
     * chip 全量重建。数量最多 10 个(pixiv 每个作品的标签上限),不值得做增量。
     * 可删的那些带一个 `×` 且可点;作者指定 / 别人加的只展示,点了给一句解释而不是静默无反应。
     */
    private fun renderChips(
        activity: AppCompatActivity,
        flow: FlexboxLayout,
        palette: V3Palette,
        tags: List<WorkEditableTag>,
        onDelete: (tagName: String) -> Unit,
    ) {
        flow.removeAllViews()
        if (tags.isEmpty()) {
            flow.addView(
                TextView(activity).apply {
                    setText(R.string.work_tag_edit_empty)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    setTextColor(palette.textSecondary)
                }
            )
            return
        }

        val density = activity.resources.displayMetrics.density
        val chipBg = palette.tagLockedBg(999f * density).constantState
        tags.forEach { tag ->
            val name = tag.tag.orEmpty()
            if (name.isEmpty()) return@forEach
            val chip = TextView(activity).apply {
                text = buildString {
                    append("# ")
                    append(name)
                    tag.translatedName?.let { append("  "); append(it) }
                }
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTextColor(palette.textTag)
                background = chipBg?.newDrawable()?.mutate()
                if (tag.deletable) {
                    val close = AppCompatResources
                        .getDrawable(activity, R.drawable.ic_close_black_24dp)
                        ?.mutate()
                    close?.setBounds(0, 0, 14.ppppx, 14.ppppx)
                    close?.setTint(palette.textTag)
                    setCompoundDrawablesRelative(null, null, close, null)
                    compoundDrawablePadding = 4.ppppx
                } else {
                    // 不可删的整条压暗,让「哪些能动」一眼可辨。
                    alpha = 0.55f
                }
                setPaddingRelative(14.ppppx, 7.ppppx, if (tag.deletable) 10.ppppx else 14.ppppx, 7.ppppx)
                layoutParams = FlexboxLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply {
                    setMargins(0, 0, 8.ppppx, 8.ppppx)
                    flexShrink = 0f
                }
                setOnClickListener {
                    if (tag.deletable) {
                        confirmDelete(activity, name, onDelete)
                    } else {
                        Common.showToast(activity.getString(R.string.work_tag_edit_not_deletable))
                    }
                }
            }
            flow.addView(chip)
        }
    }

    private fun confirmDelete(
        activity: AppCompatActivity,
        name: String,
        onConfirm: (String) -> Unit,
    ) {
        QMUIDialog.MessageDialogBuilder(activity)
            .setTitle(R.string.work_tag_edit_title)
            .setMessage(activity.getString(R.string.work_tag_edit_delete_confirm, name))
            .setSkinManager(QMUISkinManager.defaultInstance(activity))
            .addAction(R.string.cancel) { dialog, _ -> dialog.dismiss() }
            .addAction(0, R.string.action_delete, QMUIDialogAction.ACTION_PROP_NEGATIVE) { dialog, _ ->
                dialog.dismiss()
                onConfirm(name)
            }
            .create()
            .show()
    }

    // ── 失败解释 ────────────────────────────────────────────────────────

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

    /** writable=false 的两种原因(作者没开放 / 网页未登录)服务端不区分,一并说清。 */
    private fun showNotWritable(activity: AppCompatActivity) {
        QMUIDialog.MessageDialogBuilder(activity)
            .setTitle(R.string.work_tag_edit_title)
            .setMessage(R.string.work_tag_edit_locked)
            .setSkinManager(QMUISkinManager.defaultInstance(activity))
            .addAction(R.string.sure) { dialog, _ -> dialog.dismiss() }
            .create()
            .show()
    }

    private fun showWebLoginNeeded(activity: Activity) {
        QMUIDialog.MessageDialogBuilder(activity)
            .setTitle(R.string.work_tag_edit_title)
            .setMessage(R.string.work_tag_edit_need_web_login)
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
