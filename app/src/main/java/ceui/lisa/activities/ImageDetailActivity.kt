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
import ceui.pixiv.ui.translate.MangaOcrModel
import ceui.pixiv.ui.translate.MangaOcrModelManager
import ceui.pixiv.ui.translate.MangaTranslator
import ceui.pixiv.ui.translate.SakuraModel
import ceui.pixiv.ui.translate.SakuraModelManager
import ceui.pixiv.ui.upscale.BackgroundRemover
import ceui.pixiv.ui.upscale.ModelPickerDialog
import ceui.pixiv.ui.upscale.RembgModelPickerDialog
import ceui.pixiv.ui.upscale.RembgModel
import ceui.pixiv.ui.upscale.UpscaleStatus
import ceui.pixiv.ui.upscale.UpscaleTaskPool
import ceui.pixiv.ui.upscale.UpscaleTask
import ceui.pixiv.ui.upscale.UpscaleModel
import ceui.pixiv.ui.works.ToggleToolnarViewModel
import com.google.android.material.progressindicator.CircularProgressIndicator
import ceui.pixiv.utils.animateFadeInQuickly
import ceui.pixiv.utils.animateFadeOutQuickly
import android.content.Intent
import android.widget.ImageView
import androidx.core.view.ViewCompat
import ceui.lisa.utils.QMUIMenuPopup
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.UnsupportedEncodingException
import java.net.URLDecoder
import java.util.Locale

/**
 * 图片二级详情
 */
