package ceui.lisa.model

import android.os.Parcelable
import com.google.gson.annotations.SerializedName
import kotlinx.android.parcel.Parcelize
import java.io.Serializable

/**
 * @param aD If true, it means that every record in the answer was verified with DNSSEC.
 * @param cD If true, the client asked to disable DNSSEC validation. In this case, Cloudflare will still fetch the DNSSEC-related records, but it will not attempt to validate the records.
 * @param rA If true, it means the Recursion Available bit was set. This is always set to true for Cloudflare DNS over HTTPS.
 * @param rD If true, it means the Recursive Desired bit was set. This is always set to true for Cloudflare DNS over HTTPS.
 * @param status The Response Code of the DNS Query. These are defined here: https://www.iana.org/assignments/dns-parameters/dns-parameters.xhtml#dns-parameters-6.
 * @param tC If true, it means the truncated bit was set. This happens when the DNS answer is larger than a single UDP or TCP packet. TC will almost always be false with Cloudflare DNS over HTTPS because Cloudflare supports the maximum response size.
 */
@Parcelize
data class DnsQueryResponse(
        @SerializedName("AD")
        val aD: Boolean = false,
        @SerializedName("Answer")
        val answer: List<Answer> = listOf(),
        @SerializedName("Authority")
        val authority: List<Authority> = listOf(),
        @SerializedName("CD")
        val cD: Boolean = false,
        @SerializedName("Question")
        val question: List<Question> = listOf(),
        @SerializedName("RA")
        val rA: Boolean = false,
        @SerializedName("RD")
        val rD: Boolean = false,
        @SerializedName("Status")
        val status: Int = 0,
        @SerializedName("TC")
        val tC: Boolean = false
) : Parcelable, Serializable {

    /**
     * @param data The value of the DNS record for the given name and type. The data will be in text for standardized record types and in hex for unknown types.
     * @param name The record owner.
     * @param tTL The number of seconds the answer can be stored in cache before it is considered stale.
     * @param type The type of DNS record. These are defined here: https://www.iana.org/assignments/dns-parameters/dns-parameters.xhtml#dns-parameters-4.
     */
    @Parcelize
    data class Answer(
            @SerializedName("data")
            val data: String = "",
            @SerializedName("name")
            val name: String = "",
            @SerializedName("TTL")
            val tTL: Int = 0,
            @SerializedName("type")
            val type: Int = 0
    ) : Parcelable, Serializable

    @Parcelize
    data class Authority(
            @SerializedName("data")
            val data: String = "",
            @SerializedName("name")
            val name: String = "",
            @SerializedName("TTL")
            val tTL: Int = 0,
            @SerializedName("type")
            val type: Int = 0
    ) : Parcelable, Serializable

    /**
     * @param name The record name requested.
     * @param type The type of DNS record requested. These are defined here: https://www.iana.org/assignments/dns-parameters/dns-parameters.xhtml#dns-parameters-4.
     */
    @Parcelize
    data class Question(
            @SerializedName("name")
            val name: String = "",
            @SerializedName("type")
            val type: Int = 0
    ) : Parcelable, Serializable
}
