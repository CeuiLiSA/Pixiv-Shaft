package ceui.lisa.http

import com.google.gson.annotations.SerializedName

data class OneZeroResponse(

    @SerializedName("status") val status : Int,
    @SerializedName("tC") val tC : Boolean,
    @SerializedName("rD") val rD : Boolean,
    @SerializedName("rA") val rA : Boolean,
    @SerializedName("aD") val aD : Boolean,
    @SerializedName("cD") val cD : Boolean,
    @SerializedName("question") val question : List<Question>,
    @SerializedName("answer") val answer : List<Answer>
)
data class Question (

        @SerializedName("name") val name : String,
        @SerializedName("type") val type : Int
)
data class Answer (

        @SerializedName("name") val name : String,
        @SerializedName("type") val type : Int,
        @SerializedName("tTL") val tTL : Int,
        @SerializedName("data") val data : String
)