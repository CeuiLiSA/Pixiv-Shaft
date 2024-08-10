package ceui.pixiv.ui.search

import androidx.fragment.app.viewModels
import ceui.lisa.R
import ceui.pixiv.ui.common.PixivFragment

class SearchNovelFragment : PixivFragment(R.layout.fragment_pixiv_list) {
    private val searchViewModel by viewModels<SearchViewModel>(ownerProducer = { requireParentFragment() })

    /**
     * :path	/v1/search/novel?include_translated_tag_results=true&word=%E8%96%AC%E5%B1%8B%E3%81%AE%E3%81%B2%E3%81%A8%E3%82%8A%E3%81%94%E3%81%A8&merge_plain_keyword_results=true&search_target=exact_match_for_tags&sort=date_desc&search_ai_type=0
     */
}