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
}