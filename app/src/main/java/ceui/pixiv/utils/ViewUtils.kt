package ceui.pixiv.utils

import android.animation.*
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Resources
import android.graphics.*
import android.os.Build
import android.text.StaticLayout
import android.text.TextPaint
import android.util.AttributeSet
import android.util.DisplayMetrics
import android.util.Size
import android.util.TypedValue
import android.view.*
import android.view.animation.*
import android.widget.*
import androidx.annotation.ColorInt
import androidx.annotation.WorkerThread
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.view.isVisible
import androidx.databinding.BindingAdapter
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import ceui.lisa.R
import com.google.android.material.appbar.AppBarLayout
import java.io.File
import java.io.FileOutputStream
import kotlin.math.min
import kotlin.math.roundToInt

fun View.setScale(scale: Float) {
    scaleX = scale
    scaleY = scale
}

fun View.getLayoutWeight(): Float {
    return (layoutParams as? LinearLayout.LayoutParams)?.weight ?: 0.0f
}

fun disableAll(viewGroup: ViewGroup) {
    viewGroup.isEnabled = false
    for (index in 0 until viewGroup.childCount) {
        val child = viewGroup.getChildAt(index)
        if (child is ViewGroup) {
            disableAll(child)
        } else {
            child.isEnabled = false
            child.alpha = 0.3F
        }
    }
}

fun ViewGroup.getNotGoneChildren(): List<View> {
    val res = mutableListOf<View>()
    for (i in 0 until childCount) {
        val child = getChildAt(i)
        if (child.visibility != View.GONE) {
            res.add(child)
        }
    }

    return res
}

fun View.getAncestorsDesc(): String {

    val chain = mutableListOf<String>()
    chain.add(this.toString())
    var itr = this.parent
    while (itr != null) {

        chain.add(itr.toString())
        itr = itr.parent
    }

    return chain.mapIndexed { index, string -> "${index} ${string}" }.joinToString("\n", prefix = "\n")
}

inline fun View.findAncestorOrSelfOrNull(predicate: (View) -> Boolean): View? {
    var itr: View? = this
    while (itr != null) {
        if (predicate(itr)) {
            return itr
        }
        itr = (itr.parent as? View)
    }
    return null
}

inline fun <reified T : View> View.findAncestorOrNull(): T? {
    var itr = this.parent
    while (itr != null) {
        if (itr is T) {
            return itr
        }
        itr = itr.parent
    }
    return null
}

inline fun <reified T : View> View.findAncestorOrSelfOrNull(): T? {
    var itr = this as? ViewParent
    while (itr != null) {
        if (itr is T) {
            return itr
        }
        itr = itr.parent
    }
    return null
}

inline fun <reified T : View> ViewGroup.findChildOrNull(): T? {
    for (i in 0 until childCount) {
        val child = getChildAt(i)
        if (child is T) {
            return child
        }
    }

    return null
}

inline fun <reified T : View> View.findChildOrSelfOrNull(): T? {
    if (this is ViewGroup) {
        return this.findChildOrNull()
    } else {
        return this as? T
    }
}


fun ViewGroup.forEachDescendants(visitor: (view: View) -> Boolean) {
    forEachDescendantsImpl(visitor)
}
// true to stop
fun ViewGroup.forEachDescendantsImpl(visitor: (view: View) -> Boolean): Boolean {
    for (i in 0 until childCount) {
        val child = getChildAt(i)
        if (visitor(child)) {
            return true
        }

        val vg = child as? ViewGroup
        if (vg != null) {
            if (vg.forEachDescendantsImpl(visitor)) {
                return true
            }
        }
    }
    return false
}

inline fun <reified T : View> View.findSibling(): T? {
    return (parent as? ViewGroup)?.findChildOrNull<T>()
}


val View.size: Size
    get() = Size(width, height)

@set:BindingAdapter("isSelected")
var View.isSelected
    get() = isSelected
    set(value) {
        isSelected = value
    }

@set:BindingAdapter("visibleOrGone")
var View.visibleOrGone
    get() = visibility == View.VISIBLE
    set(value) {
        visibility = if (value) View.VISIBLE else View.GONE
    }

@set:BindingAdapter("invisibleOrGone")
var View.invisibleOrGone
    get() = visibility == View.INVISIBLE
    set(value) {
        visibility = if (value) View.INVISIBLE else View.GONE
    }

