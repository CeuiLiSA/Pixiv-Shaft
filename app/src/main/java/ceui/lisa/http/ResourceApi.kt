package ceui.lisa.http

import okhttp3.ResponseBody
import retrofit2.http.GET
import retrofit2.http.Path

/** jsDelivr 上仓库 master 分支的静态资源（评论过滤规则、Markdown 文档）。 */
interface ResourceApi {

    @GET("gh/CeuiLiSA/Pixiv-Shaft@master/app/src/main/assets/comment.filter.rule.txt")
    suspend fun getCommentFilterRule(): ResponseBody

    @GET("$JSDELIVR_PROJECT_MASTER_PATH{path}")
    suspend fun getByPath(@Path("path") path: String): ResponseBody

    companion object {
        const val JSDELIVR_BASE_URL = "https://cdn.jsdelivr.net/"
        const val JSDELIVR_PROJECT_MASTER_PATH = "gh/CeuiLiSA/Pixiv-Shaft@master/"
    }
}
