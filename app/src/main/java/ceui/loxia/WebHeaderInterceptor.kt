package ceui.loxia

import ceui.lisa.helper.LanguageHelper
import ceui.lisa.utils.Common
import ceui.pixiv.session.SessionManager
import ceui.pixiv.ui.settings.SettingsFragment
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.tencent.mmkv.MMKV
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response

class WebHeaderInterceptor : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        return chain.proceed(
            addHeader(
                request.newBuilder()
            ).build()
        )
    }

    private fun addHeader(before: Request.Builder): Request.Builder {
//        val end2 = end.replace("first_visit_datetime", "first_visit_datetime_pc")
        before.addHeader("accept-language", LanguageHelper.getRequestHeaderAcceptLanguageFromAppLanguage())
            .addHeader("Host", "www.pixiv.net")
            .addHeader("Referer", "https://www.pixiv.net/")
            .addHeader("cookie", "first_visit_datetime_pc=2025-04-16%2017%3A04%3A47; p_ab_id=4; p_ab_id_2=7; p_ab_d_id=1675980838; yuid_b=OEEIVzA; privacy_policy_agreement=7; first_visit_datetime=2025-04-16%2017%3A05%3A26; webp_available=1; street_tutorial=1; _ga_3WKBFJLFCP=GS1.1.1744790738.1.1.1744791324.0.0.0; PHPSESSID=89567067a6b3d1da0dbba5eda64eb79d; cc1=2025-04-18%2018%3A49%3A49; _ga=GA1.1.1942179286.1744790690; _ga_MZ1NL4PHH0=GS1.1.1744969959.1.1.1744970389.0.0.0; cf_clearance=AuYS8PE6OOjKLsIZw55YhAuCKIOE5S2A3gJzQ5CBJ74-1744970392-1.2.1.1-v7sI0a_pv36cOwFEK4fFdS1GOQKbeiAICKaL_NGA7tEm5.0R8j.4hptSdZXH5.rR5.NcTKl65cBDVFCamS.fxdb1OWWvdhb.cLzI0V9cQOoe8SDOdS5R0bsyIRBvO0E83i0ymSgbGe_5oa3s3JpU0g6e_3ZedHZlfYz5xzJ91txRySxrAyXAcZao3zdjFMzt0w57m6BVcL6dDaTDQjPAkcgey_IbqEq5vCv9m5x1iDgqf5qHbqWcvIeoOVSWItaXzof4H7JVuSUN6ae1nHZKyuNVHUHrWGzggiRu4DMm7muiGwNaYSnW3JB.UUdJKdDE9w1KG2Ye5AjQ4sHVU2gickMCX.pOkCAuuDWc.keNwBY")
            .addHeader("x-csrf-token", "602c9af5988dacf73a91955d5350ce93")
            .addHeader("User-Agent", "Mozilla/5.0 (Linux; Android 14; Pixel 6 Build/UQ1A.240105.002; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/123.0.6312.42 Mobile Safari/537.36 AgentWeb/4.1.9  UCBrowser/11.6.4.950")
        return before
    }
}