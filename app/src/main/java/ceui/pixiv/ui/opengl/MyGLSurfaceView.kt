package ceui.pixiv.ui.opengl

import android.content.Context
import android.graphics.PixelFormat
import android.opengl.GLSurfaceView
import android.view.MotionEvent


class MyGLSurfaceView(context: Context) : GLSurfaceView(context) {
    private val myRenderer: MyRenderer

    init {
        setEGLContextClientVersion(2)

        // 设置透明背景
        holder.setFormat(PixelFormat.TRANSLUCENT)
        setZOrderOnTop(true) // 必须设为顶层，否则透明无效

        setEGLConfigChooser(8, 8, 8, 8, 16, 0) // 启用 8-bit alpha 通道

        myRenderer = MyRenderer()
        setRenderer(myRenderer)
        renderMode = RENDERMODE_CONTINUOUSLY
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y
        myRenderer.setTouch(x, y, width, height)
        return true
    }
}
