package ceui.pixiv.network

import ceui.pixiv.actions.Nana7miSearchTelemetry.ContentType
import ceui.pixiv.actions.Nana7miSearchTelemetry.Page
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import timber.log.Timber

/**
 * Counts the search requests a Premium member sends to Pixiv on their OWN account, so pixshaft can
 * add them to what the account is lent for: an account's load is everything Pixiv sees on it, no
 * matter whose hand was on it, and an owner who searches a lot should be lent less.
 *
 * Sits at the HTTP layer rather than in the search repos because that is the only place that sees
 * every request — first pages, pagination, the detail pager loading the next page, the novel
 * reader's related-illust lookup, network retries and the novel title fallback alike.
 *
 * Must run after [HeaderInterceptor] and before [TokenFetcherInterceptor]: borrowed-account
 * requests carry [HeaderInterceptor.EXPLICIT_AUTHORIZATION_HEADER] until TokenFetcher strips it,
 * and that marker is how they are told apart from the member's own requests.
 *
 * Only requests that got a response are reported — that is what Pixiv actually saw; a request that
 * never connected is not load. Reporting never changes the response or throws.
 */
class OwnSearchLoadInterceptor(
    private val ownerUid: () -> Long,
    private val isPremium: () -> Boolean,
    private val report: (uid: Long, contentType: ContentType, page: Page, httpStatus: Int) -> Unit,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val search = classify(request) ?: return chain.proceed(request)
        // Read before sending: the Authorization header was just injected from this same session,
        // so this is the account the request goes out on, even if the user switches mid-flight.
        val uid = ownerUid()
        val premium = isPremium()
        val response = chain.proceed(request)
        if (premium && uid > 0L) {
            try {
                report(uid, search.contentType, search.page, response.code)
            } catch (error: Exception) {
                Timber.tag(TAG).w(error, "own search request report failed")
            }
        }
        return response
    }

    internal data class OwnSearch(val contentType: ContentType, val page: Page)

    internal companion object {
        private const val TAG = "OwnSearchLoad"

        /** Pixiv search endpoints counted as load — the same unit as a lent search request. */
        private val SEARCH_PATHS = mapOf(
            "/v1/search/illust" to ContentType.ILLUST,
            "/v1/search/novel" to ContentType.NOVEL,
            "/v1/search/popular-preview/illust" to ContentType.ILLUST,
            "/v1/search/popular-preview/novel" to ContentType.NOVEL,
        )

        /** The search this request is on the member's own token, or null when it is not counted. */
        internal fun classify(request: Request): OwnSearch? {
            if (request.method != "GET") return null
            // Someone else's token: a borrowed account, already counted as that account's loan.
            if (request.header(HeaderInterceptor.EXPLICIT_AUTHORIZATION_HEADER) == "1") return null
            // Suffix, not equality: a self-hosted app-api proxy may hand back next_url under its own
            // path prefix, and those pages are still this account's searches.
            val path = request.url.encodedPath
            val contentType = SEARCH_PATHS.entries
                .firstOrNull { (suffix, _) -> path == suffix || path.endsWith(suffix) }
                ?.value ?: return null
            // Pixiv's next_url carries `offset`; a first page never does.
            val offset = request.url.queryParameter("offset")?.toIntOrNull() ?: 0
            return OwnSearch(contentType, if (offset > 0) Page.NEXT else Page.FIRST)
        }
    }
}
