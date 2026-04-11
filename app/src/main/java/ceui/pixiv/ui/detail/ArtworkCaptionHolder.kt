package ceui.pixiv.ui.detail

import android.content.Intent
import android.view.View
import androidx.core.text.HtmlCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import ceui.lisa.R
import ceui.lisa.activities.TemplateActivity
import ceui.lisa.annotations.ItemHolder
import ceui.lisa.databinding.CellArtworkCaptionBinding
import ceui.lisa.utils.Common
import ceui.lisa.utils.ShareIllust
import ceui.loxia.DateParse
import ceui.loxia.Illust
import ceui.loxia.ObjectPool
import ceui.pixiv.ui.common.ListItemHolder
import ceui.pixiv.ui.common.ListItemViewHolder
import ceui.pixiv.ui.translate.OfflineTranslator
import ceui.pixiv.ui.translate.TranslationModel
import ceui.pixiv.ui.translate.TranslationModelManager
import ceui.pixiv.utils.setOnClick
import kotlinx.coroutines.launch
import timber.log.Timber


class ArtworkCaptionHolder(val illustId: Long) : ListItemHolder() {
    override fun getItemId(): Long {
        return illustId
    }
}

@ItemHolder(ArtworkCaptionHolder::class)
class ArtworkCaptionViewHolder(bd: CellArtworkCaptionBinding) : ListItemViewHolder<CellArtworkCaptionBinding, ArtworkCaptionHolder>(bd) {

    override fun onBindViewHolder(holder: ArtworkCaptionHolder, position: Int) {
        super.onBindViewHolder(holder, position)
        val liveIllust = ObjectPool.get<Illust>(holder.illustId)
        binding.illust = liveIllust
        liveIllust.observe(lifecycleOwner) { illust ->
            if (illust.caption?.isNotEmpty() == true) {
                binding.caption.isVisible = true
                binding.caption.text = HtmlCompat.fromHtml(illust.caption, HtmlCompat.FROM_HTML_MODE_COMPACT)
                binding.btnTranslate.isVisible = true
            } else {
                binding.caption.isVisible = false
                binding.btnTranslate.isVisible = false
            }

            binding.btnTranslate.setOnClick {
                handleTranslate(illust)
            }

            binding.userId.setOnClick {
                Common.copy(context, illust.user?.id?.toString())
            }
            binding.illustLink.text =
                context.getString(R.string.artwork_link, ShareIllust.URL_Head + illust.id)
            binding.illustLink.setOnClick {
                Common.copy(context, ShareIllust.URL_Head + illust.id)
            }

            binding.userLink.text =
                context.getString(R.string.user_link, ShareIllust.USER_URL_Head + illust.user?.id)
            binding.userLink.setOnClick {
                Common.copy(context, ShareIllust.USER_URL_Head + illust.user?.id)
            }

            binding.publishTime.text = context.getString(
                R.string.published_on,
                DateParse.getTimeAgo(context, illust.create_date)
            )
        }
        binding.illustId.setOnClick {
            Common.copy(context, holder.illustId.toString())
        }
    }

    private fun handleTranslate(illust: Illust) {
        val model = TranslationModel.OPUS_MT_JA_ZH

        // Check if model is downloaded
        if (!TranslationModelManager.isModelReady(context, model)) {
            // Navigate to download page
            val intent = Intent(context, TemplateActivity::class.java)
            intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "翻译模型下载")
            intent.putExtra("translation_model_name", model.name)
            context.startActivity(intent)
            return
        }

        // Model ready, do translation
        val rawCaption = illust.caption ?: return
        // Strip HTML tags to get plain text
        val plainText = HtmlCompat.fromHtml(rawCaption, HtmlCompat.FROM_HTML_MODE_COMPACT).toString().trim()
        if (plainText.isEmpty()) return

        binding.btnTranslate.text = context.getString(R.string.string_translating)
        binding.btnTranslate.isEnabled = false

        lifecycleOwner.lifecycleScope.launch {
            try {
                // Load model if not loaded
                if (!OfflineTranslator.isLoaded) {
                    binding.btnTranslate.text = context.getString(R.string.string_translate_model_loading)
                    OfflineTranslator.loadModel(context, model)
                }

                val translated = OfflineTranslator.translate(plainText)
                binding.captionTranslated.text = translated
                binding.captionTranslated.visibility = View.VISIBLE
                binding.btnTranslate.text = context.getString(R.string.string_translate_caption)
                binding.btnTranslate.isEnabled = true
            } catch (e: Exception) {
                Timber.e(e, "Translation failed")
                Common.showToast(R.string.string_translate_failed)
                binding.btnTranslate.text = context.getString(R.string.string_translate_caption)
                binding.btnTranslate.isEnabled = true
            }
        }
    }
}
