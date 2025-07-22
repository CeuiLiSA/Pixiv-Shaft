package ceui.pixiv.ui.landing

import android.os.Bundle
import android.view.View
import ceui.lisa.R
import ceui.lisa.databinding.FragmentSelectLoginWayBinding
import ceui.lisa.feature.HostManager
import ceui.loxia.launchSuspend
import ceui.loxia.openClashApp
import ceui.loxia.pushFragment
import ceui.loxia.requireNetworkStateManager
import ceui.pixiv.ui.common.PixivFragment
import ceui.pixiv.ui.common.viewBinding
import ceui.pixiv.ui.web.WebFragmentArgs
import ceui.pixiv.utils.setOnClick
import ceui.pixiv.widgets.alertYesOrCancel

class SelectLoginWayFragment : PixivFragment(R.layout.fragment_select_login_way) {

    private val binding by viewBinding(FragmentSelectLoginWayBinding::bind)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.login.setOnClick {
            checkVPNAndNext {
                pushFragment(
                    R.id.navigation_web_fragment,
                    WebFragmentArgs(HostManager.get().loginUrl, saveCookies = true).toBundle()
                )
            }
        }

        binding.signUp.setOnClick {
            checkVPNAndNext {
                pushFragment(
                    R.id.navigation_web_fragment,
                    WebFragmentArgs(HostManager.get().signupUrl, saveCookies = true).toBundle()
                )
            }
        }

        binding.loginWithToken.setOnClick {
            checkVPNAndNext {
                pushFragment(
                    R.id.navigation_login_with_token,
                )
            }
        }
    }

    private fun checkVPNAndNext(block: () -> Unit) {
        val context = requireContext()
        if (requireNetworkStateManager().canAccessGoogle.value == true) {
            block()
        } else {
            launchSuspend {
                if (alertYesOrCancel("请打开VPN后继续")) {
                    openClashApp(context)
                }
            }
        }
    }
}
