package ceui.lisa.view

import android.animation.AnimatorInflater
import android.content.Context
import android.text.TextUtils
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import ceui.lisa.R
import ceui.lisa.utils.Common
import ceui.lisa.utils.DensityUtil

/**
 * 分段选择条：一排文字胶囊，同一时刻只有一项选中（主题色描边 + 主题色文字）。
 *
 * 「动态」页两条筛选条——「插画/漫画 · 小说」与「全部 · 公开 · 私人」——共用这一个控件，
 * 所以两条长得一模一样、都是点一下就切（#1017：类型切换以前是「点开下拉再点选项」两次点击）。
 *
 * 控件自己不认识业务含义，只按下标报事件；段数与文案由宿主 [setSegments] 决定。
 */
class SegmentedToggleLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    private val cells = mutableListOf<TextView>()
    private var currentIndex = 0
    private var listener: OnCheckChangeListener? = null

    /** 字号(px)。默认对齐筛选条的 13sp,类型条在布局里用 `app:stl_textSize` 调大。 */
    private val textSizePx: Float

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundResource(R.drawable.glare_bg)
        val array = context.obtainStyledAttributes(attrs, R.styleable.SegmentedToggleLayout)
        textSizePx = array.getDimension(
            R.styleable.SegmentedToggleLayout_stl_textSize,
            TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP, 13f, resources.displayMetrics
            )
        )
        array.recycle()
    }

    /** 建分段（数组顺序即回调里的下标）。选中态复位到第 0 项，且**不回调 listener**。 */
    fun setSegments(@StringRes vararg titles: Int) {
        removeAllViews()
        cells.clear()
        currentIndex = 0
        titles.forEachIndexed { index, titleRes ->
            val cell = newCell(titleRes)
            cell.setOnClickListener { v -> onCellClick(index, v) }
            // 带权重的 wrap_content：宽松时 remainingExcess 为 0，谁也不拉伸，胶囊照旧贴合文字；
            // 挤不下时 LinearLayout 把亏空按权重摊到各段上，文字省略号收窄而不是被裁掉
            // ——最后一段被整段裁掉的话，那一项就再也点不到了。
            //
            // 权重取「自然宽度」而不是清一色的 1：等权重是等**像素**让步，短标签先被榨干
            // （1.3x 字号下「插画/漫画 · 小说」会变成「插画/漫... · 小...」）。按自然宽度分配
            // 才是等**比例**收缩，长标签先让，短的那段始终留得住。
            addView(cell, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                weight = naturalWidthOf(cell)
            })
            cells.add(cell)
        }
        renderSelection()
    }

    /**
     * 直接把选中项设成 [index]，**不回调 listener**。
     * 给「页面状态活得比视图久」的宿主用：视图重建后按宿主记着的状态回填选中态，
     * 而不是让控件复位成默认的第 0 项跟内容对不上（见 FragmentRight）。
     */
    fun setCurrentState(index: Int) {
        if (index < 0 || index >= cells.size || index == currentIndex) {
            return
        }
        currentIndex = index
        renderSelection()
    }

    fun setListener(listener: OnCheckChangeListener?) {
        this.listener = listener
    }

    private fun newCell(@StringRes titleRes: Int): TextView = TextView(context).apply {
        val horizontal = DensityUtil.dp2px(10f)
        val vertical = resources.getDimensionPixelSize(R.dimen.six_dp)
        setPadding(horizontal, vertical, horizontal, vertical)
        setTextSize(TypedValue.COMPLEX_UNIT_PX, textSizePx)
        setText(titleRes)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        isClickable = true
        isFocusable = true
        stateListAnimator =
            AnimatorInflater.loadStateListAnimator(context, R.animator.button_press_alpha)
    }

    /** 一段不被压缩时该占多宽（文字 + 左右内边距）。只在建段时算一次。 */
    private fun naturalWidthOf(cell: TextView): Float =
        cell.paint.measureText(cell.text.toString()) + cell.paddingStart + cell.paddingEnd

    private fun onCellClick(index: Int, view: View) {
        if (index == currentIndex) {
            listener?.onReselect(index, view)
            return
        }
        currentIndex = index
        renderSelection()
        listener?.onSelect(index, view)
    }

    private fun renderSelection() {
        val selectedColor =
            Common.resolveThemeAttribute(context, androidx.appcompat.R.attr.colorPrimary)
        val unselectedColor = ContextCompat.getColor(context, R.color.glare_unselected_text)
        cells.forEachIndexed { index, cell ->
            if (index == currentIndex) {
                cell.setTextColor(selectedColor)
                cell.setBackgroundResource(R.drawable.glare_selected)
            } else {
                cell.setTextColor(unselectedColor)
                cell.background = null
            }
        }
    }
}
