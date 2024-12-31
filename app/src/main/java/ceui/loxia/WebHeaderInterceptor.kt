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

    private val prefStore: MMKV by lazy {
        MMKV.defaultMMKV()
    }
    private val gson by lazy { Gson() }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        return chain.proceed(
            addHeader(
                request.newBuilder()
            ).build()
        )
    }

    private fun addHeader(before: Request.Builder): Request.Builder {
        val cookies = prefStore.getString(SessionManager.COOKIE_KEY, "") ?: ""
//        val end = cookies.substring(1, cookies.length - 1)
//        val end2 = end.replace("first_visit_datetime", "first_visit_datetime_pc")
        Common.showLog("dsaadsdsaaww2 get ${cookies}")
        before.addHeader("accept-language", LanguageHelper.getRequestHeaderAcceptLanguageFromAppLanguage())
            .addHeader("Host", "www.pixiv.net")
//            .addHeader("Cookie", "first_visit_datetime_pc=2023-08-29%2011%3A18%3A36; p_ab_id=3; p_ab_id_2=0; p_ab_d_id=488794011; yuid_b=KIUHKVQ; privacy_policy_notification=0; a_type=0; login_ever=yes; _gcl_au=1.1.1589565743.1706238528; _im_vid=01HSA975DT21AG0E8C4Z7KQNV6; device_token=88d8a17289eb672e1e5deaf9118e04d7; PHPSESSID=31660292_ByvXUOYCfHCUtF97MjBan4p2oHGdQti8; privacy_policy_agreement=6; _ga_MZ1NL4PHH0=GS1.1.1711599851.7.0.1711599853.0.0.0; c_type=30; b_type=1; __utmc=235335808; cf_clearance=xko7RmBNHP1sB8DwXdGQz37ziHLQPBfEU1Bto0FY8bw-1712458701-1.0.1.1-Q_zVD_Ogdb8EreKgus.FAXRl1YWF02L7hCgVM.dFVnH61U6QLt7mqLJf.ZL9ZoLvWApHjnIs.v.HIUpG5ROiSg; QSI_S_ZN_5hF4My7Ad6VNNAi=v:0:0; _gid=GA1.2.921747741.1712458705; FCNEC=%5B%5B%22AKsRol-lCQJAg59fClTyi9swNt7kbFKms5xxTNO6tB7_rQNEjO181niKSaw15LknvKPUZi0wzOiOQccrked7QnBJq8gBurHfFb_dA0NEw-HboJg_fnTDLUakXTu-neyS5-fdI0tkChtJ2-UWIxFpvIP0QYmKHk9M9w%3D%3D%22%5D%5D; __utmv=235335808.|2=login%20ever=yes=1^3=plan=normal=1^5=gender=male=1^6=user_id=31660292=1^9=p_ab_id=3=1^10=p_ab_id_2=0=1^11=lang=zh=1; __cf_bm=Xw2w0_5OAkqEzaqIB9upbW1fjOH4sGhPMmRPGuuaaoQ-1712468986-1.0.1.1-bMB8ZolgqD353nE5ToW.KjlovBcDfkH9ACQdhUkekutEADYdCrILSeDPMSgTcY0I.IIRxSXBKOn.tGrOB6PNBZbuX5Hfps_TcLODg7WmXuY; __utma=235335808.1777699323.1693275529.1712458700.1712468987.24; __utmz=235335808.1712468987.24.21.utmcsr=google|utmccn=(organic)|utmcmd=organic|utmctr=(not%20provided); __utmt=1; _gat_UA-1830249-3=1; __utmb=235335808.2.10.1712468987; _ga=GA1.1.1777699323.1693275529; _ga_75BBYNYN9J=GS1.1.1712468987.34.1.1712469002.0.0.0")
            .addHeader("Cookie", cookies)
            .addHeader("Referer", "https://www.pixiv.net/")
            .addHeader("User-Agent", "Mozilla/5.0 (Linux; Android 14; Pixel 6 Build/UQ1A.240105.002; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/123.0.6312.42 Mobile Safari/537.36 AgentWeb/4.1.9  UCBrowser/11.6.4.950")
//        val type = object : TypeToken<Map<String, String>>() {}.type
//        val jsonHeaders = prefStore.getString("web-api-header", "")
//        if (jsonHeaders?.isNotEmpty() == true) {
//            val deserializedHeaders: Map<String, String> = gson.fromJson(jsonHeaders, type)
//            deserializedHeaders.forEach { (k, v) ->
//                before.removeHeader(k)
//                before.addHeader(k, v)
//            }
//        }
        return before
    }
}