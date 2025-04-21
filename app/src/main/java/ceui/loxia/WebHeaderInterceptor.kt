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
            .addHeader("cookie", "p_ab_d_id=488794011; privacy_policy_notification=0; b_type=1; _ga_ZQEKG3EF2C=GS1.1.1719661097.1.0.1719661260.17.0.0; privacy_policy_agreement=7; FCCDCF=%5Bnull%2Cnull%2Cnull%2C%5B%22CQDceEAQDceEAEsACBENBBFoAP_gAEPgABJ4INJB7C7FbSFCwH5zaLsAMAhHRsAAQoQAAASBAmABQAKQIAQCgkAQFASgBAACAAAAICZBIQIECAAACUAAQAAAAAAEAAAAAAAIIAAAgAEAAAAIAAACAAAAEAAIAAAAEAAAmAgAAIIACAAAhAAAAAAAAAAAAAAAAgCAAAAAAAAAAAAAAAAAAQOhSD2F2K2kKFkPCmwXYAYBCujYAAhQgAAAkCBMACgAUgQAgFJIAgCIFAAAAAAAAAQEiCQAAQABAAEIACgAAAAAAIAAAAAAAQQAABAAIAAAAAAAAEAAAAIAAQAAAAIAABEhCAAQQAEAAAAAAAQAAAAAAAAAAABAAA%22%2C%222~70.89.93.108.122.149.196.236.259.311.313.323.358.415.449.486.494.495.540.574.609.827.864.981.1029.1048.1051.1095.1097.1126.1205.1276.1301.1365.1415.1423.1449.1514.1570.1577.1598.1651.1716.1735.1753.1765.1870.1878.1889.1958.2072.2253.2299.2357.2373.2415.2506.2526.2568.2571.2575.2624.2677~dv.%22%2C%2245FBE8EA-5D8D-4C57-975D-B7A26F4BE3B8%22%5D%5D; a_type=1; first_visit_datetime=2024-09-10%2013%3A34%3A14; first_visit_datetime_pc=2024-10-23%2019%3A28%3A04; p_ab_id=3; p_ab_id_2=4; yuid_b=MFUJB1k; login_ever=yes; street_tutorial=1; _ga_3WKBFJLFCP=GS1.1.1739004631.4.0.1739004631.0.0.0; FCNEC=%5B%5B%22AKsRol8WXCOYusneTTJQcaveSAB9WbgIWkpsHHULp4v1GsTwRJZgjVeqLR4jL3H0Eadl2CPKef2b2vx-6gIwhLNNB76BjWPvBtYZrJsGJsHtVR-CQsJ1boFCQez6KDutx5wfMUQubYv-Qz9gN7jFnSkaKTTEPnH0lA%3D%3D%22%5D%5D; __utmv=235335808.|2=login%20ever=yes=1^3=plan=normal=1^5=gender=male=1^6=user_id=31660292=1^9=p_ab_id=3=1^10=p_ab_id_2=4=1^11=lang=zh=1^20=webp_available=yes=1; c_type=31; __utma=235335808.1777699323.1693275529.1739256046.1739850787.78; __utmz=235335808.1739850787.78.67.utmcsr=google|utmccn=(organic)|utmcmd=organic|utmctr=(not%20provided); _ga=GA1.1.1777699323.1693275529; webp_available=1; PHPSESSID=fd08cd7659abd8f7f73d8134ace74969; cc1=2025-04-21%2011%3A33%3A46; _ga_MZ1NL4PHH0=GS1.1.1745202866.12.0.1745202870.0.0.0; __cf_bm=C5jV.5CG5dMrTYdvzJvR5cqxV4cBtNItPh8gLzi8gTU-1745203668-1.0.1.1-fp9iPxTfJwfSrGJ37kdU8Nv8pxtInL70ZRx8b4tLX0SGmaIs6jWZzXTKjLgLI4kA5QNKFIhfy9lCtic3Q.uNzyJOYqEyuyLZFn8ZJJGbJrwNRShUdEqcYeqXruV2zlUj; cf_clearance=80fPzVMhbAt4y_LdKJeRwQeHAn5oAzSNU_RwrYQ1_I8-1745204711-1.2.1.1-J2OW6KcIXJMXVSPal9ljXnpb1ainctTWYrYqYuWiYnSRISf8RCKoG8rieQx6eJMa6kLZDz.Em27iy8MmcqWEg4jv4Rd4UK.HD0OlTzUQj9IMEG5XLq9P1_KNZXBgZgzMq8DhWTB6z.09ufHXPT8FPnq_3XnzENgMkmcojUMyxXxNMBjWfQKvkvJ4.d0tOx2ShfiFzkZcThS4RnYmEq.gyLbV8BiDsyBDvw9hHOufcZookDntZJm_ijXVZkmuCtNkEGRLJNuKz.GDFXh.HtVY5N2ejNswJnhJjED534EL_kKgaBO49MHZo44JX8vbigc7iTJmLic9mjcP_pH9Ns0kiphSp.nnG1wt6RWIOIfWNdE; _ga_75BBYNYN9J=GS1.1.1745202830.118.1.1745204768.0.0.0")
            .addHeader("x-csrf-token", "38206841946ed123600e6fe370ae3a8a")
            .addHeader("User-Agent", "Mozilla/5.0 (Linux; Android 14; Pixel 6 Build/UQ1A.240105.002; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/123.0.6312.42 Mobile Safari/537.36 AgentWeb/4.1.9  UCBrowser/11.6.4.950")
        return before
    }
}