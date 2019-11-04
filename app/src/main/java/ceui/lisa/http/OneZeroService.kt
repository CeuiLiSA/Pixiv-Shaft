package ceui.lisa.http


import retrofit2.Call
import retrofit2.http.GET
import retrofit2.http.Query

interface OneZeroService {
    @GET("dns-query")
    fun getItem(@Query(value = "ct", encoded = true) ct: String,
                @Query("name") name: String,
                @Query("type") type: String,
                @Query("do") doo: String,
                @Query("cd") cd: String): Call<OneZeroResponse>
}
