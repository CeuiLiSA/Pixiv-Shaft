package ceui.loxia

import ceui.lisa.activities.Shaft
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response

class HeaderInterceptor(private val isWebApi: Boolean) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        return chain.proceed(
            addHeader(
                chain.request().newBuilder()
            ).build()
        )
    }

    private fun addHeader(before: Request.Builder): Request.Builder {
        val requestNonce = RequestNonce.build()
        before.addHeader(ClientManager.HEADER_AUTH, Shaft.sUserModel.access_token)
            .addHeader("accept-language", "zh-cn")
            .addHeader("app-os", "ios")
            .addHeader("app-version", "7.13.4")
            .addHeader("x-client-time", requestNonce.xClientTime)
            .addHeader("x-client-hash", requestNonce.xClientHash)
        if (isWebApi) {
//            before.addHeader("sec-fetch-site", "same-origin")
//            before.addHeader("x-user-id", "31660292")
//            before.addHeader("cookie", "first_visit_datetime_pc=2021-12-05+20:19:46; p_ab_id=2; p_ab_id_2=5; p_ab_d_id=490296721; yuid_b=JFlZkmA; _ga=GA1.2.1413912358.1638703186; privacy_policy_agreement=3; privacy_policy_notification=0; a_type=0; b_type=1; d_type=1; __utmv=235335808.|2=login ever=no=1^3=plan=normal=1^5=gender=male=1^6=user_id=31660292=1^9=p_ab_id=2=1^10=p_ab_id_2=5=1^11=lang=zh=1; ki_s=214908:0.0.0.0.2;214994:0.0.0.0.2;215190:0.0.0.0.2;221691:0.0.0.0.2; ki_r=aHR0cHM6Ly93d3cuZ29vZ2xlLmNvbS8=; ki_t=1638703201364;1639220061113;1639220064473;2;5; _gcl_au=1.1.1256324010.1650767479; _fbp=fb.1.1650767479751.418437755; PHPSESSID=31660292_Ks7ljohmnuwsD9YX5DlWqyxukCSCJjzh; device_token=d132f759ad41fa7f8a5c544f33dc4e0b; c_type=28; QSI_SI_eY5YAs3UC8fZN3g_intercept=true; _im_vid=01G1CPHV3MG65WBH86K6GR6GK9; adr_id=CBAQccnlNmZMZFL104C73SwAyuvZiEnQhdLe36Q0yYVhG6Vm; _gid=GA1.2.1148235764.1652012841; __utma=235335808.1413912358.1638703186.1652012828.1652060888.5; __utmc=235335808; __utmz=235335808.1652060888.5.5.utmcsr=google|utmccn=(organic)|utmcmd=organic|utmctr=(not provided); __cf_bm=kfrbZwKE1TKiWxCwTyhEJjhd2PNhJSwzN36AZCxTr7w-1652060888-0-ASbtxf7vBql8VOX3y4e/6LMaLvvf0XfCLAi5qYmMxg/skEVIsfhunI1O/cSUKumPWQH4Pqak8NE2jNKWKowi5ECieHAuJ5pgrBfNjWd+OB/XMYNjK0fCYV+uYvxFQNY/NaoYxRqFXHG5+h7Ffj/0SIOQd5RkCbxIFsc5NPIUR0AoCVAAAEx9ywRNRsoj7hq/MA==; _im_uid.3929=b.743341ffbcdbaeaa; QSI_S_ZN_5hF4My7Ad6VNNAi=r:10:4; __utmt=1; tag_view_ranking=RTJMXD26Ak~SGynHlaqpK~_pwIgrV8TB~jH0uD88V6F~Lt-oEicbBr~sOBG5_rfE2~yREQ8PVGHN~_EOd7bsGyl~F5CBR92p_Q~HtzL6MJRMg~cryvQ5p2Tx~eiuRRKbs6P~jhuUT0OJva~pa4LoD4xuT~LJo91uBPz4~nSgabm-jWl~nZxUxw-kIG; __utmb=235335808.8.10.1652060888; cto_bundle=r6Cunl93aUhIVEptVVhvejhaZGhMMnhXcWZ0akZHOUJGUkFQd1lWanpkQ0JUbGpwbGhhSUZ6RU9xJTJGTjBkOUt6M0klMkJKQmhadnI1TWZyaFFDbHgwdnB2JTJGVFFLNnYlMkJMazF0b012YUFFSndQTENCY3NmY1l5T3BBT2xaN0IwMVlOdTUwWWN0YUFOTkxhVnZBMlB3cTZUaHBEbWhxUSUzRCUzRA; _gat_UA-1830249-3=1")
            before.addHeader("user-agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/101.0.4951.54 Safari/537.36")
        } else {
            before.addHeader("user-agent", "PixivIOSApp/7.13.4 (iOS 16.0.3; iPhone13,3)")
        }
        return before
    }
}