package ceui.pixiv.ui.web

import androidx.core.net.toUri
import androidx.navigation.NavController
import androidx.navigation.NavOptions
import ceui.lisa.R
import ceui.loxia.setHorizontalSlide
import ceui.pixiv.session.SessionManager
import ceui.pixiv.ui.detail.ArtworkFragmentArgs
import ceui.pixiv.ui.user.UserFragmentArgs
import timber.log.Timber

class LinkHandler(private val navController: NavController) {

    fun processLink(link: String?): Boolean {
        if (link.isNullOrEmpty()) return false

        if (link.startsWith("pixiv://")) {
            val uri = link.toUri()

            when {
                uri.host == "account" && uri.path == "/login" -> {
                    SessionManager.login(uri) {
                        val navOptions = NavOptions.Builder()
                            .setPopUpTo(R.id.navigation_landing, true)
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

        // 用正则提取 artworkId
        val matchResult = ARTWORK_URL_REGEX.find(link)
        if (matchResult != null) {
            val artworkId = matchResult.groupValues[1].toLong()
            navController.navigate(
                R.id.navigation_artwork,
                ArtworkFragmentArgs(artworkId).toBundle(),
                NavOptions.Builder().setHorizontalSlide().build()
            )
            return true
        }

        return false
    }

    companion object {
        private val ARTWORK_URL_REGEX = Regex("""https://www\.pixiv\.net/(?:\w+/)?artworks/(\d+)""")
    }
}
