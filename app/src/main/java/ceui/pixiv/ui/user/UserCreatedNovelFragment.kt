package ceui.pixiv.ui.user

import android.os.Bundle
import android.view.View
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.navArgs
import ceui.lisa.R
import ceui.lisa.databinding.FragmentPixivListBinding
import ceui.loxia.Client
import ceui.loxia.NovelResponse
import ceui.pixiv.ui.common.DataSource
import ceui.pixiv.ui.common.ListItemHolder
import ceui.pixiv.ui.common.ListMode
import ceui.loxia.RefreshHint
import ceui.pixiv.ui.common.NovelCardHolder
import ceui.pixiv.ui.common.PixivFragment
import ceui.pixiv.ui.common.setUpRefreshState
import ceui.pixiv.ui.list.pixivListViewModel
import ceui.pixiv.ui.common.viewBinding
import ceui.pixiv.ui.novel.UncategorizedNovelsViewModel
import ceui.pixiv.ui.novel.UncategorizedSeriesCardHolder
import ceui.pixiv.ui.task.FetchAllTask
import ceui.pixiv.ui.task.PixivTaskType
import ceui.pixiv.utils.setOnClick
import ceui.pixiv.widgets.MenuItem
import ceui.pixiv.widgets.showActionMenu
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

class UserCreatedNovelFragment : PixivFragment(R.layout.fragment_pixiv_list) {

    private val binding by viewBinding(FragmentPixivListBinding::bind)
    private val args by navArgs<UserCreatedNovelFragmentArgs>()

    // Observed by UncategorizedSeriesCardHolder to flip subtitle from
    // "加载中…" to the concrete count once background pagination finishes.
    // Null = still loading; 0 = no uncategorized novels (card hides);
    // >0 = show count.
    private val uncategorizedCount = MutableLiveData<Int?>(null)

    private val viewModel by pixivListViewModel {
        object : DataSource<ceui.loxia.Novel, NovelResponse>(
            dataFetcher = { Client.appApi.getUserCreatedNovels(args.userId) },
            itemMapper = { novel -> listOf(NovelCardHolder(novel)) },
            filter = { novel -> novel.visible != false }
        ) {
            // Prepend the virtual "未归类作品" series card at position 0. We
            // always insert it while the count probe is still running (count
            // == null) or > 0; if the probe confirms 0 uncategorized novels
            // we drop it so the card doesn't mislead.
            override fun updateHolders(holders: List<ListItemHolder>) {
                val tally = uncategorizedCount.value
                val shouldShow = tally == null || tally > 0
                val out = if (shouldShow) {
                    buildList<ListItemHolder> {
                        add(
                            UncategorizedSeriesCardHolder(
                                userId = args.userId,
                                count = uncategorizedCount,
                            )
                        )
                        addAll(holders)
                    }
                } else {
                    holders
                }
                super.updateHolders(out)
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setUpRefreshState(binding, viewModel, ListMode.VERTICAL)
        binding.toolbarLayout.naviMore.setOnClick {
            showActionMenu {
                add(
                    MenuItem(getString(R.string.download_all_created_novels)) {
                        FetchAllTask(requireActivity(), taskFullName = "下载用户全部小说作品-${args.userId}", PixivTaskType.DownloadSeriesNovels) {
                            Client.appApi.getUserCreatedNovels(
                                args.userId,
                            )
                        }
                    }
                )
            }
        }

        // Count probe: page through every novel the author has published and
        // tally the ones with no series. Drives the virtual card's subtitle.
        // If this hit fails we stay in the "加载中…" state forever — that's
        // acceptable here; the card stays tappable and the inner page can
        // still fetch on its own.
        probeUncategorizedCount()
    }

    private fun probeUncategorizedCount() {
        viewLifecycleOwner.lifecycleScope.launch {
            val gson = Gson()
            val tally = withContext(Dispatchers.IO) {
                try {
                    var count = 0
                    var resp: NovelResponse = Client.appApi.getUserCreatedNovels(args.userId)
                    count += resp.novels.count { UncategorizedNovelsViewModel.isUncategorized(it) }
                    var next = resp.nextPageUrl
                    while (!next.isNullOrEmpty()) {
                        // Gentle rate-limit — mirrors FetchAllTask's 1.5s
                        // delay so Pixiv doesn't 429 us while the user is
                        // just sitting on the list page.
                        delay(1500L)
                        val body = Client.appApi.generalGet(next).string()
                        resp = gson.fromJson(body, NovelResponse::class.java)
                        count += resp.novels.count { UncategorizedNovelsViewModel.isUncategorized(it) }
                        next = resp.nextPageUrl
                    }
                    count
                } catch (ex: Exception) {
                    Timber.w(ex, "probeUncategorizedCount failed for ${args.userId}")
                    null
                }
            } ?: return@launch
            uncategorizedCount.value = tally
            // If count == 0 the card was eagerly inserted with a "加载中…"
            // subtitle; the only way to drop it is to re-emit holders. A
            // light refresh hits the response-cache first so it's cheap.
            if (tally == 0) {
                viewModel.refresh(RefreshHint.PullToRefresh)
            }
        }
    }
}
