package ceui.pixiv.widgets

import android.content.Context
import android.content.Context.INPUT_METHOD_SERVICE
import android.graphics.Rect
import android.os.Build
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.view.updatePadding
import androidx.drawerlayout.widget.DrawerLayout
import androidx.fragment.app.Fragment
import androidx.lifecycle.MutableLiveData
import androidx.viewpager2.widget.ViewPager2
import ceui.lisa.R
import ceui.loxia.asLiveData
import ceui.loxia.findAncestorOrSelf
import ceui.loxia.findFragmentOrNull
import ceui.pixiv.utils.findAncestorOrNull
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlin.math.max

open class MercuryDrawerLayout(context: Context, attrs: AttributeSet?, defStyle: Int)
    : DrawerLayout(context, attrs, defStyle)  {

    constructor(context: Context) : this(context, null, 0)

    constructor(context: Context, attrs: AttributeSet?): this(context, attrs, 0)


    private val _currentInsets = MutableLiveData<WindowInsets?>()
    val currentInset = _currentInsets.asLiveData()

    override fun onApplyWindowInsets(insets: WindowInsets?): WindowInsets {
        this.suggestedMinimumWidth
        _currentInsets.value = insets
        return super.onApplyWindowInsets(insets)
    }
}


data class InsetState(val margin: Rect, val padding: Rect) {
    companion object {
        fun create(view: View): InsetState {
            val lp = view.layoutParams as? ViewGroup.MarginLayoutParams
            return InsetState(
                if (lp != null) Rect(
                    lp.leftMargin,
                    lp.topMargin,
                    lp.rightMargin,
                    lp.bottomMargin
                ) else Rect(0, 0, 0, 0),
                Rect(view.paddingLeft, view.paddingTop, view.paddingRight, view.paddingBottom)
            )
        }
    }
}


class BottomBarContainer(context: Context, attrs: AttributeSet?, defStyle: Int, defStyleRes: Int) :
    FrameLayout(context, attrs, defStyle, defStyleRes) {
    constructor(context: Context) : this(context, null, 0, 0)

    constructor(context: Context, attrs: AttributeSet?) : this(context, attrs, 0, 0)

    constructor(context: Context, attrs: AttributeSet?, defStyle: Int) : this(
        context,
        attrs,
        defStyle,
        0
    )
}


private fun View.getIsEmbeddedInViewPager(): Boolean {
    return findAncestorOrNull<ViewPager2>() != null
}

private fun View.shouldIgnoreInset(): Boolean {
    return findAncestorOrNull<IgnoreInsetLayout>() != null
}

class IgnoreInsetLayout : FrameLayout {
    constructor(context: Context) : this(context, null)

    constructor(context: Context, attrs: AttributeSet?) : this(context, attrs, 0)

    constructor(context: Context, attrs: AttributeSet?, style: Int) : super(context, attrs, style)
}

