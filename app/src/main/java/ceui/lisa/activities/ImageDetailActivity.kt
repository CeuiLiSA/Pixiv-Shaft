package ceui.lisa.activities

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.fragment.app.Fragment
import androidx.activity.viewModels
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.FragmentPagerAdapter
import androidx.viewpager.widget.ViewPager
import ceui.lisa.R
import ceui.lisa.databinding.ActivityImageDetailBinding
import ceui.lisa.download.IllustDownload
import ceui.lisa.fragments.FragmentImageDetail
import ceui.lisa.helper.PageTransformerHelper
import ceui.lisa.models.IllustsBean
import ceui.lisa.utils.Common
import ceui.lisa.utils.Params
import ceui.lisa.utils.PixivOperate
import ceui.pixiv.ui.task.NamedUrl
import ceui.pixiv.ui.task.TaskPool
import ceui.pixiv.ui.upscale.ModelPickerDialog
import ceui.pixiv.ui.upscale.UpscaleStatus
import ceui.pixiv.ui.upscale.UpscaleTaskPool
import ceui.pixiv.ui.upscale.UpscaleTask
import ceui.pixiv.ui.upscale.UpscaleModel
import ceui.pixiv.ui.works.ToggleToolnarViewModel
import com.google.android.material.progressindicator.CircularProgressIndicator
import ceui.pixiv.utils.animateFadeInQuickly
import ceui.pixiv.utils.animateFadeOutQuickly
import java.io.UnsupportedEncodingException
import java.net.URLDecoder
import java.util.Locale

/**
 * 图片二级详情
 */
