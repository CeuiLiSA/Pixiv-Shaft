package ceui.lisa.update

import android.app.Application
import ceui.lisa.activities.Shaft
import ceui.lisa.http.GithubProxy
import ceui.lisa.utils.Settings
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 35], application = Application::class)
class AppUpdateCheckerTest {

    private val sampleAtomXml = """
        <?xml version="1.0" encoding="UTF-8"?>
        <feed xmlns="http://www.w3.org/2005/Atom">
          <id>tag:github.com,2008:https://github.com/CeuiLiSA/Pixiv-Shaft/releases</id>
          <entry>
            <id>tag:github.com,2008:Repository/178835117/v4.9.9</id>
            <updated>2026-10-08T07:07:59Z</updated>
            <link rel="alternate" type="text/html" href="https://github.com/CeuiLiSA/Pixiv-Shaft/releases/tag/v4.9.9"/>
            <title>v4.9.9</title>
            <content type="html">&lt;p&gt;v4.9.9 更新&lt;/p&gt;</content>
            <author><name>bot</name></author>
          </entry>
        </feed>
    """.trimIndent()

    private class FakeGitHubApi(
        var atomResponse: String = "",
        var atomError: Exception? = null,
        var apiRelease: GitHubRelease = GitHubRelease(
            tagName = "v4.9.8",
            name = "v4.9.8",
            body = "api note",
            publishedAt = "2026-10-08T00:00:00Z",
            htmlUrl = "https://example.invalid/v4.9.8",
            assets = emptyList()
        )
    ) : GitHubApi {
        var atomCallCount = 0
        var apiLatestCallCount = 0
        var apiReleasesCallCount = 0

        override suspend fun getReleasesAtom(owner: String, repo: String): ResponseBody {
            atomCallCount++
            atomError?.let { throw it }
            return atomResponse.toResponseBody("application/atom+xml".toMediaTypeOrNull())
        }

        override suspend fun getLatestRelease(owner: String, repo: String): GitHubRelease {
            apiLatestCallCount++
            return apiRelease
        }

        override suspend fun getReleases(
            owner: String,
            repo: String,
            perPage: Int,
            page: Int
        ): List<GitHubRelease> {
            apiReleasesCallCount++
            return listOf(apiRelease)
        }
    }

    @Test
    fun `when github proxy is enabled, always downgrade and skip rss atom`() = runBlocking {
        val fakeApi = FakeGitHubApi(atomResponse = sampleAtomXml)
        AppUpdateChecker.apiOverride = fakeApi

        val originalSettings = Shaft.sSettings
        try {
            // 配置并开启 GitHub 加速代理
            Shaft.sSettings = Settings().apply { githubProxy = "https://gh-proxy.com" }
            assertTrue(GithubProxy.isEnabled())

            // 1. 检查更新：始终降级走 API 端点，Atom 绝不调用
            val result = AppUpdateChecker.checkForUpdate()
            assertEquals(0, fakeApi.atomCallCount)
            assertEquals(1, fakeApi.apiLatestCallCount)
            assertTrue(result is AppUpdateChecker.UpdateResult.UpdateAvailable)
            assertEquals("v4.9.8", (result as AppUpdateChecker.UpdateResult.UpdateAvailable).release.tagName)

            // 2. 获取版本历史：始终降级走 API 端点，Atom 绝不调用
            val releases = AppUpdateChecker.fetchAllReleases()
            assertEquals(0, fakeApi.atomCallCount)
            assertEquals(1, fakeApi.apiReleasesCallCount)
            assertEquals(1, releases.size)
            assertEquals("v4.9.8", releases[0].tagName)
        } finally {
            Shaft.sSettings = originalSettings
            AppUpdateChecker.apiOverride = null
        }
    }

    @Test
    fun `when github proxy is not enabled, prioritize rss atom and fallback to api on failure`() = runBlocking {
        val fakeApi = FakeGitHubApi(atomResponse = sampleAtomXml)
        AppUpdateChecker.apiOverride = fakeApi

        val originalSettings = Shaft.sSettings
        try {
            // 未开启加速代理
            Shaft.sSettings = Settings().apply { githubProxy = "" }
            assertFalse(GithubProxy.isEnabled())

            // 1. 优先通过 RSS Atom 检查更新
            val result = AppUpdateChecker.checkForUpdate()
            assertEquals(1, fakeApi.atomCallCount)
            assertEquals(0, fakeApi.apiLatestCallCount)
            assertTrue(result is AppUpdateChecker.UpdateResult.UpdateAvailable)
            assertEquals("v4.9.9", (result as AppUpdateChecker.UpdateResult.UpdateAvailable).release.tagName)

            // 2. 优先通过 RSS Atom 获取全部历史
            val releases = AppUpdateChecker.fetchAllReleases()
            assertEquals(2, fakeApi.atomCallCount)
            assertEquals(0, fakeApi.apiReleasesCallCount)
            assertEquals(1, releases.size)
            assertEquals("v4.9.9", releases[0].tagName)

            // 3. Atom 接口异常时自动降级到 REST API
            fakeApi.atomError = IOException("Connect reset")
            fakeApi.atomCallCount = 0
            val fallbackUpdate = AppUpdateChecker.checkForUpdate()
            assertEquals(1, fakeApi.atomCallCount)
            assertEquals(1, fakeApi.apiLatestCallCount)
            assertTrue(fallbackUpdate is AppUpdateChecker.UpdateResult.UpdateAvailable)
            assertEquals("v4.9.8", (fallbackUpdate as AppUpdateChecker.UpdateResult.UpdateAvailable).release.tagName)

            val fallbackReleases = AppUpdateChecker.fetchAllReleases()
            assertEquals(2, fakeApi.atomCallCount)
            assertEquals(1, fakeApi.apiReleasesCallCount)
            assertEquals("v4.9.8", fallbackReleases[0].tagName)
        } finally {
            Shaft.sSettings = originalSettings
            AppUpdateChecker.apiOverride = null
        }
    }
}
