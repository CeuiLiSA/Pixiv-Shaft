package ceui.pixiv.ui.opengl


import android.opengl.GLES20
import android.opengl.GLSurfaceView
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class MyRenderer : GLSurfaceView.Renderer {
    private var program = 0
    private var positionHandle = 0
    private var resolutionHandle = 0
    private var trailHandle = 0

    private var viewWidth = 1
    private var viewHeight = 1

    private val maxTrail = 10
    private val trailPoints = ArrayDeque<Pair<Float, Float>>()
    private val trailLock = Any()


    fun setTouch(x: Float, y: Float, width: Int, height: Int) {
        viewWidth = width
        viewHeight = height
        val normX = x / width.toFloat()
        val normY = 1f - y / height.toFloat()

        synchronized(trailLock) {
            trailPoints.addFirst(normX to normY)
            if (trailPoints.size > maxTrail) trailPoints.removeLast()
        }
    }


    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 0f)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)

        val vertexShaderCode = """
            attribute vec4 a_Position;
            void main() {
                gl_Position = a_Position;
            }
        """.trimIndent()

        val fragmentShaderCode = """
            precision mediump float;
            uniform vec2 u_Resolution;
            uniform vec2 u_Trail[10];
            
            void main() {
                vec2 uv = gl_FragCoord.xy / u_Resolution;
                vec3 color = vec3(0.0);
                
                for (int i = 0; i < 10; i++) {
                    vec2 p = u_Trail[i];
                    float dist = distance(uv, p);
                    float glow = 0.02 / (dist + 0.01);
                    float fade = 1.0 - float(i) / 10.0;
                    color += vec3(glow * fade, glow * fade * 0.4, glow * fade * 0.2);
                }

                float alpha = clamp(color.r + color.g + color.b, 0.0, 1.0);
                gl_FragColor = vec4(color, alpha);
            }
        """.trimIndent()

        val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexShaderCode)
        val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentShaderCode)

        program = GLES20.glCreateProgram().also {
            GLES20.glAttachShader(it, vertexShader)
            GLES20.glAttachShader(it, fragmentShader)
            GLES20.glLinkProgram(it)
        }

        positionHandle = GLES20.glGetAttribLocation(program, "a_Position")
        resolutionHandle = GLES20.glGetUniformLocation(program, "u_Resolution")
        trailHandle = GLES20.glGetUniformLocation(program, "u_Trail")
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        viewWidth = width
        viewHeight = height
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(program)

        GLES20.glUniform2f(resolutionHandle, viewWidth.toFloat(), viewHeight.toFloat())

        // 填充触摸点数组
        val flatTrail = FloatArray(maxTrail * 2)

        synchronized(trailLock) {
            trailPoints.forEachIndexed { index, (x, y) ->
                if (index < maxTrail) {
                    flatTrail[index * 2] = x
                    flatTrail[index * 2 + 1] = y
                }
            }
        }

        GLES20.glUniform2fv(trailHandle, maxTrail, flatTrail, 0)

        // 全屏三角形
        val triangleCoords = floatArrayOf(
            -1f, -1f,
            3f, -1f,
            -1f, 3f
        )
        val vertexBuffer: FloatBuffer = ByteBuffer
            .allocateDirect(triangleCoords.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(triangleCoords)
                position(0)
            }

        GLES20.glEnableVertexAttribArray(positionHandle)
        GLES20.glVertexAttribPointer(positionHandle, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 3)
        GLES20.glDisableVertexAttribArray(positionHandle)
    }

    private fun loadShader(type: Int, code: String): Int {
        return GLES20.glCreateShader(type).also { shader ->
            GLES20.glShaderSource(shader, code)
            GLES20.glCompileShader(shader)
            val compiled = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
            if (compiled[0] == 0) {
                val error = GLES20.glGetShaderInfoLog(shader)
                GLES20.glDeleteShader(shader)
                throw RuntimeException("Shader compile error: $error")
            }
        }
    }
}
