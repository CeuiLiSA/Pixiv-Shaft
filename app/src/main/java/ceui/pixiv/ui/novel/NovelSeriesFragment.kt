package ceui.pixiv.ui.novel

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.lifecycle.lifecycleScope
import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.lisa.activities.TemplateActivity
import ceui.lisa.activities.UActivity
import ceui.lisa.activities.VActivity
import ceui.lisa.core.Container
import ceui.lisa.core.PageData
import ceui.lisa.databinding.FragmentPixivListBinding
import ceui.lisa.models.IllustsBean
import ceui.lisa.utils.Params
import ceui.loxia.Client
import ceui.loxia.ObjectPool
import ceui.pixiv.session.SessionManager
import androidx.core.view.isVisible
import ceui.pixiv.ui.common.ListMode
import ceui.pixiv.ui.common.PixivFragment
import ceui.pixiv.ui.common.constructVM
import ceui.pixiv.ui.common.createResponseStore
import ceui.pixiv.ui.common.pixivValueViewModel
import ceui.pixiv.ui.common.setUpRefreshState
import ceui.pixiv.ui.common.viewBinding
import ceui.pixiv.ui.task.FetchAllTask
import ceui.pixiv.ui.task.PixivTaskType
import ceui.pixiv.ui.works.blurBackground
import ceui.pixiv.utils.setOnClick
import ceui.pixiv.widgets.MenuItem
import ceui.pixiv.widgets.showActionMenu
import kotlinx.coroutines.launch
import java.util.UUID
import android.widget.TextView
import android.widget.FrameLayout
import android.view.Gravity
import android.view.ViewGroup
import android.graphics.drawable.GradientDrawable
import android.graphics.Color

class NovelSeriesFragment : PixivFragment(R.layout.fragment_pixiv_list) {

    private val binding by viewBinding(FragmentPixivListBinding::bind)
    private val seriesId: Long by lazy { arguments?.getLong(ARG_SERIES_ID, 0L) ?: 0L }
    private val viewModel by constructVM({ seriesId }) { id ->
        NovelSeriesViewModel(id)
    }
    private val bgViewModel by pixivValueViewModel(
        dataFetcher = {
            Client.appApi.getUserBookmarkedIllusts(
                SessionManager.loggedInUid,
                Params.TYPE_PUBLIC
            )
        },
        responseStore = createResponseStore({ "user-${SessionManager.loggedInUid}-bookmarked-illusts" })
    )

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setUpRefreshState(binding, viewModel, ListMode.VERTICAL)
        bgViewModel.result.observe(viewLifecycleOwner) { resp ->
            resp.displayList.getOrNull(seriesId.mod(10))?.let {
                ObjectPool.update(it)
                blurBackground(binding, it.id)
            }
        }
        binding.toolbarLayout.root.visibility = View.GONE

        // 醒目的合集下载按钮——老版本放在 toolbarLayout 的 more 菜单里，但 toolbarLayout
        // 被整体 GONE 了，用户根本看不到入口。挂到 bottomCovered 里做成一个 fab-ish 的
        // 条状按钮，所有系列页都能一眼看到。
        addDownloadAllButton()
    }

    private fun addDownloadAllButton() {
        val density = resources.displayMetrics.density
        val btn = TextView(requireContext()).apply {
            text = getString(R.string.download_all_artworks)
            setTextColor(Color.WHITE)
            textSize = 15f
            gravity = Gravity.CENTER
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 28 * density
                setColor(0xFF2196F3.toInt())
            }
            elevation = 4 * density
            val h = (48 * density).toInt()
            val mx = (20 * density).toInt()
            val my = (12 * density).toInt()
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, h,
            ).apply { setMargins(mx, my, mx, my) }
            setOnClick { launchDownloadAll() }
        }
        binding.bottomCovered.isVisible = true
        binding.bottomCovered.addView(btn)
    }

    private fun launchDownloadAll() {
        FetchAllTask(
            requireActivity(),
            taskFullName = "下载系列小说全部作品-${seriesId}",
            PixivTaskType.DownloadSeriesNovels,
        ) {
            Client.appApi.getNovelSeries(seriesId)
        }
    }

    override fun onClickUser(id: Long) {
        val intent = Intent(requireContext(), UActivity::class.java).apply {
            putExtra(Params.USER_ID, id.toInt())
        }
        startActivity(intent)
    }

    override fun onClickNovel(novelId: Long) {
        val intent = Intent(requireContext(), TemplateActivity::class.java).apply {
            putExtra(TemplateActivity.EXTRA_FRAGMENT, "小说详情")
            putExtra(Params.NOVEL_ID, novelId)
        }
        startActivity(intent)
    }

    override fun onClickIllust(illustId: Long) {
        viewLifecycleOwner.lifecycleScope.launch {
            val illust = runCatching { Client.appApi.getIllust(illustId).illust }
                .getOrNull() ?: return@launch
            val gson = Shaft.sGson
            val bean = gson.fromJson(gson.toJson(illust), IllustsBean::class.java)
            val uuid = UUID.randomUUID().toString()
            val pageData = PageData(uuid, null, listOf(bean))
            Container.get().addPageToMap(pageData)
            val intent = Intent(requireContext(), VActivity::class.java).apply {
                putExtra(Params.POSITION, 0)
                putExtra(Params.PAGE_UUID, uuid)
            }
            startActivity(intent)
        }
    }

    companion object {
        const val ARG_SERIES_ID = "series_id"

        fun newInstance(seriesId: Long): NovelSeriesFragment = NovelSeriesFragment().apply {
            arguments = Bundle().apply { putLong(ARG_SERIES_ID, seriesId) }
        }
    }
}