class ImageDetailActivity : BaseActivity<ActivityImageDetailBinding?>() {
    var mIllustsBean: IllustsBean? = null
        private set
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
        val btnAi = findViewById<View>(R.id.btn_ai_menu)
        ViewCompat.setOnApplyWindowInsetsListener(btnAi) { v, windowInsets ->
            val statusBarHeight = windowInsets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            val lp = v.layoutParams as android.widget.RelativeLayout.LayoutParams
            lp.topMargin = statusBarHeight + 8
            v.layoutParams = lp
            windowInsets
        }
        val infoItems = listOf(
            baseBind?.bottomRela,
            btnAi
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
            val btnAiMenu = findViewById<ImageView>(R.id.btn_ai_menu)
            btnAiMenu.visibility = View.VISIBLE
            btnAiMenu.setOnClickListener { anchor ->
                val titles = arrayOf<CharSequence>(
                    getString(R.string.string_ai_upscale),
                    getString(R.string.string_ai_rembg)
                )
                QMUIMenuPopup.show(this, anchor, titles) { index, _ ->
                    val illust = mIllustsBean ?: return@show
                    val pageIndex = baseBind!!.viewPager.currentItem
                    when (index) {
                        0 -> ModelPickerDialog.pickOrUseDefault(supportFragmentManager) { model ->
                            performAiUpscale(illust, pageIndex, model)
                        }
                        1 -> RembgModelPickerDialog.pickOrUseDefault(supportFragmentManager) { model ->
                            performAiRembg(illust, pageIndex, model)
                        }
                    }
                }
            }
            baseBind!!.viewPager.adapter = object : FragmentPagerAdapter(
                supportFragmentManager
            ) {
                override fun getItem(i: Int): Fragment {
                    return FragmentImageDetail.newInstance(i)
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
        } else if (ceui.pixiv.ui.common.ImageUrlViewer.DATA_TYPE_URL_SINGLE == dataType) {
            findViewById<View>(R.id.btn_ai_menu).visibility = View.GONE
            currentPage = findViewById(R.id.current_page)
            currentPage?.visibility = View.INVISIBLE
            downloadSingle = findViewById(R.id.download_this_one)
            downloadSingle?.visibility = View.INVISIBLE
            val singleUrl = intent.getStringExtra(Params.URL)
            val singleTitle = intent.getStringExtra(Params.TITLE)
            if (singleUrl.isNullOrEmpty()) {
                finish()
                return
            }
            baseBind!!.viewPager.adapter = object : FragmentPagerAdapter(
                supportFragmentManager
            ) {
                override fun getItem(i: Int): Fragment =
                    FragmentImageDetail.newInstance(singleUrl, singleTitle)

                override fun getCount(): Int = 1
            }
        } else if ("下载详情" == dataType) {
            findViewById<View>(R.id.btn_ai_menu).visibility = View.GONE
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
        val illust = mIllustsBean ?: return
        lifecycleScope.launch {
            val downloaded = withContext(Dispatchers.IO) {
                Common.isIllustDownloaded(illust, i)
            }
            downloadSingle?.visibility = if (downloaded) View.INVISIBLE else View.VISIBLE
        }
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

    private fun performAiRembg(illust: IllustsBean, pageIndex: Int, model: RembgModel) {
        val imageUrl = IllustDownload.getUrl(illust, pageIndex, Params.IMAGE_RESOLUTION_ORIGINAL)
            ?: IllustDownload.getUrl(illust, pageIndex, Params.IMAGE_RESOLUTION_LARGE) ?: return

        val overlayRoot = findViewById<View>(R.id.ai_overlay_root) ?: return
        val loadingState = findViewById<View>(R.id.ai_loading_state)
        val doneState = findViewById<View>(R.id.ai_done_state)
        val progressRing = findViewById<CircularProgressIndicator>(R.id.ai_progress_ring)
        val progressText = findViewById<TextView>(R.id.ai_progress_text)
        val statusText = findViewById<TextView>(R.id.ai_status_text)

        overlayRoot.visibility = View.VISIBLE
        loadingState.visibility = View.VISIBLE
        doneState.visibility = View.GONE
        overlayRoot.alpha = 0f
        overlayRoot.animate().alpha(1f).setDuration(300).start()
        statusText.text = getString(R.string.string_ai_rembg_running)
        progressRing.isIndeterminate = true
        progressText.visibility = View.GONE

        val loadTask = TaskPool.getLoadTask(NamedUrl("", imageUrl))
        loadTask.result.observe(this) { file ->
            if (file != null) {
                lifecycleScope.launch {
                    val result = BackgroundRemover.removeBackground(this@ImageDetailActivity, file, model) { percent ->
                        runOnUiThread {
                            progressRing.isIndeterminate = false
                            progressText.visibility = View.VISIBLE
                            val p = (percent * 100).toInt()
                            progressRing.setProgressCompat(p, true)
                            progressText.text = "$p%"
                        }
                    }
                    overlayRoot.animate().alpha(0f).setDuration(300).withEndAction {
                        overlayRoot.visibility = View.GONE
                    }.start()
                    if (result != null) {
                        val intent = Intent(this@ImageDetailActivity, TemplateActivity::class.java)
                        intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "主体高亮")
                        intent.putExtra("original_path", file.absolutePath)
                        intent.putExtra("rembg_path", result.absolutePath)
                        startActivity(intent)
                    } else {
                        Common.showToast(R.string.string_ai_rembg_failed)
                    }
                }
            }
        }
    }

    private fun performAiMangaTranslation(illust: IllustsBean, pageIndex: Int) {
        // Check manga-ocr model
        val ocrModel = MangaOcrModel.MANGA_OCR_BASE
        if (!MangaOcrModelManager.isModelReady(this, ocrModel)) {
            Common.showToast(R.string.string_manga_ocr_model_needed)
            val intent = Intent(this, TemplateActivity::class.java)
            intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "漫画OCR模型下载")
            intent.putExtra("manga_ocr_model_name", ocrModel.name)
            startActivity(intent)
            return
        }

        // Check Sakura translation model
        val sakuraModel = SakuraModel.SAKURA_1_5B
        if (!SakuraModelManager.isModelReady(this, sakuraModel)) {
            Common.showToast(R.string.string_sakura_model_needed)
            val intent = Intent(this, TemplateActivity::class.java)
            intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "Sakura翻译模型下载")
            intent.putExtra("sakura_model_name", sakuraModel.name)
            startActivity(intent)
            return
        }

        val imageUrl = IllustDownload.getUrl(illust, pageIndex, Params.IMAGE_RESOLUTION_ORIGINAL)
            ?: IllustDownload.getUrl(illust, pageIndex, Params.IMAGE_RESOLUTION_LARGE) ?: return

        val overlayRoot = findViewById<View>(R.id.ai_overlay_root) ?: return
        val loadingState = findViewById<View>(R.id.ai_loading_state)
        val doneState = findViewById<View>(R.id.ai_done_state)
        val statusText = findViewById<TextView>(R.id.ai_status_text)
        val progressRing = findViewById<CircularProgressIndicator>(R.id.ai_progress_ring)
        val progressText = findViewById<TextView>(R.id.ai_progress_text)

        overlayRoot.visibility = View.VISIBLE
        loadingState.visibility = View.VISIBLE
        doneState.visibility = View.GONE
        overlayRoot.alpha = 0f
        overlayRoot.animate().alpha(1f).setDuration(300).start()
        statusText.text = getString(R.string.string_ai_manga_translating)
        progressRing.isIndeterminate = true
        progressText.visibility = View.GONE

        val loadTask = TaskPool.getLoadTask(NamedUrl("", imageUrl))
        loadTask.result.observe(this) { file ->
            if (file != null) {
                lifecycleScope.launch {
                    val result = MangaTranslator.translate(this@ImageDetailActivity, file) { stage, detail ->
                        runOnUiThread {
                            statusText.text = detail
                        }
                    }
                    overlayRoot.animate().alpha(0f).setDuration(300).withEndAction {
                        overlayRoot.visibility = View.GONE
                    }.start()
                    if (result != null) {
                        val intent = Intent(this@ImageDetailActivity, TemplateActivity::class.java)
                        intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "漫画翻译")
                        intent.putExtra("translated_path", result.outputFile.absolutePath)
                        intent.putExtra("original_path", file.absolutePath)
                        startActivity(intent)
                    } else {
                        Common.showToast(R.string.string_ai_manga_translate_failed)
                    }
                }
            }
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
                    statusText.text = getString(R.string.string_ai_upscale_running, task.model.displayName)
                }
                UpscaleStatus.Done -> {
                    val result = task.resultFile.value
                    if (result != null && lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) {
                        overlayRoot.animate().alpha(0f).setDuration(300).withEndAction {
                            overlayRoot.visibility = View.GONE
                        }.start()
                        val intent = android.content.Intent(this@ImageDetailActivity, TemplateActivity::class.java)
                        intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "画质增强对比")
                        intent.putExtra("upscaled_path", result.absolutePath)
                        intent.putExtra("original_path", task.originalFilePath)
                        startActivity(intent)
                        UpscaleTaskPool.removeTask(task.taskKey)
                    } else {
                        loadingState.visibility = View.GONE
                        doneState.visibility = View.VISIBLE
                        overlayRoot.visibility = View.VISIBLE
                        overlayRoot.alpha = 1f
                    }
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
