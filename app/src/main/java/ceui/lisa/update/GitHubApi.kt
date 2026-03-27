package ceui.lisa.update

import io.reactivex.Observable
import retrofit2.http.GET
import retrofit2.http.Path

interface GitHubApi {

    @GET("repos/{owner}/{repo}/releases/latest")
    fun getLatestRelease(
        @Path("owner") owner: String,
        @Path("repo") repo: String
    ): Observable<GitHubRelease>

    companion object {
        const val BASE_URL = "https://api.github.com/"
        const val OWNER = "CeuiLiSA"
        const val REPO = "Pixiv-Shaft"
    }
}
