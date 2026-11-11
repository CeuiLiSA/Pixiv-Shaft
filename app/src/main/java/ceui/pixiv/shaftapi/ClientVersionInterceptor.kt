package ceui.pixiv.shaftapi

import ceui.lisa.BuildConfig
import ceui.lisa.update.UpgradeRequiredPrompt
import com.google.gson.JsonParser
import okhttp3.Interceptor
import okhttp3.Response
import timber.log.Timber

/** Installed only on first-party API clients; never on Pixiv or COS transfers. */
internal class ClientVersionInterceptor(
    private val onUpgradeRequired: (String?) -> Unit = UpgradeRequiredPrompt::notifyRequired,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (!isFirstParty(request.url.host)) return chain.proceed(request)
        val response = chain.proceed(
            request.newBuilder().header(VERSION_HEADER, BuildConfig.VERSION_CODE.toString()).build()
        )
        if (response.code == 426 && isFirstParty(response.request.url.host)) {
            // Peek, do not consume: Retrofit and callers returning Response<T> still need the
            // original status and error body. Even an empty/malformed 426 must offer an upgrade.
            val version = runCatching {
                val json = JsonParser.parseString(response.peekBody(4096).string()).asJsonObject
                json.get("minVersionName")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
                    ?.asString?.trim()?.takeIf { it.isNotEmpty() && it.length <= 64 }
            }.getOrNull()
            runCatching { onUpgradeRequired(version) }
                .onFailure { Timber.w(it, "Could not queue upgrade prompt") }
        }
        return response
    }

    private fun isFirstParty(host: String) = host == "pixshaft.com" || host == "api.pixshaft.com"

    companion object {
        const val VERSION_HEADER = "X-Shaft-App-Version"
    }
}
