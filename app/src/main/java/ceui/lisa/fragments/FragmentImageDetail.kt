package ceui.lisa.fragments

import android.os.Bundle
import android.text.TextUtils
import android.view.View
import androidx.fragment.app.viewModels
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.lifecycleScope
import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.lisa.databinding.FragmentImageDetailBinding
import ceui.lisa.download.IllustDownload
import ceui.lisa.models.IllustsBean
import ceui.lisa.utils.Params
import ceui.pixiv.ui.common.setUpFullScreen
import ceui.pixiv.ui.common.setUpWithTaskStatus
import ceui.pixiv.ui.task.NamedUrl
import ceui.pixiv.ui.task.TaskPool
import ceui.pixiv.ui.works.ToggleToolnarViewModel
import ceui.refactor.setOnClick
import com.github.panpf.sketch.loadImage
import kotlinx.coroutines.launch

class FragmentImageDetail : BaseFragment<FragmentImageDetailBinding?>() {
    private var mIllustsBean: IllustsBean? = null
    private var index = 0
    private var url: String? = null
    private val viewModel by viewModels<ToggleToolnarViewModel>(ownerProducer = { requireActivity() })

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
        baseBind.image.setOnClick {
            viewModel.toggleFullscreen()
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        loadImage()
    }

    private fun loadImage() {
        baseBind.emptyFrame.visibility = View.GONE
        val imageUrl: String? = if (mIllustsBean == null && !TextUtils.isEmpty(url)) {
            url
        } else {
            IllustDownload.getUrl(mIllustsBean, index, Params.IMAGE_RESOLUTION_ORIGINAL)
        }

        if (imageUrl?.isNotEmpty() == true) {
            val task = TaskPool.getLoadTask(NamedUrl("", imageUrl), requireActivity())
            task.file.observe(viewLifecycleOwner) { file ->
                baseBind.image.loadImage(file)
            }
            baseBind.progressCircular.setUpWithTaskStatus(task.status, viewLifecycleOwner)
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
