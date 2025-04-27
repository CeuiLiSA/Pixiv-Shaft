package ceui.pixiv.ui.web

import androidx.core.net.toUri
import androidx.navigation.NavController
import androidx.navigation.NavOptions
import ceui.lisa.R
import ceui.pixiv.session.SessionManager
import timber.log.Timber

class LinkHandler(private val navController: NavController) {

    fun processLink(link: String?): Boolean {
        if (link?.startsWith("pixiv://") == true) {
            val uri = link.toUri()
            if (uri.host == "account") {
                if (uri.path == "/login") {
                    SessionManager.login(uri) {
                        val navOptions = NavOptions.Builder()
                            .setPopUpTo(R.id.navigation_landing, true) // 清除到 Landing
                            .build()
                        navController.navigate(R.id.navigation_home_viewpager, null, navOptions)
                    }
                }
            } else {
                Timber.w("Unknown pixiv deeplink: ${uri}")
            }

            return true
        }

        return false
    }
}