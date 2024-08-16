package ceui.pixiv.utils

import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import kotlin.math.roundToInt


object ShapedDrawables {
    fun getOval(borderWidth: Float, borderColor: Int, backgroundColor: Int): Drawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(backgroundColor)
            setStroke(borderWidth.roundToInt(), borderColor)
        }
    }

    fun getRoundedRect(cornerRadius: Float, borderWidth: Float, borderColor: Int, backgroundColor: Int, dashWidth: Float = 0F, dashGap: Float = 0F): Drawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setCornerRadius(cornerRadius)
            setColor(backgroundColor)
            setStroke(borderWidth.roundToInt(), borderColor, dashWidth, dashGap)
        }
    }

    fun getRoundedRect(
        cornerRadii: FloatArray,
        borderWidth: Float,
        borderColor: Int,
        backgroundColor: Int,
        dashWidth: Float = 0F,
        dashGap: Float = 0F
    ): Drawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setCornerRadii(cornerRadii)
            setColor(backgroundColor)
            setStroke(borderWidth.roundToInt(), borderColor, dashWidth, dashGap)
        }
    }
}

fun getIntColor(colorStr: String?, fallback: Int = Color.TRANSPARENT): Int {
    return try {
        if (colorStr?.isNotEmpty() == true) {
            Color.parseColor(colorStr)
        } else {
            fallback
        }
    } catch (ex: Exception) {
        ex.printStackTrace()
        fallback
    }
}

fun getIntColorJava(colorStr: String?): Int {
    return if (colorStr != null) Color.parseColor(colorStr).toInt() else Color.TRANSPARENT
}