@set:BindingAdapter("visibleOrInvisible")
var View.visibleOrInvisible
    get() = visibility == View.VISIBLE
    set(value) {
        visibility = if (value) View.VISIBLE else View.INVISIBLE
    }


@set:BindingAdapter("isActivated")
var View.isActivated
    get() = isActivated
    set(value) {
        isActivated = value
    }

@set:BindingAdapter("isEnabled")
var View.isEnabled
    get() = isEnabled
    set(value) {
        isEnabled = value
    }

@set:BindingAdapter("isEnabledAndDark")
var View.isEnabledAndDark
    get() = isEnabled
    set(value) {
        isEnabled = value
        alpha = if (value) {
            1F
        } else {
            0.5F
        }
    }

@set:BindingAdapter("isClickableAndDark")
var View.isClickableAndDark
    get() = isClickable
    set(value) {
        isClickable = value
        alpha = if (value) {
            1F
        } else {
            0.5F
        }
    }

@set:BindingAdapter("updateText")
var TextView.updateText: CharSequence?
    get() = text
    set(value) {
        text = value
        requestLayout()
    }
@set:BindingAdapter("updateTextOneLine")
var TextView.updateTextOneLine: CharSequence?
    get() = text
    set(value) {
        text = value.toString().replace("\n"," ").trim()
        requestLayout()
    }

fun Context.screenDisplay(): DisplayMetrics {
    val displayMetrics = DisplayMetrics()
    val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
    windowManager.defaultDisplay.getRealMetrics(displayMetrics)
    return displayMetrics
}

fun View.screenDisplay(): DisplayMetrics {
    return context.screenDisplay()
}

fun View.divideCount(unit: Int, offset: Int): Int {
    val metrics = screenDisplay()
    val screenWidth = (metrics.widthPixels.toFloat() / metrics.density).toInt()
    return (screenWidth - offset) / unit
}

fun View.proportionHeight(height: Int): Float {
    val metrics = screenDisplay()
    val screenHeight = metrics.heightPixels.toFloat() / metrics.density
    return min(1.0f, height.toFloat() / screenHeight)
}


fun View.convertRectToWindowSpace(rect: Rect): Rect {
    val locations = intArrayOf(0, 0)
    getLocationInWindow(locations)
    return Rect(
        rect.left + locations[0],
        rect.top + locations[1],
        rect.right + locations[0],
        rect.bottom + locations[1]
    )
}

fun View.convertYToWindowSpace(y: Int): Int {
    val locations = intArrayOf(0, 0)
    getLocationInWindow(locations)
    return y + locations[1]
}

val View.frame get() = Rect(left, top, right, bottom)



fun TextView.makeUnderline() {
    paintFlags = paintFlags or Paint.UNDERLINE_TEXT_FLAG
}

fun View.makeGoneIf(f: Boolean) {
    visibility = if (f) View.GONE else View.VISIBLE
}

fun List<View>.makeOneVisible(visibleView: View?) {
    forEach {
        it.makeGoneIf(it != visibleView)
    }
}
fun List<View>.makeOneSelected(selected: View?) {
    forEach {
        it.isSelected = (it == selected)
    }
}

class InterceptTouchFrameLayout(context: Context, attrs: AttributeSet?, defStyle: Int, defStyleRes: Int)
    : FrameLayout(context, attrs, defStyle, defStyleRes) {

    constructor(context: Context) : this(context, null, 0, 0)

    constructor(context: Context, attrs: AttributeSet?): this(context, attrs, 0, 0)

    constructor(context: Context, attrs: AttributeSet?, defStyle: Int): this(context, attrs, defStyle, 0)

    override fun onInterceptTouchEvent(ev: MotionEvent?): Boolean {
        return true
    }
}

object Justify {

    data class LayoutInfo(val maxChildCount: Int, val actualSpacing: Float)