class ImageDetailActivity : BaseActivity<ActivityImageDetailBinding?>() {
    private var mIllustsBean: IllustsBean? = null
    private var localIllust: List<String>? = ArrayList()
    private var currentPage: TextView? = null
    private var downloadSingle: TextView? = null
    private var currentSize: TextView? = null
    private var index = 0
    private val viewModel by viewModels<ToggleToolnarViewModel>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        (this as? ComponentActivity)?.enableEdgeToEdge()
    }

    override fun initLayout(): Int {
        return R.layout.activity_image_detail
    }

    override fun initView() {
        val dataType = intent.getStringExtra("dataType")
        baseBind!!.viewPager.setPageTransformer(true, PageTransformerHelper.getCurrentTransformer())
        val windowInsetsController = WindowInsetsControllerCompat(
            window,
            window.decorView
        )
        val infoItems = listOf(
            baseBind?.bottomRela
        )
        windowInsetsController.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        viewModel.isFullscreenMode.observe(this) { isFullScreen ->
            if (isFullScreen) {
                windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())
                infoItems.forEach {
                    it?.animateFadeOutQuickly()
                }
            } else {
                windowInsetsController.show(WindowInsetsCompat.Type.systemBars())
                infoItems.forEach {
                    it?.animateFadeInQuickly()
                }
            }
        }
        if ("二级详情" == dataType) {
            currentSize = findViewById(R.id.current_size)
            currentPage = findViewById(R.id.current_page)
            downloadSingle = findViewById(R.id.download_this_one)
            mIllustsBean = intent.getSerializableExtra("illust") as IllustsBean?
            index = intent.getIntExtra("index", 0)
            if (mIllustsBean == null) {
                return
            }
            findViewById<View>(R.id.btn_ai_upscale).setOnClickListener {
                val illust = mIllustsBean ?: return@setOnClickListener
                val pageIndex = baseBind!!.viewPager.currentItem
                ModelPickerDialog.show(supportFragmentManager) { model ->
                    performAiUpscale(illust, pageIndex, model)
                }
            }
            baseBind!!.viewPager.adapter = object : FragmentPagerAdapter(
                supportFragmentManager
            ) {
                override fun getItem(i: Int): Fragment {
                    return FragmentImageDetail.newInstance(mIllustsBean, i)
                }

                override fun getCount(): Int {
                    return mIllustsBean!!.page_count
                }
            }
            baseBind!!.viewPager.currentItem = index
            checkDownload(index)
            downloadSingle?.setOnClickListener(View.OnClickListener {
                IllustDownload.downloadIllustCertainPage(
                    mIllustsBean,
                    baseBind!!.viewPager.currentItem,
                    mContext as BaseActivity<*>
                )
                if (Shaft.sSettings.isAutoPostLikeWhenDownload && !mIllustsBean!!.isIs_bookmarked) {
                    PixivOperate.postLikeDefaultStarType(mIllustsBean)
                }
            })
            baseBind!!.viewPager.addOnPageChangeListener(object : ViewPager.OnPageChangeListener {
                override fun onPageScrolled(i: Int, v: Float, i1: Int) {
                }

                override fun onPageSelected(i: Int) {
                    checkDownload(i)
                    currentPage?.setText(
                        String.format(
                            Locale.getDefault(),
                            "第 %d/%d P",
                            i + 1,
                            mIllustsBean!!.page_count
                        )
                    )
                }

                override fun onPageScrollStateChanged(i: Int) {
                }
            })
            if (mIllustsBean!!.page_count == 1) {
                currentPage?.setVisibility(View.INVISIBLE)
            } else {
                currentPage?.setText(
                    String.format(
                        Locale.getDefault(),
                        "第 %d/%d P",
                        index + 1,
                        mIllustsBean!!.page_count
                    )
                )
            }
        } else if ("下载详情" == dataType) {
            findViewById<View>(R.id.btn_ai_upscale).visibility = View.GONE
            currentPage = findViewById(R.id.current_page)
            downloadSingle = findViewById(R.id.download_this_one)
            localIllust = intent.getSerializableExtra("illust") as List<String>?
            index = intent.getIntExtra("index", 0)

            baseBind!!.viewPager.adapter = object : FragmentPagerAdapter(
                supportFragmentManager
            ) {
                override fun getItem(i: Int): Fragment {
                    return FragmentImageDetail.newInstance(localIllust!![i])
                }

                override fun getCount(): Int {
                    return localIllust!!.size
                }
            }
            currentPage?.setVisibility(View.INVISIBLE)
            baseBind!!.viewPager.currentItem = index
            baseBind!!.viewPager.addOnPageChangeListener(object : ViewPager.OnPageChangeListener {
                override fun onPageScrolled(i: Int, v: Float, i1: Int) {
                }

                override fun onPageSelected(i: Int) {
                    try {
                        downloadSingle?.setText(
                            String.format(
                                "%s%s", getString(R.string.file_path),
                                URLDecoder.decode(localIllust!![i], "utf-8")
                            )
                        )
                    } catch (e: UnsupportedEncodingException) {
                        e.printStackTrace()
                    }
                }

                override fun onPageScrollStateChanged(i: Int) {
                }
            })
            try {
                downloadSingle?.setText(
                    String.format(
                        "%s%s", getString(R.string.file_path),
                        URLDecoder.decode(localIllust!![index], "utf-8")
                    )
                )
            } catch (e: UnsupportedEncodingException) {
                e.printStackTrace()
            }
        }
    }

    private fun checkDownload(i: Int) {
        downloadSingle!!.visibility = if (Common.isIllustDownloaded(
                mIllustsBean,
                i
            )
        ) View.INVISIBLE else View.VISIBLE
    }

    override fun initData() {
        postponeEnterTransition()
    }

    override fun onBackPressed() {
        if (index == baseBind!!.viewPager.currentItem) {
            super.onBackPressed()
        } else {
            mActivity.finish()
        }
    }

    private fun performAiUpscale(illust: IllustsBean, pageIndex: Int, model: UpscaleModel) {
        val imageUrl = IllustDownload.getUrl(illust, pageIndex, Params.IMAGE_RESOLUTION_ORIGINAL)
            ?: IllustDownload.getUrl(illust, pageIndex, Params.IMAGE_RESOLUTION_LARGE) ?: return

        val loadTask = TaskPool.getLoadTask(NamedUrl("", imageUrl))
        loadTask.result.observe(this) { file ->
            if (file != null) {
                val key = UpscaleTask.illustKey(illust.id * 100 + pageIndex)
                val task = UpscaleTaskPool.startTask(key, this, file, file.absolutePath, model)
                observeUpscaleTask(task)
            }
        }
    }

    private fun observeUpscaleTask(task: UpscaleTask) {
        val overlayRoot = findViewById<View>(R.id.ai_overlay_root) ?: return
        val loadingState = findViewById<View>(R.id.ai_loading_state)
        val doneState = findViewById<View>(R.id.ai_done_state)
        val viewCompare = findViewById<View>(R.id.ai_view_compare)
        val dismiss = findViewById<View>(R.id.ai_dismiss)
        val progressRing = findViewById<CircularProgressIndicator>(R.id.ai_progress_ring)
        val progressText = findViewById<TextView>(R.id.ai_progress_text)
        val statusText = findViewById<TextView>(R.id.ai_status_text)
        val etaText = findViewById<TextView>(R.id.ai_eta_text)

        viewCompare.setOnClickListener {
            val result = task.resultFile.value ?: return@setOnClickListener
            val intent = android.content.Intent(this, TemplateActivity::class.java)
            intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "画质增强对比")
            intent.putExtra("upscaled_path", result.absolutePath)
            intent.putExtra("original_path", task.originalFilePath)
            startActivity(intent)
            overlayRoot.visibility = View.GONE
            UpscaleTaskPool.removeTask(task.taskKey)
        }
        dismiss.setOnClickListener {
            overlayRoot.animate().alpha(0f).setDuration(300).withEndAction {
                overlayRoot.visibility = View.GONE
            }.start()
            UpscaleTaskPool.removeTask(task.taskKey)
        }

        task.status.observe(this) { status ->
            when (status) {
                UpscaleStatus.Running -> {
                    overlayRoot.visibility = View.VISIBLE
                    loadingState.visibility = View.VISIBLE
                    doneState.visibility = View.GONE
                    if (overlayRoot.alpha < 1f) {
                        overlayRoot.alpha = 0f
                        overlayRoot.animate().alpha(1f).setDuration(300).start()
                    }
                    statusText.text = getString(R.string.string_ai_upscale_running)
                }
                UpscaleStatus.Done -> {
                    loadingState.visibility = View.GONE
                    doneState.visibility = View.VISIBLE
                    overlayRoot.visibility = View.VISIBLE
                    overlayRoot.alpha = 1f
                }
                UpscaleStatus.Failed -> {
                    overlayRoot.animate().alpha(0f).setDuration(300).withEndAction {
                        overlayRoot.visibility = View.GONE
                    }.start()
                    Common.showToast(R.string.string_ai_upscale_failed)
                    UpscaleTaskPool.removeTask(task.taskKey)
                }
                else -> {}
            }
        }
        task.progress.observe(this) { percent ->
            val p = (percent * 100).toInt()
            progressRing.setProgressCompat(p, true)
            progressText.text = "$p%"
        }
        task.eta.observe(this) { eta ->
            etaText.text = if (eta > 0) "预计 ${String.format("%.0f", eta)} 秒后完成" else ""
        }
    }

    override fun hideStatusBar(): Boolean {
        return true
    }
}
