package ceui.lisa.models

interface ModelObject {
    val objectUniqueId: Long
    val objectType: Int
}

object ObjectSpec {
    const val UNKNOWN = 0

    const val KOTLIN_ILLUST = 1
    const val KOTLIN_USER = 2
    const val KOTLIN_NOVEL = 3


    const val JAVA_ILLUST = 6
    const val JAVA_NOVEL = 7
    const val JAVA_USER = 8

    const val UserProfile = 9

    const val TRENDING_TAG = 10
    const val USER_PREVIEW = 11

    const val ARTICLE = 100
    const val GIF_INFO = 101

    const val USER_TASK = 1001

}