    fun getColumnLayoutInfo(availableWidth: Float, childWidth: Float, minSpacing: Float): LayoutInfo {
        require(childWidth > 10)

        var consumedWidth = 0F
        var triedChildCount = 0
        while (consumedWidth < availableWidth) {
            consumedWidth += childWidth
            if (triedChildCount != 0) {
                consumedWidth += minSpacing
            }

            ++triedChildCount
        }

        val maxChildCount = if (triedChildCount == 0) 0 else (triedChildCount - 1)
        if (maxChildCount <= 1) {
            return LayoutInfo(maxChildCount, 0F)
        }

        val actualSpacing = (availableWidth - maxChildCount * childWidth).toFloat() / (maxChildCount - 1)

        return LayoutInfo(maxChildCount, actualSpacing)
    }
}

fun measureChildNoPadding(
    child: View,
    parentWidthMeasureSpec: Int,
    parentHeightMeasureSpec: Int
) {
    val lp = child.layoutParams
    val childWidthMeasureSpec = ViewGroup.getChildMeasureSpec(
        parentWidthMeasureSpec,
        0, lp.width
    )
    val childHeightMeasureSpec = ViewGroup.getChildMeasureSpec(
        parentHeightMeasureSpec,
        0, lp.height
    )
    child.measure(childWidthMeasureSpec, childHeightMeasureSpec)
}


class TypingTextViewModel: ViewModel() {
    val text = MutableLiveData("")
}

fun ViewPager2.addOnPageSelectedListener(listener: (position: Int) -> Unit) {
    registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
        override fun onPageSelected(position: Int) {
            listener(position)
        }
    })
}


fun View.toBitmap(config: Bitmap.Config = Bitmap.Config.ARGB_8888): Bitmap? {
    return Bitmap.createBitmap(width, height, config).also { bitmap ->
        val radius = screenDisplay().density * 16f
        val path = Path().also {
            it.addRoundRect(RectF(0f, 0f, width.toFloat(), height.toFloat()), radius, radius, Path.Direction.CW)
        }
        Canvas(bitmap).also { canvas ->
            canvas.clipPath(path)
            draw(canvas)
        }
    }
}

// returns file path
@WorkerThread
fun Bitmap.saveAsJpgToTempFile(prefix: String, quality: Int, subdir: File?): File {
    val tempFile = File.createTempFile(prefix, ".jpg", subdir)
    return saveAsJpgToFile(tempFile, quality)
}

@WorkerThread
fun Bitmap.saveAsJpgToFile(file: File, quality: Int): File {
    val outputStream = FileOutputStream(file)
    compress(Bitmap.CompressFormat.JPEG, quality, outputStream)
    outputStream.flush()
    outputStream.close()

    return file
}

@WorkerThread
fun Bitmap.saveAsPngToTempFile(prefix: String, quality: Int, subdir: File?): File {
    val tempFile = File.createTempFile(prefix, ".png", subdir)
    return saveAsPngToFile(tempFile, quality)
}

@WorkerThread
fun Bitmap.saveAsPngToFile(file: File, quality: Int): File {
    val outputStream = FileOutputStream(file)
    compress(Bitmap.CompressFormat.PNG, quality, outputStream)
    outputStream.flush()
    outputStream.close()

    return file
}

@WorkerThread
fun Bitmap.saveAsWebpToFile(file: File, quality: Int): File {
    val outputStream = FileOutputStream(file)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        compress(Bitmap.CompressFormat.WEBP_LOSSLESS, quality, outputStream)
    } else {
        compress(Bitmap.CompressFormat.WEBP, quality, outputStream)
    }
    outputStream.flush()
    outputStream.close()

    return file
}

fun measureTextWidth(content: String, textSize: Float, resources: Resources): Int {
    val paint = TextPaint()
    paint.textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, textSize, resources.displayMetrics)
    return StaticLayout.getDesiredWidth(content, paint).toInt()
}

@BindingAdapter("shadowRadius", "shadowColor")
fun TextView.binding_setShadow(radius: Float, @ColorInt color: Int) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        setShadowLayer(radius, 0F, 0F, color)
    }
}


fun AppBarLayout.getAppBarBehavior(): AppBarLayout.Behavior? {
    return ((layoutParams as CoordinatorLayout.LayoutParams).behavior as? AppBarLayout.Behavior)
}


fun ViewPager2.getRecyclerView(): RecyclerView? {
    return findChildOrNull()
}

//fun <T : View?> View.findViewByIdOrNull(@IdRes id: Int): T? {
//    return try {
//        findViewById(id)
//    } catch (e: java.lang.Exception) {
//        null
//    }
//}

