package ceui.pixiv.ui.detail

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.fragment.app.Fragment
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import ceui.lisa.R
import ceui.lisa.activities.BaseActivity
import ceui.lisa.activities.Shaft
import ceui.lisa.activities.TemplateActivity
import ceui.lisa.download.IllustDownload
import ceui.lisa.utils.Common
import ceui.lisa.utils.Dev
import ceui.lisa.utils.Params
import ceui.lisa.utils.PixivOperate
import ceui.lisa.utils.ShareIllust
import ceui.pixiv.api.model.Illust
import ceui.pixiv.cache.ObjectPool
import ceui.pixiv.download.DownloadRecordStateSource
import ceui.pixiv.snapshot.showSnapshotCreateDialog
import ceui.pixiv.ui.bookmark.SelectTagBottomSheet
import ceui.pixiv.ui.detail.frames.openUgoiraFrames
import ceui.pixiv.ui.muted.MuteTagSheet
import ceui.pixiv.ui.navigation.TemplateRoute
import ceui.pixiv.ui.share.saveArtworkPoster
import ceui.pixiv.ui.share.shareFirstImage
import ceui.pixiv.ui.upscale.IllustAiHelper
import ceui.pixiv.ui.upscale.IllustAiStore
import ceui.pixiv.ui.upscale.UpscaleStatus
import ceui.pixiv.ui.upscale.ModelPickerDialog
import ceui.pixiv.ui.upscale.RembgModelPickerDialog
import ceui.pixiv.utils.playToggleHaptic
import ceui.pixiv.utils.setOnClick
import ceui.pixiv.ui.filter.openImageFilter
import ceui.pixiv.utils.toTagsBeans
import ceui.pixiv.wallpaper.WallpaperSetter
import ceui.pixiv.witstudio.dialog.WitDialog
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * 详情页的作品操作入口:悬浮胶囊上的下载 / 收藏(单击与长按)、下载完成后刷新胶囊、右上角更多菜单。
 *
 * 只管「点了做什么」;胶囊的着色、位置、显隐归 [V3FabBarController] 与宿主。快照模式不建本类
 * (快照只读,胶囊点击由宿主统一 toast)。一个视图生命周期一个实例。
 *
 * @param currentPageIndex 页码浮标当前指着的页(0 基,不在场时为 -1),海报按它取图。
 * @param aiHelper 宿主随视图持有的 AI 助手,菜单点到时现取。
 */
