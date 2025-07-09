package ceui.pixiv.ui.background

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.map
import ceui.lisa.R
import ceui.lisa.databinding.FragmentPixivListBinding
import ceui.loxia.requireAppBackground
import ceui.pixiv.ui.common.ListMode
import ceui.pixiv.ui.common.PixivFragment
import ceui.pixiv.ui.common.TabCellHolder
import ceui.pixiv.ui.common.setUpCustomAdapter
import ceui.pixiv.ui.common.viewBinding

class BackgroundSettingsFragment : PixivFragment(R.layout.fragment_pixiv_list) {

    private lateinit var imageCropper: ImageCropper<BackgroundSettingsFragment>

    private val binding by viewBinding(FragmentPixivListBinding::bind)
    private val pickImageLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val imageUri = result.data?.data ?: return@registerForActivityResult
                imageCropper.startCrop(imageUri)
            }
        }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val config = requireAppBackground().config

        imageCropper = ImageCropper(
            this,
            onCropSuccess = BackgroundSettingsFragment::onCropSuccess,
        )

        val adapter = setUpCustomAdapter(binding, ListMode.VERTICAL_TABCELL)
        binding.toolbarLayout.naviTitle.text = getString(R.string.app_background)
        adapter.submitList(
            listOf(
                TabCellHolder(
                    getString(R.string.background_specified_illust),
                    showGreenDone = true,
                    selected = config.map { it.type == BackgroundType.SPECIFIC_ILLUST }).onItemClick {

                },
                TabCellHolder(
                    getString(R.string.background_chosen_from_gallary),
                    showGreenDone = true,
                    selected = config.map { it.type == BackgroundType.LOCAL_FILE }).onItemClick {
                    openSystemGallery()
                },
                TabCellHolder(
                    getString(R.string.background_random_from_favorites),
                    showGreenDone = true,
                    selected = config.map { it.type == BackgroundType.RANDOM_FROM_FAVORITES }).onItemClick {
                    if (config.value?.type != BackgroundType.RANDOM_FROM_FAVORITES) {
                        requireAppBackground().updateConfig(
                            BackgroundConfig(
                                BackgroundType.RANDOM_FROM_FAVORITES,
                            )
                        )
                    }
                },
            )
        )
    }

    private fun onCropSuccess(uri: Uri) {
        requireAppBackground().updateConfig(
            BackgroundConfig(
                BackgroundType.LOCAL_FILE,
                localFileUri = uri.toString()
            )
        )
    }

    fun openSystemGallery() {
        // 指定类型为 image/* 可以选择所有图片格式
        val gallery = Intent(Intent.ACTION_PICK)
        gallery.setDataAndType(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "image/*")
        pickImageLauncher.launch(gallery)
    }
}