fun View.findDescendantOrSelfMatches(predicate: (View) -> Boolean): View? {
    if (predicate(this))
        return this

    if (this is ViewGroup) {
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            val target = child.findDescendantOrSelfMatches(predicate)
            if (target != null)
                return target
        }
        return null
    } else {
        return null
    }
}


fun View.findDescendantMatches(predicate: (View) -> Boolean): View? {
    if (this is ViewGroup) {
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            val target = child.findDescendantOrSelfMatches(predicate)
            if (target != null)
                return target
        }
    }

    return null
}

fun View.findAllDescendantsMatches(predicate: (View) -> Boolean): List<View> {
    val result = mutableListOf<View>()
    if (predicate(this))
        result.add(this)
    if (this is ViewGroup) {
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            val target = child.findAllDescendantsMatches(predicate)
            target.forEach { result.add(it) }
        }
    }

    return result
}

inline fun <reified T : Any> View.findDescendant(): T? {
    return findDescendantMatches {
        it is T
    } as? T
}

inline fun <reified T : Any> View.findDescendantOrSelf(): T? {
    return findDescendantOrSelfMatches {
        it is T
    } as? T
}

fun View.isDescendantOf(ancestor: View): Boolean {
    var itr: View? = this
    while (itr != null) {
        if (itr == ancestor)
            return true

        itr = itr.parent as? View
    }

    return false
}

fun View.largeEnough(limit: Double = 1.8) : Boolean {
    val boundingWidth = this.screenDisplay().widthPixels * 0.88
    val boundingHeight = this.screenDisplay().heightPixels * 0.8
    return boundingHeight / boundingWidth > limit
}

fun View.dipToPx(dp: Float) : Int {
    return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, resources.displayMetrics).roundToInt()
}

fun View.dipToPxF(dp: Float) : Float {
    return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, resources.displayMetrics)
}

fun View.spToPx(sp: Float) : Int {
    return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, resources.displayMetrics).roundToInt()
}

fun View.spToPxF(sp: Float) : Float {
    return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, resources.displayMetrics)
}

fun View.animateWiggle() {
    val wiggle: Animation = AnimationUtils.loadAnimation(context, R.anim.wiggle)
    wiggle.interpolator = CycleInterpolator(3F)
    clearAnimation()
    startAnimation(wiggle)
}

fun View.animateWiggleForPasswordCheck() {
    val wiggle: Animation = AnimationUtils.loadAnimation(context, R.anim.wiggle_for_password_check)
    wiggle.interpolator = CycleInterpolator(1F)
    clearAnimation()
    startAnimation(wiggle)
}

fun View.animateInvisible() {
    SpringAnimation(this, DynamicAnimation.ALPHA, 0F).apply {
        spring.dampingRatio = SpringForce.DAMPING_RATIO_NO_BOUNCY
        spring.stiffness = SpringForce.STIFFNESS_VERY_LOW
        start()
    }
}

fun View.animateVisible() {
    SpringAnimation(this, DynamicAnimation.ALPHA, 1F).apply {
        spring.dampingRatio = SpringForce.DAMPING_RATIO_NO_BOUNCY
        spring.stiffness = SpringForce.STIFFNESS_VERY_LOW
        start()
    }
}


fun View.adjustShowKeyboard(height: Float = -dipToPx(150F).toFloat()) {
    translationY = 0F
    SpringAnimation(this, DynamicAnimation.TRANSLATION_Y, height).apply {
        spring.dampingRatio = SpringForce.DAMPING_RATIO_NO_BOUNCY
        spring.stiffness = SpringForce.STIFFNESS_MEDIUM
        start()
    }
}

fun View.adjustHideKeyboard() {
    SpringAnimation(this, DynamicAnimation.TRANSLATION_Y, 0F).apply {
        spring.dampingRatio = SpringForce.DAMPING_RATIO_NO_BOUNCY
        spring.stiffness = SpringForce.STIFFNESS_MEDIUM
        start()
    }
}

fun View.slideRightToLeft(startOffset: Float = dipToPx(170F).toFloat()) {
    translationX = startOffset
    SpringAnimation(this, DynamicAnimation.TRANSLATION_X, 0F).apply {
        spring.dampingRatio = SpringForce.DAMPING_RATIO_NO_BOUNCY
        spring.stiffness = 500F
        start()
    }
}

