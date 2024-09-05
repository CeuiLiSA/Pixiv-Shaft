package ceui.loxia

import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

interface PixivWebApi {

    //

    @GET("/rpc/index.php?mode=latest_message_threads2&num=10&offset=0")
    suspend fun getMessageList()

    @GET("/ajax/illust/{illust_id}")
    suspend fun getWebIllust(@Path("illust_id") illust_id: Long)

    @GET("/ajax/top/illust?mode=all&lang=zh")
    suspend fun getSquareContents(): SquareResponse

    @GET("ajax/user/{user_id}/illusts/bookmarks?rest=show&offset=0&limit=48&lang=zh&version=ebdc1282e55d2c6d71244b71f158c2f32e968753")
    suspend fun getBookmarkedIllust(
        @Path("user_id") user_id: Long
    ): SquareResponse

    // https://www.pixiv.net/ajax/user/31660292/illusts/bookmarks?tag=&rest=show&lang=zh&version=ebdc1282e55d2c6d71244b71f158c2f32e968753


    @GET("/touch/ajax/recommender/top_items?mode=safe&lang=zh")
    suspend fun getMessageListBBBB()




}