internal class ArtworkV3Actions(
    private val fragment: Fragment,
    private val illustId: Long,
    private val viewModel: ArtworkV3ViewModel,
    private val fabBar: V3FabBarController,
    private val currentPageIndex: () -> Int,
    private val aiHelper: () -> IllustAiHelper?,
    private val onOpenComicReader: () -> Unit,
    private val onForceOriginal: () -> Unit,
) {
    private val context: Context
        get() = fragment.requireContext()

    private fun currentIllust(): Illust? = ObjectPool.get<Illust>(illustId).value

    /** 下载 / 收藏按钮的单击与长按,以及下载完成广播。 */
    fun bindFabBar() {
        bindDownload()
        bindDownloadFinishReceiver()
        bindBookmark()
    }

    private fun bindDownload() {
        fabBar.binding.fabDownloadContainer.setOnClick {
            // 下载 FAB 的两条 bean 缺失早退(这里 + ViewModel.triggerDownload)会让点击彻底
            // 无声无息 —— issue #1105「点了下载什么都没发生」就是这个形态。经典详情页
            // (FragmentIllust.setupDownloadButton)一直有这条点击日志，V3 侧补齐，好让
            // 「点击没到」和「点击到了但入队被丢」在日志里分得开。
            val illust = currentIllust() ?: run {
                Timber.tag(DownloadRecordStateSource.LOG_TAG)
                    .w("click dropped illustId=%d reason=bean_missing", illustId)
                return@setOnClick
            }
            val currentDownloadState = viewModel.downloadFabState.value
            if (currentDownloadState is DownloadFab.Paused || currentDownloadState is DownloadFab.Resume) {
                Timber.tag(DownloadRecordStateSource.LOG_TAG).d("click resume paused download illustId=%d", illustId)
                viewModel.resumeDownload()
            } else {
                Timber.tag(DownloadRecordStateSource.LOG_TAG).d(
                    "click illustId=%d pages=%d resolution=%s",
                    illustId, illust.page_count, IllustDownload.defaultImageResolution(),
                )
                viewModel.triggerDownload(fragment.requireActivity() as? BaseActivity<*>)
                if (Shaft.sSettings.isAutoPostLikeWhenDownload && !illust.isBookmarked) {
                    fabBar.setBookmarked(true)
                    PixivOperate.postLikeDefaultStarType(illust)
                }
            }
        }
        fabBar.binding.fabDownloadContainer.setOnLongClickListener {
            val illust = currentIllust() ?: return@setOnLongClickListener true
            val baseAct = fragment.requireActivity() as? BaseActivity<*>
            val resNames =
                arrayOf(
                    context.getString(R.string.resolution_original),
                    context.getString(R.string.resolution_large),
                    context.getString(R.string.resolution_medium),
                    context.getString(R.string.resolution_square_medium),
                )
            val resValues =
                arrayOf(
                    Params.IMAGE_RESOLUTION_ORIGINAL,
                    Params.IMAGE_RESOLUTION_LARGE,
                    Params.IMAGE_RESOLUTION_MEDIUM,
                    Params.IMAGE_RESOLUTION_SQUARE_MEDIUM,
                )
            WitDialog.MenuDialogBuilder(context)
                .addItems(resNames) { dialog, which ->
                    if (illust.page_count == 1) {
                        IllustDownload.downloadIllustFirstPageWithResolution(
                            illust,
                            resValues[which],
                            baseAct,
                        )
                    } else {
                        IllustDownload.downloadIllustAllPagesWithResolution(
                            illust,
                            resValues[which],
                            baseAct,
                        )
                    }
                    viewModel.refreshDownloadFab()
                    dialog.dismiss()
                }
                .show()
            true
        }
    }

    /** Manager 下载完成广播 → 刷新 FAB(轮询期间不干扰)。注册随视图生命周期。 */
    private fun bindDownloadFinishReceiver() {
        val owner = fragment.viewLifecycleOwner
        val downloadFinishReceiver =
            object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    // ViewPager 的前后缓存页也注册了 receiver，但它们仅 STARTED；只让当前 RESUMED
                    // 页面查下载状态，避免一次完成广播唤醒三页 DB 探测。
                    if (
                        owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
                            !viewModel.isPollingProgress
                    ) {
                        viewModel.refreshDownloadFab()
                    }
                }
            }
        LocalBroadcastManager.getInstance(context)
            .registerReceiver(
                downloadFinishReceiver,
                IntentFilter(Params.DOWNLOAD_FINISH),
            )
        owner.lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onDestroy(owner: LifecycleOwner) {
                    LocalBroadcastManager.getInstance(context)
                        .unregisterReceiver(downloadFinishReceiver)
                }
            }
        )
    }

    private fun bindBookmark() {
        fabBar.binding.fabBookmark.setOnClick {
            val illust = currentIllust() ?: return@setOnClick
            val willBookmark = !illust.isBookmarked
            // 乐观着色与权威渲染(isBookmarked observer)同走 controller,取同一个内容色,
            // 避免取消收藏当帧闪一帧错色(详见 V3FabBarController.setBookmarked)。
            fabBar.setBookmarked(willBookmark)
            playToggleHaptic(it, willBookmark)
            PixivOperate.postLikeDefaultStarType(illust)
            if (willBookmark && Shaft.sSettings.isAutoDownloadAfterStar) {
                // 同样尊重「默认下载分辨率」。刻意**不**带 activity:收藏是个轻动作,不该顺手
                // 弹出 SAF 重选目录的对话框;闸门留给用户主动点下载 FAB 那条路径。
                IllustDownload.downloadIllustAllPagesWithResolution(
                    illust,
                    IllustDownload.defaultImageResolution(),
                )
            }
        }

        fabBar.binding.fabBookmark.setOnLongClickListener {
            val illust = currentIllust() ?: return@setOnLongClickListener true
            SelectTagBottomSheet.show(
                fragment,
                illust.id,
                Params.TYPE_ILLUST,
                illust.tagNames.toTypedArray(),
            )
            true
        }
    }

    fun showMoreMenu() {
        val illust = currentIllust() ?: return
        fragment.showV3Menu {
            if (illust.isGif()) {
                item(context.getString(R.string.ugoira_frames_menu), R.drawable.ic_ugoira_frames_24) {
                    openUgoiraFrames(context, illust)
                }
            }
            if (!illust.isGif() && illust.page_count == 1) {
                item(context.getString(R.string.comic_reader_enter_illust), R.drawable.ic_baseline_menu_book_24) {
                    onOpenComicReader()
                }
            }
            item(context.getString(R.string.share), R.drawable.ic_share_black_24dp) {
                object : ShareIllust(context, illust) {
                        override fun onPrepare() {}
                    }
                    .execute()
            }
            item(context.getString(R.string.string_454), R.drawable.ic_share_black_24dp) {
                fragment.shareFirstImage(illust)
            }
            item(context.getString(R.string.artwork_poster_save), R.drawable.ic_setcat_photo) {
                // 多图作品按右上角页码浮标当前指向的那一页生成；已经滑进简介区时回退首图。
                fragment.saveArtworkPoster(illust, currentPageIndex().coerceAtLeast(0))
            }
            item(context.getString(R.string.string_355_2), R.drawable.ic_baseline_launch_24) {
                Common.copy(context, ShareIllust.URL_Head + illust.id)
            }
            item(context.getString(R.string.snapshot_create), R.drawable.ic_baseline_get_app_20) {
                fragment.showSnapshotCreateDialog(illust)
            }
            // issue #898 / #515:详情页直接设壁纸(首图),不必先进全屏看图页。动图 original 是 zip,跳过。
            if (!illust.isGif()) {
                item(
                    context.getString(R.string.string_set_wallpaper),
                    R.drawable.ic_baseline_wallpaper_24,
                ) {
                    fragment.viewLifecycleOwner.lifecycleScope.launch {
                        WallpaperSetter.setFromIllust(fragment.requireActivity(), illust, 0)
                    }
                }
            }
            item(context.getString(R.string.string_1), R.drawable.ic_baseline_settings_24) {
                MuteTagSheet.show(fragment.childFragmentManager, illust.tags?.toTagsBeans(), illust.user)
            }
            item(context.getString(R.string.string_355), R.drawable.ic_visibility_off_black_24dp) {
                PixivOperate.muteIllust(illust)
            }
            item(context.getString(R.string.flag_post), R.drawable.ic_baseline_flag_24) {
                val intent = Intent(context, TemplateActivity::class.java)
                intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, TemplateRoute.FLAG_REASON.key)
                intent.putExtra(ceui.pixiv.ui.flag.FlagDescFragment.FlagObjectIdKey, illust.id)
                intent.putExtra(
                    ceui.pixiv.ui.flag.FlagDescFragment.FlagObjectTypeKey,
                    ceui.lisa.models.ObjectSpec.POST,
                )
                fragment.startActivity(intent)
            }
            if (!illust.isGif()) {
                item(context.getString(R.string.string_0), R.drawable.ic_remove_red_eye_black_24dp) {
                    onForceOriginal()
                }
                item(context.getString(R.string.string_ai_upscale), R.drawable.ic_upscale_add_photo) {
                    val pageIndex = currentPageIndex().coerceAtLeast(0)
                    val state = IllustAiStore.get(illust.id, pageIndex)
                    if (state?.hasUpscaleResult == true) {
                        Common.showToast(R.string.string_ai_upscale_already_completed)
                        return@item
                    }
                    if (state?.upscaleRunning == true || state?.upscaleStatus == UpscaleStatus.Queued) {
                        Common.showToast(R.string.string_ai_task_already_running)
                        return@item
                    }
                    ModelPickerDialog.pickOrUseDefault(fragment.childFragmentManager) { model ->
                        aiHelper()?.performUpscale(illust, model, pageIndex)
                    }
                }
                item(context.getString(R.string.string_ai_rembg), R.drawable.ic_baseline_filter_24) {
                    val pageIndex = currentPageIndex().coerceAtLeast(0)
                    val state = IllustAiStore.get(illust.id, pageIndex)
                    if (state?.hasRembgResult == true) {
                        Common.showToast(R.string.string_ai_rembg_already_completed)
                        return@item
                    }
                    if (state?.rembgRunning == true || state?.rembgStatus == UpscaleStatus.Queued) {
                        Common.showToast(R.string.string_ai_task_already_running)
                        return@item
                    }
                    RembgModelPickerDialog.pickOrUseDefault(fragment.childFragmentManager) { model ->
                        aiHelper()?.performRembg(illust, model, pageIndex)
                    }
                }
                item(context.getString(R.string.image_filter), R.drawable.ic_photo_filter_24) {
                    // 多图作品按页码浮标当前指向的那一页；已经滑进简介区时回退首图（同海报）。
                    openImageFilter(context, illust, currentPageIndex().coerceAtLeast(0))
                }
            }
            if (Dev.showPlazaShareInArtwork) {
                item(
                    context.getString(R.string.plaza_share_illust_to_plaza),
                    R.drawable.ic_plaza_feed_24,
                ) {
                    val intent = Intent(context, TemplateActivity::class.java)
                    intent.putExtra(
                        TemplateActivity.EXTRA_FRAGMENT,
                        TemplateRoute.PLAZA_COMPOSE.key,
                    )
                    intent.putExtra(
                        ceui.pixiv.plaza.ui.PlazaComposeFragment.ARG_PREFILL_ILLUST_ID,
                        illust.id,
                    )
                    intent.putExtra(
                        ceui.pixiv.plaza.ui.PlazaComposeFragment.ARG_OBJECT_TYPE,
                        if (illust.type == "manga") "manga" else "illust",
                    )
                    fragment.startActivity(intent)
                }
            }
        }
    }
}
