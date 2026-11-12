package ceui.pixiv.ui.artlist

import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.lisa.model.ListIllust
import ceui.pixiv.api.Client
import ceui.pixiv.api.model.Illust
import ceui.pixiv.session.SessionManager
import ceui.pixiv.utils.fetchFullIllustDetail
import ceui.pixiv.utils.isFullDetail
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import retrofit2.Response
import java.io.IOException

object ArtlistRepository {
    private val changes = MutableStateFlow(0L)
    val revision = changes.asStateFlow()
    @androidx.annotation.VisibleForTesting
    internal var apiOverride: ArtlistApi? = null
    private val api get() = apiOverride ?: Client.artlists
    val r18: Int get() = if (Shaft.sSettings.isR18FilterTempEnable) 0 else 2

    internal fun checkAccount(uid: Long) {
        if (uid != SessionManager.loggedInUid) throw IOException(message(R.string.artlist_account_changed))
    }

    internal suspend fun <T : Any> call(uid: Long, write: Boolean = false, block: suspend () -> Response<T>): T {
        checkAccount(uid)
        if (write && uid <= 0) throw IOException(message(R.string.artlist_login_required))
        val response = try { block() } catch (e: CancellationException) { throw e }
        catch (e: IOException) { throw IOException(message(R.string.artlist_network_error), e) }
        checkAccount(uid)
        if (!response.isSuccessful) {
            val code = runCatching { JsonParser.parseString(response.errorBody()?.string()).asJsonObject["error"].asString }.getOrNull()
            val resource = when {
                code == "uid_forbidden" -> R.string.artlist_account_changed
                response.code() == 401 -> R.string.artlist_login_required
                response.code() == 403 -> R.string.artlist_forbidden
                response.code() == 404 -> R.string.artlist_not_found
                response.code() == 429 -> R.string.artlist_busy_error
                code == "artlist_limit" || code == "artlist_work_limit" -> R.string.artlist_limit_error
                code == "artlist_work_unavailable" -> R.string.artlist_work_unavailable
                response.code() == 400 -> R.string.artlist_invalid_error
                else -> R.string.artlist_network_error
            }
            throw IOException(message(resource))
        }
        val result = response.body() ?: throw IOException(message(R.string.artlist_network_error))
        if (write) changes.value++
        return result
    }

    suspend fun lists(uid: Long, scope: String, before: Long?, workId: Long?) =
        call(uid) { api.lists(scope, before, r18, workId) }
    suspend fun detail(uid: Long, id: Long) = call(uid) { api.detail(id, r18) }
    suspend fun works(uid: Long, id: Long, cursor: String?): ListIllust = if (cursor == null) {
        call(uid) { api.works(id, r18) }
    } else {
        require(pageListId(cursor) == id) { "Invalid artlist cursor" }
        nextWorks(cursor, uid)
    }

    /** Only these URLs may use first-party credentials; never replay them through Pixiv OAuth. */
    fun pageListId(url: String): Long? {
        val parsed = url.toHttpUrlOrNull() ?: return null
        if (parsed.scheme != "https" || parsed.host != "pixshaft.com" || parsed.port != 443 ||
            parsed.username.isNotEmpty() || parsed.password.isNotEmpty() || parsed.fragment != null) return null
        val match = Regex("/v1/artlists/([1-9][0-9]*)/works").matchEntire(parsed.encodedPath) ?: return null
        return match.groupValues[1].toLongOrNull()
    }

    suspend fun nextWorks(url: String, uid: Long = SessionManager.loggedInUid): ListIllust {
        require(pageListId(url) != null) { "Invalid artlist cursor" }
        return call(uid) { api.nextWorks(url) }
    }

    suspend fun create(uid: Long, body: ArtlistCreate) = call(uid, true) { api.create(uid, body) }
    suspend fun edit(uid: Long, id: Long, body: ArtlistEdit) = call(uid, true) { api.edit(id, uid, r18, body) }
    suspend fun add(uid: Long, id: Long, illust: Illust): Artlist {
        checkAccount(uid)
        // The picker passes a small Bundle; restore all page URLs before publishing the work.
        val full = if (illust.isFullDetail()) illust else fetchFullIllustDetail(illust.id, toUpdate = false)
            ?: throw IOException(message(R.string.artlist_work_unavailable))
        return call(uid, true) { api.add(id, uid, r18, ArtlistAdd(full)) }
    }
    suspend fun remove(uid: Long, id: Long, workId: Long) = call(uid, true) { api.remove(id, workId, uid, r18) }
    suspend fun save(uid: Long, id: Long, saved: Boolean) = call(uid, true) {
        if (saved) api.save(id, uid, r18) else api.unsave(id, uid, r18)
    }
    suspend fun delete(uid: Long, id: Long) = call(uid, true) { api.delete(id, uid) }
    private fun message(id: Int) = Shaft.getContext().getString(id)
}
