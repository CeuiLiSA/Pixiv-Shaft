package ceui.loxia

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import ceui.lisa.databinding.ItemLoadingBinding
import ceui.refactor.ppppx
import com.scwang.smart.refresh.footer.ClassicsFooter

class SlinkyFooter(context: Context, attrs: AttributeSet? = null) : ClassicsFooter(context, attrs) {

    private val loadingBinding =
        ItemLoadingBinding.inflate(LayoutInflater.from(context), null, false).apply {
            loadingFrame.isVisible = true
            loadingFrame.updateLayoutParams {
                height = 100.ppppx
            }
            progressCircular.showProgress()
        }

    override fun getView(): View {
        return loadingBinding.root
    }
}