fun View.slideLeftToRight(startOffset: Float = dipToPx(170F).toFloat()) {
    translationX = 0F
    SpringAnimation(this, DynamicAnimation.TRANSLATION_X, startOffset).apply {
        spring.dampingRatio = SpringForce.DAMPING_RATIO_NO_BOUNCY
        spring.stiffness = 800F
        start()
    }
}


fun View.animateFadeInQuickly(): SpringAnimation {
    alpha = 0F
    isVisible = true
    val anim = SpringAnimation(this, DynamicAnimation.ALPHA, 1F).apply {
        spring.dampingRatio = SpringForce.DAMPING_RATIO_NO_BOUNCY
        spring.stiffness = 300F
    }
    anim.addEndListener { animation, canceled, value, velocity ->
        alpha = 1F
    }.start()
    return anim
}

fun View.animateFadeOutQuickly(): SpringAnimation {
    alpha = 1F
    val anim = SpringAnimation(this, DynamicAnimation.ALPHA, 0F).apply {
        spring.dampingRatio = SpringForce.DAMPING_RATIO_NO_BOUNCY
        spring.stiffness = 300F
    }
    anim.addUpdateListener { _, value, _ ->
        if (value < 0.2F) {
            visibleOrInvisible = false
        }
    }.addEndListener { animation, canceled, value, velocity ->
        alpha = 0F
    }.start()
    return anim
}


fun View.animateMeteor() {
    val x = measuredWidth.toFloat()
    val screenWidth = screenDisplay().widthPixels.toFloat()
    SpringAnimation(this, DynamicAnimation.TRANSLATION_X, -x).apply {
        spring.dampingRatio = SpringForce.DAMPING_RATIO_NO_BOUNCY
        spring.stiffness = 50F
        start()
    }

    val finalTranslationY = translationY + screenWidth + x

    SpringAnimation(this, DynamicAnimation.TRANSLATION_Y, finalTranslationY).apply {
        spring.dampingRatio = SpringForce.DAMPING_RATIO_NO_BOUNCY
        spring.stiffness = 50F
        start()
    }
}

fun View.animateSlideX() {
    SpringAnimation(this, DynamicAnimation.TRANSLATION_X, 0F).apply {
        spring.dampingRatio = SpringForce.DAMPING_RATIO_NO_BOUNCY
        spring.stiffness = 100F
        start()
    }
}

fun View.animateSlideY() {
    SpringAnimation(this, DynamicAnimation.TRANSLATION_Y, 0F).apply {
        spring.dampingRatio = SpringForce.DAMPING_RATIO_NO_BOUNCY
        spring.stiffness = 100F
        start()
    }
}

fun View.animateSlideXY() {
    /**
     * spring.dampingRatio = 0.75F
     * spring.stiffness = 100F
     * delay(550L)
     */

    SpringAnimation(this, DynamicAnimation.TRANSLATION_X, 0F).apply {
        spring.dampingRatio = 0.75F
        spring.stiffness = 180F
        start()
    }

    SpringAnimation(this, DynamicAnimation.TRANSLATION_Y, 0F).apply {
        spring.dampingRatio = 0.75F
        spring.stiffness = 180F
        start()
    }
}

fun View.animateScale() {
    SpringAnimation(this, DynamicAnimation.SCALE_X, 1.1F).apply {
        spring.dampingRatio = 0.4F
        spring.stiffness = 120F
        start()
    }

    SpringAnimation(this, DynamicAnimation.SCALE_Y, 1.1F).apply {
        spring.dampingRatio = 0.4F
        spring.stiffness = 120F
        start()
    }
}

fun View.animateZoomOut() {
    val finalPosition = -dipToPx(18F).toFloat()
    SpringAnimation(this, DynamicAnimation.TRANSLATION_Y, finalPosition).apply {
        spring.dampingRatio = 0.6F
        spring.stiffness = 80F
        start()
    }
    SpringAnimation(this, DynamicAnimation.SCALE_X, 0.84F).apply {
        spring.dampingRatio = SpringForce.DAMPING_RATIO_NO_BOUNCY
        spring.stiffness = 30F
        start()
    }
    SpringAnimation(this, DynamicAnimation.SCALE_Y, 0.84F).apply {
        spring.dampingRatio = SpringForce.DAMPING_RATIO_NO_BOUNCY
        spring.stiffness = 30F
        start()
    }
}

