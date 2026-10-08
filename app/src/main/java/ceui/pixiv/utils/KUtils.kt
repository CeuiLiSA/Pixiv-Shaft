package ceui.pixiv.utils

import android.animation.AnimatorInflater
import android.content.res.Resources
import android.view.View
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import ceui.lisa.R

fun <T: View> T.setOnClick(listener: (T) -> Unit) {
    stateListAnimator =
        AnimatorInflater.loadStateListAnimator(context, R.animator.button_press_alpha)
    setOnClickListener {
        listener(this)
    }
}


internal val Int.ppppx: Int
    get() = (this * Resources.getSystem().displayMetrics.density).toInt()

internal val screenWidth: Int
    get() = Resources.getSystem().displayMetrics.widthPixels

internal val screenHeight: Int
    get() = Resources.getSystem().displayMetrics.heightPixels


fun View.animateFadeIn() {
    SpringAnimation(this, DynamicAnimation.ALPHA, 1F).apply {
        spring.dampingRatio = SpringForce.DAMPING_RATIO_NO_BOUNCY
        spring.stiffness = 15F
        start()
    }
}

fun View.animateFadeOut() {
    SpringAnimation(this, DynamicAnimation.ALPHA, 0F).apply {
        spring.dampingRatio = SpringForce.DAMPING_RATIO_NO_BOUNCY
        spring.stiffness = 15F
        start()
    }
}

/**
 * 把标题折成一行：任何空白串（含换行）折成一个空格，并去掉首尾空白。
 *
 * 只给「单行展示面」用，不要拿它替换原始标题。pixiv 的标题可以自带换行，例如
 * illust 150482199 的标题就是 `神子   \n\n八重神子，Yae Miko，原神`：官 app 与 V3
 * 详情页的 hero 标题都原样排成多行（中间那行是空的），那是作者写进标题的内容，
 * 不是我们的缺陷。
 *
 * 经典详情页标题（fragment_illust.xml 的 @id/title）却是 16dp 固定高的单行盒：
 * maxLines=1 加 autoSizeTextType=uniform(6sp..20sp) 之下，AppCompat 的 autosize
 * 「装得下」判据要求整段文字都排进 maxLines 行内
 * （AppCompatTextViewAutoSizeHelper#suggestedSizeFitsInSpace：
 * getLineEnd(last) != text.length() 即判为装不下），含换行的标题在任何字号下都
 * 判为装不下，字号于是被钉死在 autoSizeMinTextSize=6sp，再被 ellipsize 补一个
 * 「…」。单行盒承载不了作者换行，就在这一处折平；长按复制、分享、下载命名等
 * 仍用原始标题。
 *
 * 只折 ASCII 空白（\s）。全角空格 U+3000 不折——它不是换行，不会触发上面那条判据。
 */
fun String?.singleLineTitle(): String = orEmpty().replace(Regex("\\s+"), " ").trim()
