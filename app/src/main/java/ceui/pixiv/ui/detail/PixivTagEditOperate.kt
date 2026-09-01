package ceui.pixiv.ui.detail

import ceui.loxia.Tag
import ceui.pixiv.api.Client
import ceui.pixiv.api.CsrfTokenProvider
import ceui.pixiv.api.model.WebResponse
import ceui.pixiv.api.model.WorkEditableTag
import ceui.pixiv.api.model.WorkTagEditRequest
import ceui.pixiv.api.model.WorkTagsBody
import ceui.pixiv.cache.ObjectPool
import ceui.pixiv.session.SessionManager
import com.google.gson.Gson
import retrofit2.HttpException

/**
 * issue #1023: pixiv 的**社区标签**编辑 —— 网页版作品页标签行末尾那个「+」的后端。
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
 * 本对象只管**网络与数据**,一个 View 都不碰;UI 全在 [TagEditSheet]。V2
 * ([ceui.lisa.fragments.FragmentIllust]) 和 V3 ([ArtworkV3Fragment]) 两棵树共用同一张 sheet。
 */
object PixivTagEditOperate {

    /** 没有网页 cookie 时连问都不用问 —— 这组接口全要登录态。 */
    val hasWebSession: Boolean get() = SessionManager.hasWebCookie

    /** 读作品当前的可编辑标签态。 */
    suspend fun loadTags(illustId: Long): WorkTagsBody {
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
    suspend fun editTag(illustId: Long, tagName: String, add: Boolean, retried: Boolean = false) {
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
     * 只对 400 这么做:401 / 403 要保住 [HttpException] 的身份,[isAuthFailure] 靠它把用户送去
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
     * 401 / 403 在这条链路上基本只有一个原因:网页 cookie 过期或失效 —— 调用方据此把用户送去
     * 重新网页登录,而不是丢一句「没有权限执行此操作」让他自己猜。
     */
    fun isAuthFailure(ex: Throwable): Boolean =
        (ex as? HttpException)?.code().let { it == 401 || it == 403 }

    /**
     * 把编辑结果写回对象池,让 V2 / V3 两棵树各自的观察者自然重画。
     *
     * 译名优先沿用池里已有的那份:app-api 的译名跟着 app 语言走,而网页这条接口全仓统一
     * `lang=zh`(见 [ceui.pixiv.api.PixivWebApi]),直接照抄会把非中文用户的译名冲成中文。
     * 只有新加进来的标签才用网页给的译名。
     */
    fun applyToPool(illustId: Long, tags: List<WorkEditableTag>) {
        val illust = ObjectPool.getIllust(illustId).value ?: return
        val known = illust.tags.orEmpty().associate { it.name.orEmpty() to it.translated_name }
        val newTags = tags.mapNotNull { web ->
            val name = web.tag?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            Tag(name = name, translated_name = known[name] ?: web.translatedName)
        }
        ObjectPool.updateIllust(illust.copy(tags = newTags))
    }
}
