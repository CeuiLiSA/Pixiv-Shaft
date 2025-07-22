package ceui.pixiv.ui.opengl

import android.content.Context
import android.opengl.GLSurfaceView


class MyGLSurfaceView(context: Context) : GLSurfaceView(context) {

    private val renderer: MyShaderRenderer

    init {
        // 设置 OpenGL ES 2.0 环境
        setEGLContextClientVersion(2)

        // 初始化并设置 Renderer
        renderer = MyShaderRenderer(context)
        setRenderer(renderer)

        // 设置渲染模式：连续渲染或按需渲染
        renderMode = RENDERMODE_CONTINUOUSLY
    }
}
