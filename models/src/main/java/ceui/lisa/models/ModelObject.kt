package ceui.lisa.models

interface ModelObject {
    val objectUniqueId: Long
    val objectType: Int
}

object ObjectSpec {
    const val UNKNOWN = 0
    const val POST = 1
    const val ARTICLE = 3
    const val GIF_INFO = 4


    const val Illust = 5
    const val KUser = 6

    const val UserProfile = 8

    // Novel 的专属类型。绝不能复用 POST(=Illust)：插画 ID 与小说 ID
    // 是两套独立命名空间，会在 (id, POST) 上撞键，导致 get<Novel> 取到 Illust 后
    // ClassCastException。User 已完成单模型迁移，KUser 现在是唯一用户对象池类型。
    const val KNovel = 9
}
