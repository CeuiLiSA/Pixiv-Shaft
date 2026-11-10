package ceui.lisa.fragments

import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import ceui.lisa.activities.ImageTranslationViewModel
import ceui.pixiv.ui.translate.MangaBatchTranslateCenter
import ceui.pixiv.ui.translate.MangaTranslateModels

/**
 * 挂 [FragmentImageDetail] 的测试宿主。
 *
 * FragmentImageDetail 在 onAttach 就以宿主 Activity 为 owner 取 `@HiltViewModel` 的
 * [ImageTranslationViewModel]，裸 FragmentActivity 的默认工厂建不出它。这里不走 Hilt 图，
 * 直接把它的依赖 new 出来交给构造器（构造契约就是廉价、不做 IO；模型等真翻译才加载）；
 * 其他 VM 照旧交给默认工厂。
 */
class ImageDetailTestHostActivity : FragmentActivity() {

    private val translationModels by lazy { MangaTranslateModels(application) }
    private val batchCenter by lazy { MangaBatchTranslateCenter(application, translationModels) }

    override val defaultViewModelProviderFactory: ViewModelProvider.Factory
        get() {
            val fallback = super.defaultViewModelProviderFactory
            return object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T =
                    if (modelClass == ImageTranslationViewModel::class.java) {
                        modelClass.cast(ImageTranslationViewModel(application, batchCenter, translationModels))!!
                    } else {
                        fallback.create(modelClass, extras)
                    }
            }
        }
}
