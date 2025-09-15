package ceui.pixiv.ui.web

import androidx.core.net.toUri
import androidx.navigation.NavController
import androidx.navigation.NavOptions
import ceui.lisa.R
import ceui.loxia.findActionReceiverOrNull
import ceui.loxia.setHorizontalSlide
import ceui.pixiv.session.SessionManager
import ceui.pixiv.ui.common.NovelActionReceiver
import ceui.pixiv.ui.common.PixivFragment
import ceui.pixiv.ui.detail.ArtworkFragmentArgs
import ceui.pixiv.ui.user.UserActionReceiver
import ceui.pixiv.ui.user.UserFragmentArgs
import timber.log.Timber

class LinkHandler(
    private val navController: NavController,
    private val fromFragment: PixivFragment? = null
) {

    fun processLink(link: String?): Boolean {
        if (link.isNullOrEmpty()) return false

        if (link.startsWith("pixiv://")) {
            val uri = link.toUri()

            when {
                uri.host == "account" && uri.path == "/login" -> {
                    SessionManager.loginWithUrl(uri) {
                        val navOptions = NavOptions.Builder()
                            .setPopUpTo(R.id.navigation_landing, true)
                            .setHorizontalSlide()
                            .build()
                        navController.navigate(R.id.navigation_home_viewpager, null, navOptions)
                    }
                }

                uri.host == "illusts" -> {
                    val illustId = uri.lastPathSegment?.toLongOrNull()
                    if (illustId != null) {
                        navController.navigate(
                            R.id.navigation_artwork,
                            ArtworkFragmentArgs(illustId).toBundle(),
                            NavOptions.Builder().setHorizontalSlide().build()
                        )
                        return true
                    }
                }

                uri.host == "users" -> {
                    val userId = uri.lastPathSegment?.toLongOrNull()
                    if (userId != null) {
                        navController.navigate(
                            R.id.navigation_user,
                            UserFragmentArgs(userId).toBundle(),
                            NavOptions.Builder().setHorizontalSlide().build()
                        )
                        return true
                    }
                }

                else -> {
                    Timber.w("Unknown pixiv deeplink: $uri")
                }
            }
            return true
        }

        // 统一用正则匹配 https 链接
        ARTWORK_URL_REGEX.find(link)?.let { match ->
            val artworkId = match.groupValues[1].toLong()
            navController.navigate(
                R.id.navigation_artwork,
                ArtworkFragmentArgs(artworkId).toBundle(),
                NavOptions.Builder().setHorizontalSlide().build()
            )
            return true
        }

        USER_URL_REGEX.find(link)?.let { match ->
            val userId = match.groupValues[1].toLong()
            fromFragment?.findActionReceiverOrNull<UserActionReceiver>()?.onClickUser(userId)
            return true
        }

        NOVEL_URL_REGEX.find(link)?.let { match ->
            val novelId = match.groupValues[1].toLong()
            fromFragment?.findActionReceiverOrNull<NovelActionReceiver>()?.visitNovelById(novelId)
            return true
        }

        return false
    }

    companion object {
        private val ARTWORK_URL_REGEX = Regex("""https://www\.pixiv\.net/(?:\w+/)?artworks/(\d+)""")
        private val USER_URL_REGEX =
            Regex("""https://www\.pixiv\.net/users/(\d+)""")
        private val NOVEL_URL_REGEX =
            Regex("""https://www\.pixiv\.net/novel/show\.php\?id=(\d+)""")
    }
}