open class InsetLayout(context: Context, attrs: AttributeSet?, defStyle: Int, defStyleRes: Int) :
    FrameLayout(context, attrs, defStyle, defStyleRes) {

    constructor(context: Context) : this(context, null, 0, 0)

    constructor(context: Context, attrs: AttributeSet?) : this(context, attrs, 0, 0)

    constructor(context: Context, attrs: AttributeSet?, defStyle: Int) : this(
        context,
        attrs,
        defStyle,
        0
    )

    override fun onViewAdded(child: View?) {
        super.onViewAdded(child)

        if (child != null) {
            val lp = child.layoutParams as? LayoutParams
            if (lp != null) {
                child.findViewById<View>(lp.paddingTopTargetId)?.let { target ->
                    target.setTag(R.id.initial_insets, InsetState.create(target))
                }
                child.findViewById<View>(lp.paddingBottomTargetId)?.let { target ->
                    target.setTag(R.id.initial_insets, InsetState.create(target))
                }

                val marginTarget = child.findViewById(lp.marginTargetId) ?: child
                val paddingTarget = child.findViewById(lp.paddingTargetId) ?: child
                paddingTarget.setTag(R.id.initial_insets, InsetState.create(paddingTarget))
                if (paddingTarget != marginTarget) {
                    marginTarget.setTag(R.id.initial_insets, InsetState.create(marginTarget))
                }

                if (lp.barType == LayoutParams.BarType.BOTTOM) {
                    lp.gravity = Gravity.BOTTOM
                } else if (lp.barType == LayoutParams.BarType.TOP) {
                    lp.gravity = Gravity.TOP
                }
            }
        }
    }

    object BarHelper {

        private fun getThisBarInsets(viewGroup: ViewGroup, keyboardOpened: Boolean): Rect {

            when (viewGroup) {
                is InsetLayout -> {
                    var topBarInset = 0
                    var bottomBarInset = 0
                    for (i in 0 until viewGroup.childCount) {
                        val child = viewGroup.getChildAt(i)
                        val lp = child.layoutParams as? LayoutParams
                        if (lp != null) {
                            // if bottom bar height > keyboard height and ignoresKeyboard, the bottom insets will be incorrect
                            if (lp.barType == LayoutParams.BarType.TOP || (lp.barType == LayoutParams.BarType.BOTTOM && (!keyboardOpened || !lp.ignoresKeyboard))) {
                                child.measure(MeasureSpec.UNSPECIFIED, MeasureSpec.UNSPECIFIED)
                                if (lp.barType == LayoutParams.BarType.TOP) {
                                    topBarInset =
                                        max(topBarInset, (child.measuredHeight - child.paddingTop))
                                } else {
                                    bottomBarInset = max(
                                        bottomBarInset,
                                        (child.measuredHeight - child.paddingBottom)
                                    )
                                }
                            }
                        }
                    }

                    return Rect(0, topBarInset, 0, bottomBarInset)
                }
                is CoordinatorLayout -> {
                    var bottomBarInset = 0
                    for (i in 0 until viewGroup.childCount) {
                        val child = viewGroup.getChildAt(i)
                        if (child is BottomBarContainer) {
                            child.measure(MeasureSpec.UNSPECIFIED, MeasureSpec.UNSPECIFIED)
                            bottomBarInset =
                                max(bottomBarInset, (child.measuredHeight - child.paddingBottom))
                        }
                    }
                    return Rect(0, 0, 0, bottomBarInset)
                }
                else -> {
                    return Rect(0, 0, 0, 0)
                }
            }
        }

        fun getOverallBarInsets(viewGroup: ViewGroup, keyboardOpened: Boolean): Rect {
            val thisInsets = getThisBarInsets(viewGroup, keyboardOpened)

            val parent = viewGroup.parent as? ViewGroup ?: return thisInsets

            val otherInsets = getOverallBarInsets(parent, keyboardOpened)
            return Rect(
                0,
                max(thisInsets.top, otherInsets.top),
                0,
                max(thisInsets.bottom, otherInsets.bottom)
            )
        }

    }

    private var _currentTopInset: Int = 0
    private var _currentBottomInset: Int = 0

    val currentTopInset: Int get() = _currentTopInset
    val currentBottomInset: Int get() = _currentBottomInset


    override fun onApplyWindowInsets(insets: WindowInsets?): WindowInsets {
        val fragment = findFragmentOrNull<Fragment>()
        val isBottomSheet = fragment?.findAncestorOrSelf<BottomSheetDialogFragment>() != null

        val trueInsets = insets

        // Somehow we will be called with systemWindowInsetTop as 0 (when I nested a common view pager fragment into home tab) even it is not bottom sheet. Not sure why
        // it is ok for bottom sheet with 0 top inset
        if (trueInsets != null && (trueInsets.systemWindowInsetTop > 0 || isBottomSheet )) {
            _currentTopInset = trueInsets.systemWindowInsetTop
            _currentBottomInset = trueInsets.systemWindowInsetBottom


            var actionBarHeight = 0


            val activity = fragment?.activity
            var keyboardOpened = false

            if (activity != null) {
//                val navFragment = fragment as? NavFragment
//                if (navFragment != null) {
//                    if (navFragment.actionBarContents.barGone.value != true) {
//                        val tv = TypedValue()
//                        if (activity.theme.resolveAttribute(
//                                android.R.attr.actionBarSize,
//                                tv,
//                                true
//                            )
//                        ) {
//                            actionBarHeight = TypedValue.complexToDimensionPixelSize(
//                                tv.data,
//                                resources.displayMetrics
//                            )
//                        }
//                    }
//                }
                val imm = activity.getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
                if (imm != null) {
                    keyboardOpened =
                        imm.isAcceptingText && (trueInsets.systemWindowInsetBottom > activity.window.decorView.height / 3)
                }
            }

            val barInsets = BarHelper.getOverallBarInsets(this, keyboardOpened)

            val isIgnoreInset = shouldIgnoreInset()
            val isEmbeddedInViewPager = getIsEmbeddedInViewPager()
            val isAttachedToWindow = isAttachedToWindow
            for (i in 0 until childCount) {
                val child = getChildAt(i)
                val lp = child.layoutParams as? LayoutParams ?: continue

                val ignoreTopBarInset = lp.barType == LayoutParams.BarType.TOP
                val ignoreBottomBarInset = lp.barType == LayoutParams.BarType.BOTTOM

                val ignoresActionBar = lp.ignoresActionBar

                val marginTarget = child.findViewById(lp.marginTargetId) ?: child

                val ignoreAllTops =
                    isIgnoreInset || isEmbeddedInViewPager || !isAttachedToWindow || isBottomSheet

                if (marginTarget.layoutParams is MarginLayoutParams) {
                    var marginChanged = false
                    val initial = marginTarget.getTag(R.id.initial_insets) as InsetState
                    if ((lp.marginAdjust and LayoutParams.EdgeMask.TOP > 0) && !ignoreAllTops) {
                        (marginTarget.layoutParams as MarginLayoutParams).topMargin =
                            initial.margin.top + trueInsets.systemWindowInsetTop + (if (ignoresActionBar) 0 else actionBarHeight) + (if (ignoreTopBarInset) 0 else barInsets.top)
                        marginChanged = true
                    }

                    if (lp.marginAdjust and LayoutParams.EdgeMask.BOTTOM > 0 && !isIgnoreInset) {
                        if (!lp.ignoresKeyboard || !keyboardOpened) {
                            (marginTarget.layoutParams as MarginLayoutParams).bottomMargin =
                                initial.margin.bottom + trueInsets.systemWindowInsetBottom + (if (ignoreBottomBarInset) 0 else barInsets.bottom)
                            marginChanged = true
                        }
                    }

                    if (marginChanged) {
                        marginTarget.requestLayout() // Android 11 doesn't relayout after change the margin
                    }
                }

                if (lp.paddingAdjust > 0) {
//                    val paddingTarget = child.findViewById(lp.paddingTargetId) ?: child
//                    val initial = paddingTarget.getTag(R.id.initial_insets) as InsetState

//                    var paddingTop = initial.padding.top
//                    var paddingBottom = initial.padding.bottom
                    val paddingTopTarget = child.findViewById(lp.paddingTopTargetId) ?: (child.findViewById(lp.paddingTargetId) ?: child)
                    val paddingBottomTarget = child.findViewById(lp.paddingBottomTargetId) ?: (child.findViewById(lp.paddingTargetId) ?: child)
                    var paddingTop = (paddingTopTarget.getTag(R.id.initial_insets) as InsetState).padding.top
                    var paddingBottom = (paddingBottomTarget.getTag(R.id.initial_insets) as InsetState).padding.bottom

                    if ((lp.paddingAdjust and LayoutParams.EdgeMask.TOP > 0) && !ignoreAllTops) {
                        paddingTop += trueInsets.systemWindowInsetTop + actionBarHeight + (if (ignoreTopBarInset) 0 else barInsets.top)
                    }

                    if (lp.paddingAdjust and LayoutParams.EdgeMask.BOTTOM > 0 && !isIgnoreInset) {
                        if (!lp.ignoresKeyboard || !keyboardOpened) {
                            paddingBottom += trueInsets.systemWindowInsetBottom + (if (ignoreBottomBarInset) 0 else barInsets.bottom)
                        }
                    }

                    paddingTopTarget.updatePadding(top = paddingTop)
                    paddingBottomTarget.updatePadding(bottom = paddingBottom)
//                    paddingTarget.updatePadding(top = paddingTop, bottom = paddingBottom)
                }
            }
        }

        return super.onApplyWindowInsets(trueInsets)
    }

    private fun getTop(insets: WindowInsets): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (insets.isVisible(WindowInsets.Type.statusBars())) {
                insets.getInsets(WindowInsets.Type.systemBars()).top
            } else 0
        } else {
            if (rootView.windowSystemUiVisibility and View.SYSTEM_UI_FLAG_FULLSCREEN == 0) {
                insets.systemWindowInsetTop
            } else 0
        }
    }

    override fun generateLayoutParams(attrs: AttributeSet?): LayoutParams? {
        return LayoutParams(context, attrs)
    }

    class LayoutParams(context: Context, attrs: AttributeSet?) :
        FrameLayout.LayoutParams(context, attrs) {
        object BarType {
            const val NONE = 0
            const val TOP = 1
            const val BOTTOM = 2
        }

        object EdgeMask {
            const val NONE = 0
            const val TOP = 1
            const val BOTTOM = 1 shl 1
        }

        val barType: Int
        var ignoresKeyboard: Boolean
        var marginAdjust: Int
        var paddingAdjust: Int
        val paddingTargetId: Int
        val marginTargetId: Int
        val paddingTopTargetId: Int
        val paddingBottomTargetId: Int

        var ignoresActionBar: Boolean = false

        init {
            val ta = context.obtainStyledAttributes(attrs, R.styleable.InsetLayout_Layout)
            barType = ta.getInteger(
                R.styleable.InsetLayout_Layout_layout_inset_bar_type,
                BarType.NONE
            )
            ignoresKeyboard =
                ta.getBoolean(R.styleable.InsetLayout_Layout_layout_inset_ignores_keyboard, false)
            marginAdjust = ta.getInteger(
                R.styleable.InsetLayout_Layout_layout_inset_adjust_margin,
                EdgeMask.NONE
            )
            paddingAdjust = ta.getInteger(
                R.styleable.InsetLayout_Layout_layout_inset_adjust_padding,
                EdgeMask.NONE
            )
            marginTargetId = ta.getResourceId(
                R.styleable.InsetLayout_Layout_layout_inset_margin_adjust_target,
                View.NO_ID
            )
            paddingTopTargetId = ta.getResourceId(
                R.styleable.InsetLayout_Layout_layout_inset_padding_top_adjust_target,
                View.NO_ID
            )
            paddingBottomTargetId = ta.getResourceId(
                R.styleable.InsetLayout_Layout_layout_inset_padding_bottom_adjust_target,
                View.NO_ID
            )
            paddingTargetId = ta.getResourceId(
                R.styleable.InsetLayout_Layout_layout_inset_padding_adjust_target,
                View.NO_ID
            )
            ta.recycle()
        }
    }
}
