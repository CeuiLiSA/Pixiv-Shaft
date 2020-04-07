package ceui.lisa.http

import okhttp3.OkHttpClient
import retrofit2.Call
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query


interface CloudFlareDNSService {

    @GET("/dns-query")
    fun query(
            @Query("name") name: String,
            @Query("ct") ct: String? = "application/dns-json",
            @Query("type") type: String = "A",
            @Query("do") `do`: Boolean? = null,
            @Query("cd") cd: Boolean? = null
    ): Call<CloudFlareDNSResponse>

    companion object {
        operator fun invoke(): CloudFlareDNSService {
            return Retrofit.Builder()
                    .baseUrl("https://1.0.0.1/")
                    .addConverterFactory(GsonConverterFactory.create())
                    .client(Retro.getLogClient().build())
                    .build()
                    .create(CloudFlareDNSService::class.java)
        }
    }
}
