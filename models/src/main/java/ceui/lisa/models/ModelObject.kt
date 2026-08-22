package ceui.lisa.models

interface ModelObject {
    val objectUniqueId: Long
    val objectType: Int
}

object ObjectSpec {
    const val UNKNOWN = 0
    const val POST = 1
    const val USER = 2
    const val ARTICLE = 3
    const val GIF_INFO = 4


    const val Illust = 5
    const val KUser = 6
    const val JNOVEL = 7

    const val UserProfile = 8

    // Kotlin loxia.Novel 的专属类型。绝不能复用 POST(=Illust)：插画 ID 与小说 ID
    // 是两套独立命名空间，会在 (id, POST) 上撞键，导致 get<Novel> 取到 Illust 后
    // ClassCastException。等价于 Illust(POST)↔Illust(5)、UserBean(USER)↔User(KUser)
    // 这对 Java/Kotlin 双胞胎各自独占类型的既有约定。
    const val KNovel = 9
}