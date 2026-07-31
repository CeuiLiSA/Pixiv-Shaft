package ceui.pixiv.ui.slideshow

import java.util.UUID

object SlideshowStore {

    private const val MAX_SESSIONS = 8

    data class Session(
        val urls: List<String>,
        val titles: List<String>,
        val startIndex: Int,
        val random: Boolean,
    )

    private val sessions = LinkedHashMap<String, Session>()

    @Synchronized
    fun put(session: Session): String {
        val id = UUID.randomUUID().toString()
        sessions[id] = session.copy(
            urls = session.urls.toList(),
            titles = session.titles.toList(),
        )
        if (sessions.size > MAX_SESSIONS) {
            val oldest = sessions.keys.firstOrNull()
            if (oldest != null && oldest != id) sessions.remove(oldest)
        }
        return id
    }

    @Synchronized
    fun get(id: String): Session? = sessions[id]

    @Synchronized
    fun remove(id: String) {
        sessions.remove(id)
    }
}
