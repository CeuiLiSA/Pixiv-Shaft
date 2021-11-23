package ceui.lisa.http

import retrofit2.Call
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.Query

interface CloudFlareDNSService {

    @Headers("Accept: application/dns-json")
    @GET("/dns-query")
    fun query(
        @Query("name") name: String?,
        @Query("type") type: String = "A"
    ): Call<CloudFlareDNSResponse>

    companion object {

        val CLOUDFLARE_DOH_POINT : String = "https://1.0.0.1/"
        val DNSSB_DOH_POINT : String = "https://185.222.222.222/"

        operator fun invoke(point: String): CloudFlareDNSService {
            return Retrofit.Builder()
                .baseUrl(point)
                .addConverterFactory(GsonConverterFactory.create())
                .client(Retro.getLogClient().build())
                .build()
                .create(CloudFlareDNSService::class.java)
        }
    }
}
