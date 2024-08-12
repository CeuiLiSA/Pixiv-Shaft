package ceui.pixiv.ui.common

import android.content.Context
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import androidx.annotation.DrawableRes
import androidx.appcompat.content.res.AppCompatResources
import androidx.recyclerview.widget.RecyclerView

class BottomDividerDecoration(
    private val context: Context,
    @DrawableRes private val dividerRes: Int,
    private val marginLeft: Int = 0,
    private val marginRight: Int = 0
) : RecyclerView.ItemDecoration() {

    private val divider: Drawable = AppCompatResources.getDrawable(context, dividerRes) ?: throw IllegalArgumentException("Invalid drawable resource")

    override fun onDraw(canvas: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        super.onDraw(canvas, parent, state)
        drawBottomDividers(canvas, parent)
    }

    private fun drawBottomDividers(canvas: Canvas, parent: RecyclerView) {
        val left: Int = parent.paddingLeft + marginLeft
        val right: Int = parent.width - parent.paddingRight - marginRight
        val childCount: Int = parent.childCount

        for (i in 0 until childCount) {
            val child = parent.getChildAt(i)
            if (parent.getChildAdapterPosition(child) == RecyclerView.NO_POSITION) continue

            // Check if the current item is the last item
            val isLastItem = parent.getChildAdapterPosition(child) == parent.adapter?.itemCount?.minus(1)

            // Only draw the divider if it is not the last item
            if (!isLastItem) {
                val params = child.layoutParams as RecyclerView.LayoutParams
                val top = child.bottom + params.bottomMargin
                val bottom = top + divider.intrinsicHeight

                divider.setBounds(left, top, right, bottom)
                divider.draw(canvas)
            }
        }
    }
}
