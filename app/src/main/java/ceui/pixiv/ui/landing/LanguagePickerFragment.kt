package ceui.pixiv.ui.landing

import android.os.Bundle
import android.view.View
import ceui.lisa.R
import ceui.lisa.databinding.FragmentPixivListBinding
import ceui.lisa.databinding.FragmentPixivListBinding.bind
import ceui.lisa.databinding.FragmentSelectLanguageBinding
import ceui.pixiv.ui.common.PixivFragment
import ceui.pixiv.ui.common.viewBinding

class LanguagePickerFragment : PixivFragment(R.layout.fragment_select_language) {

    private val binding by viewBinding(FragmentSelectLanguageBinding::bind)


    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.welcomeLabel.text = getString(R.string.language)
    }
}