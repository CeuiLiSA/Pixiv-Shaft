package ceui.pixiv.ui.novel

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.lisa.activities.TemplateActivity
import ceui.lisa.activities.UActivity
import ceui.lisa.activities.VActivity
import ceui.lisa.core.Container
import ceui.lisa.core.PageData
import ceui.lisa.databinding.FragmentPixivListBinding
import ceui.lisa.databinding.ItemBigReadButtonBinding
import ceui.lisa.databinding.LayoutNovelTopActionsBinding
import ceui.lisa.models.IllustsBean
import ceui.lisa.utils.Params
import ceui.loxia.Client
import ceui.loxia.Novel
import ceui.loxia.ObjectPool
import ceui.loxia.combineLatest
import ceui.pixiv.session.SessionManager
import ceui.pixiv.ui.common.FitsSystemWindowFragment
import ceui.pixiv.ui.common.ListMode
import ceui.pixiv.ui.common.PixivFragment
import ceui.pixiv.ui.common.constructVM
import ceui.pixiv.ui.common.createResponseStore
import ceui.pixiv.ui.common.pixivValueViewModel
import ceui.pixiv.ui.common.setUpRefreshState
import ceui.pixiv.ui.common.shareNovel
import ceui.pixiv.ui.common.viewBinding
import ceui.pixiv.ui.task.DownloadNovelTask
import ceui.pixiv.ui.works.blurBackground
import ceui.pixiv.utils.setOnClick
import ceui.pixiv.ui.detail.showV3Menu
import kotlinx.coroutines.launch
import java.util.UUID


class NovelTextFragment : PixivFragment(R.layout.fragment_pixiv_list), FitsSystemWindowFragment,
    NovelSeriesActionReceiver {

    private val binding by viewBinding(FragmentPixivListBinding::bind)
    private val novelId: Long by lazy { arguments?.getLong(Params.NOVEL_ID, 0L) ?: 0L }
    private val bgViewModel by pixivValueViewModel(
        dataFetcher = {
            Client.appApi.getUserBookmarkedIllusts(
                SessionManager.loggedInUid,
                Params.TYPE_PUBLIC
            )
        },
        responseStore = createResponseStore({ "user-${SessionManager.loggedInUid}-bookmarked-illusts" })
    )
    private val textModel by constructVM({ novelId }) { id ->
        NovelTextViewModel(id)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setUpRefreshState(binding, textModel, ListMode.VERTICAL)
        bgViewModel.result.observe(viewLifecycleOwner) { resp ->
            resp.displayList.getOrNull(novelId.mod(10))?.let {
                ObjectPool.update(it)
                blurBackground(binding, it.id)
            }
        }
        binding.toolbarLayout.root.visibility = View.GONE

        val bottomView = ItemBigReadButtonBinding.inflate(layoutInflater)
        binding.bottomCovered.isVisible = true
        binding.bottomCovered.addView(bottomView.root)
        bottomView.btnRead.setOnClick {
            val ctx = requireContext()
            val intent = Intent(ctx, TemplateActivity::class.java).apply {
                putExtra(TemplateActivity.EXTRA_FRAGMENT, "小说正文")
                putExtra(Params.NOVEL_ID, novelId)
            }
            ctx.startActivity(intent)
        }

        val topActions = LayoutNovelTopActionsBinding.inflate(layoutInflater)
        // Account for the status bar: TemplateActivity may render behind it in
        // some flavors, so shift our overlay down by the system-reported
        // status bar height to keep the icons out from under the clock/battery.
        val statusBarH = com.blankj.utilcode.util.BarUtils.getStatusBarHeight()
        topActions.root.setPadding(
            topActions.root.paddingLeft,
            topActions.root.paddingTop + statusBarH,
            topActions.root.paddingRight,
            topActions.root.paddingBottom,
        )
        val rootLayout = binding.root as androidx.constraintlayout.widget.ConstraintLayout
        val topLp = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams(
            androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.WRAP_CONTENT,
            androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.WRAP_CONTENT,
        ).apply {
            topToTop = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
            endToEnd = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
        }
        rootLayout.addView(topActions.root, topLp)
        val liveNovel = ObjectPool.get<Novel>(novelId)
        combineLatest(
            liveNovel,
            textModel.webNovel
        ).observe(viewLifecycleOwner) { (novel, webNovel) ->
            if (novel != null) {
                runOnceWithinFragmentLifecycle("visit-novel-${novelId}") {
                    val bean = Shaft.sGson.fromJson(Shaft.sGson.toJson(novel), ceui.lisa.models.NovelBean::class.java)
                    ceui.lisa.utils.PixivOperate.insertNovelViewHistory(bean)
                }
            }

            topActions.btnShare.setOnClick {
                if (novel != null) shareNovel(novel)
            }
            topActions.btnMore.setOnClick {
                if (novel == null) return@setOnClick
                showV3Menu {
                    item(getString(R.string.view_comments), R.drawable.ic_baseline_comment_24) {
                        val intent = Intent(requireContext(), TemplateActivity::class.java).apply {
                            putExtra(TemplateActivity.EXTRA_FRAGMENT, "相关评论")
                            putExtra(Params.NOVEL_ID, novelId.toInt())
                        }
                        startActivity(intent)
                    }
                    item("复制链接", R.drawable.ic_baseline_launch_24) {
                        ceui.lisa.utils.Common.copy(requireContext(), ceui.pixiv.ui.common.NOVEL_URL_HEAD + novelId)
                    }
                }
            }
        }

    }

    // Classic 分支没有 NavController，把所有 PixivFragment 里走 pushFragment 的
    // action receiver 方法一律改走 Intent → 对应 Activity。
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
        fun newInstance(novelId: Long): NovelTextFragment = NovelTextFragment().apply {
            arguments = Bundle().apply { putLong(Params.NOVEL_ID, novelId) }
        }
    }
}
