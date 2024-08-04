package ceui.lisa.fragments

import android.os.Bundle
import android.text.TextUtils
import android.view.View
import androidx.fragment.app.viewModels
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.lisa.core.GlideApp
import ceui.lisa.databinding.FragmentImageDetailBinding
import ceui.lisa.download.IllustDownload
import ceui.lisa.models.IllustsBean
import ceui.lisa.utils.Common
import ceui.lisa.utils.GlideUrlChild
import ceui.lisa.utils.Params
import ceui.loxia.launchSuspend
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import com.github.panpf.sketch.loadImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.jessyan.progressmanager.ProgressListener
import me.jessyan.progressmanager.ProgressManager
import me.jessyan.progressmanager.body.ProgressInfo
import java.io.File

class ImageFileViewModel : ViewModel() {
    val fileLiveData = MutableLiveData<File>()
    var isHighQualityImageLoaded: Boolean = false
    val progressLiveData = MutableLiveData<Int>()
}


class FragmentImageDetail : BaseFragment<FragmentImageDetailBinding?>() {
    private var mIllustsBean: IllustsBean? = null
    private var index = 0
    private var url: String? = null

    private val fileViewModel by viewModels<ImageFileViewModel>()

    public override fun initBundle(bundle: Bundle) {
        url = bundle.getString(Params.URL)
        mIllustsBean = bundle.getSerializable(Params.CONTENT) as IllustsBean?
        index = bundle.getInt(Params.INDEX)
    }

    public override fun initLayout() {
        mLayoutID = R.layout.fragment_image_detail
    }

    override fun initView() {
        baseBind.emptyActionButton.setOnClickListener { v: View? -> loadImage() }
        //插画二级详情保持屏幕常亮
        if (Shaft.sSettings.isIllustDetailKeepScreenOn) {
            baseBind.root.keepScreenOn = true
        }

        fileViewModel.fileLiveData.observe(viewLifecycleOwner) { file ->
            baseBind.bigImage.loadImage(file)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        loadImage()
    }

    private fun loadImage() {
        baseBind.emptyFrame.visibility = View.GONE
        baseBind.progressLayout.root.visibility = View.VISIBLE
        val imageUrl: String?
        if (mIllustsBean == null && !TextUtils.isEmpty(url)) {
            imageUrl = url
        } else {
            val originUrl = IllustDownload.getUrl(mIllustsBean, index)
            imageUrl = if (Shaft.getMMKV().decodeBool(originUrl)) {
                originUrl
            } else {
                if (!TextUtils.isEmpty(url)) {
                    url
                } else {
                    IllustDownload.getUrl(mIllustsBean, index, Params.IMAGE_RESOLUTION_ORIGINAL)
                }
            }
        }
        ProgressManager.getInstance().addResponseListener(imageUrl, object : ProgressListener {
            override fun onProgress(progressInfo: ProgressInfo) {
                Common.showLog("dsaasdsawq2 ${progressInfo.percent.toFloat()}")
                baseBind.progressLayout.donutProgress.progress = progressInfo.percent.toFloat()
            }

            override fun onError(id: Long, e: Exception) {
            }
        })

        launchSuspend {
            withContext(Dispatchers.IO) {
                try {
                    val file = GlideApp.with(mContext)
                        .asFile()
                        .load(GlideUrlChild(imageUrl))
                        .submit()
                        .get()
                    withContext(Dispatchers.Main) {
                        baseBind.progressLayout.root.visibility = View.GONE
                        baseBind.emptyFrame.visibility = View.GONE
                    }
                    fileViewModel.isHighQualityImageLoaded = true
                    fileViewModel.fileLiveData.postValue(file)
                } catch (ex: Exception) {
                    ex.printStackTrace()
                    withContext(Dispatchers.Main) {
                        baseBind.progressLayout.root.visibility = View.GONE
                        baseBind.emptyFrame.visibility = View.VISIBLE
                    }
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putSerializable("mIllustsBean", mIllustsBean)
        outState.putInt("index", index)
    }

    companion object {
        @JvmStatic
        fun newInstance(illustsBean: IllustsBean?, index: Int): FragmentImageDetail {
            val args = Bundle()
            args.putSerializable(Params.CONTENT, illustsBean)
            args.putInt(Params.INDEX, index)
            val fragment = FragmentImageDetail()
            fragment.arguments = args
            return fragment
        }

        @JvmStatic
        fun newInstance(pUrl: String?): FragmentImageDetail {
            val args = Bundle()
            args.putString(Params.URL, pUrl)
            val fragment = FragmentImageDetail()
            fragment.arguments = args
            return fragment
        }
    }
}
