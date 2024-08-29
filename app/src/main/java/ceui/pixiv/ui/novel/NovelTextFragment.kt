package ceui.pixiv.ui.novel

import android.os.Bundle
import android.view.View
import androidx.navigation.fragment.navArgs
import ceui.lisa.R
import ceui.lisa.utils.Common
import ceui.loxia.Client
import ceui.pixiv.ui.common.PixivFragment
import ceui.pixiv.ui.common.pixivValueViewModel

class NovelTextFragment : PixivFragment(R.layout.fragment_novel_text) {

    private val args by navArgs<NovelTextFragmentArgs>()
    private val novelViewModel by pixivValueViewModel {
        val responseBody = Client.appApi.getNovelText(args.novelId)
        responseBody.string()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        novelViewModel.result.observe(viewLifecycleOwner) {
            Common.showLog("dasdsadsw2 ${it}")
        }
    }
}