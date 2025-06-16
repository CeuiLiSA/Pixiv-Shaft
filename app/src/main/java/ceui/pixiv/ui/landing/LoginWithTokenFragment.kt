package ceui.pixiv.ui.landing

import android.os.Bundle
import android.view.View
import androidx.fragment.app.viewModels
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.navigation.NavOptions
import androidx.navigation.fragment.findNavController
import ceui.lisa.R
import ceui.lisa.databinding.FragmentLoginWithTokenBinding
import ceui.loxia.setHorizontalSlide
import ceui.pixiv.session.SessionManager
import ceui.pixiv.ui.common.PixivFragment
import ceui.pixiv.ui.common.viewBinding
import ceui.pixiv.utils.setOnClick

class LoginWithTokenFragment : PixivFragment(R.layout.fragment_login_with_token) {

    class VM : ViewModel() {
        val token = MutableLiveData<String>()
    }

    private val binding by viewBinding(FragmentLoginWithTokenBinding::bind)
    private val viewModel by viewModels<VM>()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.viewModal = viewModel
        val navController = findNavController()
        binding.login.setOnClick {
            SessionManager.loginWithToken(viewModel.token.value ?: "") {
                val navOptions = NavOptions.Builder()
                    .setPopUpTo(R.id.navigation_landing, true)
                    .setHorizontalSlide()
                    .build()
                navController.navigate(R.id.navigation_home_viewpager, null, navOptions)
            }
        }
    }
}