fun View.animateScaleXY(position: Float) {
    SpringAnimation(this, DynamicAnimation.SCALE_X, position).apply {
        spring.dampingRatio = SpringForce.DAMPING_RATIO_MEDIUM_BOUNCY
        spring.stiffness = SpringForce.STIFFNESS_LOW
        start()
    }
    SpringAnimation(this, DynamicAnimation.SCALE_Y, position).apply {
        spring.dampingRatio = SpringForce.DAMPING_RATIO_MEDIUM_BOUNCY
        spring.stiffness = SpringForce.STIFFNESS_LOW
        start()
    }
}

fun View.animateScaleXYNoBouncy(position: Float) {
    SpringAnimation(this, DynamicAnimation.SCALE_X, position).apply {
        spring.dampingRatio = SpringForce.DAMPING_RATIO_NO_BOUNCY
        spring.stiffness = 100F
        start()
    }
    SpringAnimation(this, DynamicAnimation.SCALE_Y, position).apply {
        spring.dampingRatio = SpringForce.DAMPING_RATIO_NO_BOUNCY
        spring.stiffness = 100F
        start()
    }
}

fun View.animateScaleXYAndAlpha(duration: Long) {
    val animScaleX = ObjectAnimator.ofFloat(this, "scaleX",1F, 2F).apply {
        repeatMode = ValueAnimator.RESTART
    }
    val animScaleY = ObjectAnimator.ofFloat(this, "scaleY",1F, 2F).apply {
        repeatMode = ValueAnimator.RESTART
    }
    val animAlpha = ObjectAnimator.ofFloat(this, "alpha",  1F, 0F).apply {
        repeatMode = ValueAnimator.RESTART
    }
    AnimatorSet().apply {
        playTogether(animScaleX, animScaleY, animAlpha)
        setDuration(duration)
        start()

    }
}

fun View.animateAlpha(duration: Long) {
    val animAlpha = ObjectAnimator.ofFloat(this, "alpha", 0F, 1F, 0F).apply {
        repeatMode = ValueAnimator.RESTART
    }
    AnimatorSet().apply {
        play(animAlpha)
        setDuration(duration)
        start()
    }
}

fun View.animateSpin() {
    val animation = AnimationUtils.loadAnimation(context, R.anim.rotate_cycle)
    animation.interpolator = LinearInterpolator()
    animation.repeatCount = Animation.INFINITE
    startAnimation(animation)
}

fun View.animateAlpha() {
    val animation = AnimationUtils.loadAnimation(context, R.anim.alpha_cycle)
    animation.interpolator = LinearInterpolator()
    animation.repeatCount = Animation.INFINITE
    startAnimation(animation)
}

/** @see [Lookup resource name](https://stackoverflow.com/questions/10137692/how-to-get-resource-name-from-resource-id)
 */
fun View.getResIdName(resources: Resources?): String? {
    if (resources == null) return null
    return try {
        resources.getResourceEntryName(this.id)
    } catch (ignored: Throwable) {
        null
    }
}

fun View.getResIdName(): String? {
    return getResIdName(context.resources)
}



fun EditText.getFocusedLiveData(): LiveData<Boolean> {
    val ret = MutableLiveData(isFocused)
    setOnFocusChangeListener { v, hasFocus ->
        ret.value = hasFocus
    }

    return ret
}

fun EditText.moveCursorToEnd() {
    setSelection(text?.length ?: 0)
}

fun TextView.getTouchOffset(event: MotionEvent): Int {
    val widget = this
    var x = event.x.toInt()
    var y = event.y.toInt()
    x -= widget.totalPaddingLeft
    y -= widget.totalPaddingTop
    x += widget.scrollX
    y += widget.scrollY
    val layout = widget.layout
    val line = layout.getLineForVertical(y)
    return layout.getOffsetForHorizontal(line, x.toFloat())
}


fun View.findActivity(): Activity? {
    var context = this.context
    while (context is ContextWrapper) {
        if (context is Activity) {
            return context
        }
        context = context.baseContext
    }
    return null
}
