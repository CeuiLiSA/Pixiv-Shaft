package ceui.pixiv.ui.opengl


import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.opengl.GLES20.GL_COLOR_BUFFER_BIT
import android.opengl.GLES20.GL_FLOAT
import android.opengl.GLES20.GL_FRAGMENT_SHADER
import android.opengl.GLES20.GL_LINEAR
import android.opengl.GLES20.GL_TEXTURE0
import android.opengl.GLES20.GL_TEXTURE_2D
import android.opengl.GLES20.GL_TEXTURE_MAG_FILTER
import android.opengl.GLES20.GL_TEXTURE_MIN_FILTER
import android.opengl.GLES20.GL_TRIANGLE_FAN
import android.opengl.GLES20.GL_VERTEX_SHADER
import android.opengl.GLES20.glActiveTexture
import android.opengl.GLES20.glAttachShader
import android.opengl.GLES20.glBindTexture
import android.opengl.GLES20.glClear
import android.opengl.GLES20.glClearColor
import android.opengl.GLES20.glCompileShader
import android.opengl.GLES20.glCreateProgram
import android.opengl.GLES20.glCreateShader
import android.opengl.GLES20.glDisableVertexAttribArray
import android.opengl.GLES20.glDrawArrays
import android.opengl.GLES20.glEnableVertexAttribArray
import android.opengl.GLES20.glGenTextures
import android.opengl.GLES20.glGetAttribLocation
import android.opengl.GLES20.glGetUniformLocation
import android.opengl.GLES20.glLinkProgram
import android.opengl.GLES20.glShaderSource
import android.opengl.GLES20.glTexParameteri
import android.opengl.GLES20.glUniform1f
import android.opengl.GLES20.glUniform1i
import android.opengl.GLES20.glUniform2f
import android.opengl.GLES20.glUseProgram
import android.opengl.GLES20.glVertexAttribPointer
import android.opengl.GLES20.glViewport
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import androidx.core.content.ContextCompat
import ceui.lisa.R
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class RippleRenderer(private val context: Context) : GLSurfaceView.Renderer {

    private val vertexShaderCode = """
        attribute vec4 aPosition;
        attribute vec2 aTexCoord;
        varying vec2 vTexCoord;
        void main() {
            gl_Position = aPosition;
            vTexCoord = aTexCoord;
        }
    """

    private val fragmentShaderCode = """
        precision mediump float;
        uniform sampler2D uTexture;
        uniform vec2 uResolution;
        uniform vec2 uTouch;
        uniform float uTime;
        varying vec2 vTexCoord;

        void main() {
            vec2 uv = vTexCoord;
            vec2 center = uTouch / uResolution;
            vec2 delta = uv - center;
            float dist = length(delta);
            float ripple = sin(40.0 * dist - uTime * 3.0) * 0.01;
            vec2 offset = normalize(delta) * ripple;

            vec4 color;
            color.r = texture2D(uTexture, uv + offset * 1.0).r;
            color.g = texture2D(uTexture, uv + offset * 1.2).g;
            color.b = texture2D(uTexture, uv + offset * 1.5).b;
            color.a = 1.0;

            gl_FragColor = color;
        }
    """

    private val quadCoords = floatArrayOf(
        -1f, 1f,  // top left
        -1f, -1f,  // bottom left
        1f, -1f,  // bottom right
        1f, 1f   // top right
    )

    private val texCoords = floatArrayOf(
        0f, 0f,
        0f, 1f,
        1f, 1f,
        1f, 0f
    )

    private val vertexBuffer: FloatBuffer = ByteBuffer.allocateDirect(quadCoords.size * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(quadCoords)
            position(0)
        }

    private val texBuffer: FloatBuffer = ByteBuffer.allocateDirect(texCoords.size * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(texCoords)
            position(0)
        }

    private var program = 0
    private var textureId = 0
    private var startTime = 0L
    private var touchX = 0f
    private var touchY = 0f
    private var viewWidth = 1
    private var viewHeight = 1

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        glClearColor(0f, 0f, 0f, 1f)
        program = createProgram(vertexShaderCode, fragmentShaderCode)
        textureId = loadTexture(R.drawable.sample_bg)
        startTime = System.currentTimeMillis()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        glViewport(0, 0, width, height)
        viewWidth = width
        viewHeight = height
    }

    override fun onDrawFrame(gl: GL10?) {
        glClear(GL_COLOR_BUFFER_BIT)

        glUseProgram(program)

        val aPosition = glGetAttribLocation(program, "aPosition")
        val aTexCoord = glGetAttribLocation(program, "aTexCoord")
        val uTexture = glGetUniformLocation(program, "uTexture")
        val uResolution = glGetUniformLocation(program, "uResolution")
        val uTouch = glGetUniformLocation(program, "uTouch")
        val uTime = glGetUniformLocation(program, "uTime")

        val time = (System.currentTimeMillis() - startTime) / 1000f

        glActiveTexture(GL_TEXTURE0)
        glBindTexture(GL_TEXTURE_2D, textureId)
        glUniform1i(uTexture, 0)

        glUniform2f(uResolution, viewWidth.toFloat(), viewHeight.toFloat())
        glUniform2f(uTouch, touchX, touchY)
        glUniform1f(uTime, time)

        glEnableVertexAttribArray(aPosition)
        glVertexAttribPointer(aPosition, 2, GL_FLOAT, false, 0, vertexBuffer)

        glEnableVertexAttribArray(aTexCoord)
        glVertexAttribPointer(aTexCoord, 2, GL_FLOAT, false, 0, texBuffer)

        glDrawArrays(GL_TRIANGLE_FAN, 0, 4)

        glDisableVertexAttribArray(aPosition)
        glDisableVertexAttribArray(aTexCoord)
    }

    fun setTouch(x: Float, y: Float) {
        touchX = x
        touchY = y
    }

    private fun createProgram(vertexSource: String, fragmentSource: String): Int {
        val vertexShader = loadShader(GL_VERTEX_SHADER, vertexSource)
        val fragmentShader = loadShader(GL_FRAGMENT_SHADER, fragmentSource)
        val program = glCreateProgram()
        glAttachShader(program, vertexShader)
        glAttachShader(program, fragmentShader)
        glLinkProgram(program)
        return program
    }

    private fun loadShader(type: Int, shaderCode: String): Int {
        val shader = glCreateShader(type)
        glShaderSource(shader, shaderCode)
        glCompileShader(shader)
        return shader
    }

    private fun loadTexture(resourceId: Int): Int {
        val textures = IntArray(1)
        glGenTextures(1, textures, 0)
        val textureId = textures[0]

        BitmapFactory.Options().apply { inScaled = false }
        val bitmap = getBitmapFromVectorDrawable(context, resourceId)

        glBindTexture(GL_TEXTURE_2D, textureId)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
        GLUtils.texImage2D(GL_TEXTURE_2D, 0, bitmap, 0)
        bitmap.recycle()

        return textureId
    }

    fun getBitmapFromVectorDrawable(context: Context, drawableId: Int): Bitmap {
        val drawable = ContextCompat.getDrawable(context, drawableId)
            ?: throw IllegalArgumentException("Drawable not found")

        val width = if (drawable.intrinsicWidth > 0) drawable.intrinsicWidth else 1024
        val height = if (drawable.intrinsicHeight > 0) drawable.intrinsicHeight else 1024

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        return bitmap
    }


}