package ceui.pixiv.ui.landing

import android.os.Bundle
import android.view.View
import ceui.lisa.R
import ceui.lisa.databinding.FragmentSelectLoginWayBinding
import ceui.pixiv.ui.common.PixivFragment
import ceui.pixiv.ui.common.viewBinding
import ceui.pixiv.utils.setOnClick

class SelectLoginWayFragment : PixivFragment(R.layout.fragment_select_login_way) {

    private val binding by viewBinding(FragmentSelectLoginWayBinding::bind)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.login.setOnClick {

        }

        binding.signUp.setOnClick {
        }

        binding.loginWithToken.setOnClick {
//            checkVPNAndNext {
//                pushFragment(
//                    R.id.navigation_login_with_token,
//                )
//            }
        }
    }

}
