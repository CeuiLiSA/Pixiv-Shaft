package ceui.pixiv.ui.settings

import android.os.Bundle
import android.view.View
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.map
import ceui.lisa.R
import ceui.lisa.databinding.FragmentPixivListBinding
import ceui.pixiv.i18n.AppLocales
import ceui.pixiv.ui.common.ListMode
import ceui.pixiv.ui.common.PixivFragment
import ceui.pixiv.ui.common.TabCellHolder
import ceui.pixiv.ui.common.setUpCustomAdapter
import ceui.pixiv.ui.common.viewBinding

class SelectLanguageFragment : PixivFragment(R.layout.fragment_pixiv_list) {

    private val binding by viewBinding(FragmentPixivListBinding::bind)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.toolbarLayout.naviTitle.text = getString(R.string.language)

        // null = follow system
        val selectedTag = MutableLiveData<String?>(
            if (AppLocales.isFollowingSystem()) null else AppLocales.currentLocale().toLanguageTag(),
        )
        val adapter = setUpCustomAdapter(binding, ListMode.VERTICAL_TABCELL)

        val entries = buildList {
            add(LanguageEntry(tag = null, label = getString(R.string.language_follow_system)))
            AppLocales.supportedTags.forEach { tag ->
                add(LanguageEntry(tag = tag, label = AppLocales.displayName(tag)))
            }
        }

        adapter.submitList(
            entries.map { entry ->
                TabCellHolder(
                    entry.label,
                    showGreenDone = true,
                    selected = selectedTag.map { it == entry.tag },
                ).onItemClick {
                    // AppCompat 会自动 recreate 顶层 Activity，不需要手动更新 selectedTag。
                    AppLocales.apply(entry.tag)
                }
            },
        )
    }

    private data class LanguageEntry(val tag: String?, val label: String)
}
