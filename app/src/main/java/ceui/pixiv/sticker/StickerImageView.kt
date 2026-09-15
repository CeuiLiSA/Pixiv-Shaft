package ceui.pixiv.sticker

import android.content.Context
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatImageView
import ceui.lisa.R
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged

/** Used by panel cells, chat bubbles and plaza reactions. Never load resourceList.url. */
class StickerImageView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : AppCompatImageView(context, attrs) {
    private var id: Long? = null
    private var resourceSize = 128
    private var scope: CoroutineScope? = null
    init { scaleType = ScaleType.FIT_CENTER }

    fun bind(stickerId: Long?, name: String? = null, resourceSize: Int = 128) {
        require(resourceSize == 64 || resourceSize == 128)
        id = stickerId
        this.resourceSize = resourceSize
        contentDescription = name?.takeIf { it.isNotBlank() } ?: context.getString(R.string.sticker_title)
        render(StickerRepository.state.value)
        if (stickerId != null && isAttachedToWindow && StickerRepository.state.value is StickerState.Idle) StickerRepository.prepare()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).also { scope ->
            scope.launch {
                StickerRepository.state.map { it as? StickerState.Ready }.distinctUntilChanged()
                    .collect { render(it ?: StickerState.Idle) }
            }
        }
        if (id != null && StickerRepository.state.value is StickerState.Idle) StickerRepository.prepare()
    }

    private fun render(state: StickerState) {
        val stickerId = id
        if (state is StickerState.Ready && stickerId != null && state.data.images.containsKey(stickerId)) {
            Glide.with(this).load(LocalSticker(stickerId, state.data.generation, resourceSize))
                .diskCacheStrategy(DiskCacheStrategy.NONE)
                .placeholder(R.drawable.chat_ic_emoji).error(R.drawable.chat_ic_emoji).into(this)
        } else {
            Glide.with(this).clear(this)
            setImageResource(if (stickerId != null) R.drawable.chat_ic_emoji else 0)
        }
    }

    override fun onDetachedFromWindow() {
        scope?.cancel()
        scope = null
        Glide.with(this).clear(this)
        super.onDetachedFromWindow()
    }
}
