package ceui.pixiv.widgets

import android.content.Context
import android.graphics.Color
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.children
import ceui.lisa.R
import ceui.lisa.databinding.TabViewBinding
import ceui.refactor.ppppx
import ceui.refactor.setOnClick

class RadioGroupTab @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    private var _onItemClick: ((index: Int) -> Unit)? = null
    private var _selectedTabIndex: Int = -1

    init {
        orientation = HORIZONTAL
    }

    fun setItemCickListener(listener: (Int) -> Unit) {
        _onItemClick = listener
    }

    fun setTabs(tabTitles: List<String>) {
        removeAllViews()
        tabTitles.forEachIndexed { index, title ->
            val tabView = LayoutInflater.from(context).inflate(R.layout.tab_view, this, false)
            val tabTextView = tabView.findViewById<TextView>(R.id.tab_text)
            tabTextView.text = title

            tabView.setOnClick {
                _onItemClick?.let {
                    it(index)
                }
            }

            // 设置每个 tab 的 margin
            val layoutParams = LayoutParams(
                LayoutParams.WRAP_CONTENT,
                LayoutParams.WRAP_CONTENT
            )
            if (index == 0) {
                layoutParams.marginStart = 4.ppppx // 设置右侧间距为 4px
            }
            layoutParams.marginEnd = 4.ppppx // 设置右侧间距为 4px
            layoutParams.bottomMargin = 4.ppppx // 设置右侧间距为 4px
            tabView.layoutParams = layoutParams

            addView(tabView)
        }
    }

    fun selectTab(index: Int?) {
        if (index == null) {
            return
        }

        if (index == _selectedTabIndex) {
            return
        }

        if (childCount <= index) {
            return
        }

        children.forEachIndexed { i, view ->
            val binding = TabViewBinding.bind(view)
            binding.root.isSelected = i == index
            binding.tabText.isSelected = i == index
            if (i == index) {
                // 设置阴影
                val shadowRadius = 16f
                val shadowDx = 0f // 水平方向的偏移量
                val shadowDy = 1f // 垂直方向的偏移量
                val shadowColor = Color.parseColor("#0088A8") // 阴影颜色

                binding.tabText.setShadowLayer(shadowRadius, shadowDx, shadowDy, shadowColor)
            } else {
                // 清除阴影
                binding.tabText.setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT)
            }
        }
    }
}
