package ceui.pixiv.ui.opengl


import android.content.Context
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import timber.log.Timber
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class MyShaderRenderer(private val context: Context) : GLSurfaceView.Renderer {

    private var program = 0
    private var startTime = System.currentTimeMillis()

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        val vertexShaderCode = """
            attribute vec4 aPosition;
            void main() {
                gl_Position = aPosition;
            }
        """.trimIndent()

        val fragmentShaderCode = """
            precision mediump float;
            uniform vec3 iResolution;
            uniform float iTime;
            
            #define PI 3.14159
            
            float vDrop(vec2 uv, float t) {
                uv.x = uv.x * 80.0;
                float dx = fract(uv.x);
                uv.x = floor(uv.x);
                uv.y *= 0.05;
                float o = sin(uv.x * 250.0);
                float s = cos(uv.x * 3.1) * 0.2;
                float trail = mix(5.0, 15.0, s);
                float yv = fract(uv.y + t * s + o) * trail;
                yv = 1.0 / yv;
                yv = smoothstep(0.0, 1.0, yv * yv);
                yv = sin(yv * PI) * (s * 2.0);
                float d2 = sin(dx * PI);
                return yv * (d2 * d2);
            }
            
            void main() {
                vec2 fragCoord = gl_FragCoord.xy;
                vec2 p = (fragCoord.xy - 0.5 * iResolution.xy) / iResolution.y;
                float d = length(p) + 0.1;
                p = vec2(atan(p.x, p.y) / PI, 1.5 / d);
                float t = iTime * 0.4;
            
                vec3 col = vec3(1.55,0.65,.225) * vDrop(p, t);
                col += vec3(0.55,0.75,1.225) * vDrop(p, t + 0.33);
                col += vec3(0.45,1.15,0.425) * vDrop(p, t + 0.66);
                gl_FragColor = vec4(col * (d * d), 1.0);
            }
        """.trimIndent()
        program = createProgram(vertexShaderCode, fragmentShaderCode)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        GLES20.glUseProgram(program)

        val iTime = ((System.currentTimeMillis() - startTime) / 1000f) % 60f
        Timber.d("dsadasads2 iTime: ${iTime}")
        val resolutionHandle = GLES20.glGetUniformLocation(program, "iResolution")
        GLES20.glUniform3f(resolutionHandle, 1080f, 1920f, 1f) // use actual size

        val timeHandle = GLES20.glGetUniformLocation(program, "iTime")
        GLES20.glUniform1f(timeHandle, iTime)

        drawFullScreenQuad()
    }

    private fun drawFullScreenQuad() {
        val vertices = floatArrayOf(
            -1f, -1f, 1f, -1f, -1f, 1f,
            1f, -1f, 1f, 1f, -1f, 1f
        )

        val vertexBuffer = ByteBuffer.allocateDirect(vertices.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
                put(vertices)
                position(0)
            }

        val positionHandle = GLES20.glGetAttribLocation(program, "aPosition")
        GLES20.glEnableVertexAttribArray(positionHandle)
        GLES20.glVertexAttribPointer(positionHandle, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 6)
        GLES20.glDisableVertexAttribArray(positionHandle)
    }
}

fun createProgram(vertexCode: String, fragmentCode: String): Int {
    val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexCode)
    val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentCode)
    val program = GLES20.glCreateProgram()
    GLES20.glAttachShader(program, vertexShader)
    GLES20.glAttachShader(program, fragmentShader)
    GLES20.glLinkProgram(program)
    return program
}

fun loadShader(type: Int, code: String): Int {
    val shader = GLES20.glCreateShader(type)
    GLES20.glShaderSource(shader, code)
    GLES20.glCompileShader(shader)
    return shader
}
