package ceui.lisa.update

import okhttp3.ResponseBody
import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.Path
import retrofit2.http.Query

interface GitHubApi {

    @GET("repos/{owner}/{repo}/releases/latest")
    suspend fun getLatestRelease(
        @Path("owner") owner: String,
        @Path("repo") repo: String
    ): GitHubRelease

    @GET("repos/{owner}/{repo}/releases")
    suspend fun getReleases(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Query("per_page") perPage: Int = 100,
        @Query("page") page: Int = 1
    ): List<GitHubRelease>

    @GET("repos/{owner}/{repo}/releases/tags/{tag}")
    suspend fun getReleaseByTag(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Path("tag") tag: String
    ): GitHubRelease

    @GET("https://github.com/{owner}/{repo}/releases.atom")
    @Headers("Accept: application/atom+xml, application/xml, text/xml; q=0.9, */*; q=0.8")
    suspend fun getReleasesAtom(
        @Path("owner") owner: String,
        @Path("repo") repo: String
    ): ResponseBody

    companion object {
        const val BASE_URL = "https://api.github.com/"
        const val OWNER = "CeuiLiSA"
        const val REPO = "Pixiv-Shaft"
    }
}
