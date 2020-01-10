package ceui.lisa.http

import ceui.lisa.model.DnsQueryResponse
import io.reactivex.Observable
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Query

/**
 * Cloudflare API: https://developers.cloudflare.com/.
 */
interface CloudflareService {

    /**
     * Cloudflare's DNS over HTTPS endpoint: https://developers.cloudflare.com/1.1.1.1/dns-over-https/json-format/.
     *
     * @param name Query Name: Required.
     * @param type Query Type (either a numeric value or text): Required.
     * @param do DO bit - set if client wants DNSSEC data (either boolean or numeric value): Optional.
     * @param cd CD bit - set to disable validation (either boolean or numeric value): Optional.
     */
    @GET("dns-query")
    fun queryDns(
            @Header("accept") accept: String = "application/dns-json",
            @Query("name") name: String,
            @Query("type") type: String = "A",
            @Query("do") `do`: Boolean? = null,
            @Query("cd") cd: Boolean? = null
    ): Observable<DnsQueryResponse>

    companion object {
        const val URL = "https://api.cloudflare.com/"
        const val URL_DNS_RESOLVER = "https://1.0.0.1/" // Or https://1.0.0.1/.
    }
}
