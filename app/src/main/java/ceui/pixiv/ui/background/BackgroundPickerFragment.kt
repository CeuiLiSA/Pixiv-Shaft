package ceui.pixiv.ui.background

import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.core.net.toUri
import ceui.lisa.R
import ceui.lisa.databinding.FragmentPagedListBinding
import ceui.loxia.Illust
import ceui.loxia.IllustResponse
import ceui.loxia.ObjectPool
import ceui.loxia.requireAppBackground
import ceui.pixiv.paging.PagingIllustAPIRepository
import ceui.pixiv.paging.pagingViewModel
import ceui.pixiv.ui.common.PixivFragment
import ceui.pixiv.ui.common.setUpPagedList
import ceui.pixiv.ui.common.viewBinding
import ceui.pixiv.ui.task.LoadTask
import ceui.pixiv.ui.task.NamedUrl
import ceui.pixiv.ui.works.buildPixivWorksFileName
import ceui.pixiv.utils.GSON_DEFAULT
import ceui.pixiv.widgets.MenuItem
import ceui.pixiv.widgets.showActionMenu
import kotlinx.coroutines.MainScope
import java.io.File

class BackgroundPickerFragment : PixivFragment(R.layout.fragment_paged_list) {

    private lateinit var imageCropper: ImageCropper<BackgroundPickerFragment>
    private val binding by viewBinding(FragmentPagedListBinding::bind)
    private val viewModel by pagingViewModel({ requireActivity().assets }) { assets ->
        PagingIllustAPIRepository({
            val jsonString =
                assets.open("landing_bg.json").bufferedReader().use { it.readText() }
            GSON_DEFAULT.fromJson(jsonString, IllustResponse::class.java)
        })
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        imageCropper = ImageCropper(
            this,
            onCropSuccess = BackgroundPickerFragment::onCropSuccess,
        )
        setUpPagedList(binding, viewModel)
        binding.toolbarLayout.naviTitle.text = "精选"
    }

    private fun onCropSuccess(uri: Uri) {
        requireAppBackground().updateConfig(
            BackgroundConfig(
                BackgroundType.SPECIFIC_ILLUST,
                localFileUri = uri.toString()
            )
        )
    }

    override fun onClickIllust(illustId: Long) {
        showActionMenu {
            add(MenuItem("设置为软件背景图") {
                val illust = ObjectPool.get<Illust>(illustId).value ?: return@MenuItem
                val url = illust.meta_single_page?.original_image_url
                    ?: illust.meta_pages?.getOrNull(0)?.image_urls?.original

                if (url != null) {
                    object : LoadTask(
                        NamedUrl(buildPixivWorksFileName(illust.id, 0), url),
                        MainScope(),
                        true
                    ) {
                        override fun onEnd(resultT: File) {
                            super.onEnd(resultT)
                            imageCropper.startCrop(resultT.toUri())
                        }
                    }
                }
            })
            add(MenuItem("查看作品详情") {
                super.onClickIllust(illustId)
            })
        }
    }
}