/*
 * Telegram Android SpoilerEffect2, adapted for Pixiv-Shaft package and dependency names.
 * Upstream: https://github.com/DrKLO/Telegram
 * Revision: 45ab8f4308496e1f01026a97fcdb0d58a5274474
 *
 * Behavioural change vs upstream: the shared instance is now kept per window instead of once per
 * process — this app draws spoilers in several Activities, and upstream's single instance leaves
 * its TextureView in whichever window created it. See the {@code instances} field comment.
 * Licensed under GNU GPL v2; see the repository root LICENSE.
 */
package ceui.pixiv.widget.telegram;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.SurfaceTexture;
import android.opengl.EGL14;
import android.opengl.EGLExt;
import android.opengl.GLES20;
import android.opengl.GLES31;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;

import ceui.lisa.R;

import java.util.ArrayList;
import java.util.HashMap;

import javax.microedition.khronos.egl.EGL10;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.egl.EGLContext;
import javax.microedition.khronos.egl.EGLDisplay;
import javax.microedition.khronos.egl.EGLSurface;

public class SpoilerEffect2 {

    public final int MAX_FPS;
    private final double MIN_DELTA;
    private final double MAX_DELTA;

    public static final int TYPE_DEFAULT = 0;
    public static final int TYPE_PREVIEW = 1;

    public final int type;

    /**
     * 上游这里无条件 true，靠调用侧 LiteMode 挡低端机；本仓移植后没有那层，v4.8.4 因此在
     * 只支持 ES2 / 驱动有问题的机器上启动即 native crash（Firebase 无日志）。现在收口到
     * {@link ceui.pixiv.widget.SpoilerParticleSupport}：能力检查 + 上次崩在 GL 初始化窗口
     * 的熔断。不支持的设备只出模糊遮罩，不画粒子。
     */
    public static boolean supports() {
        return ceui.pixiv.widget.SpoilerParticleSupport.isSupported(AndroidUtilities.applicationContext());
    }

    /**
     * 活着的实例，**按 (type, 窗口) 各一份**。
     *
     * <p>上游是单 Activity 的应用，这里一个 map key 只按 type 存一份全进程共享的实例就够了。
     * 本仓不是：粒子在瀑布流卡（MainActivity/TemplateActivity）和作品详情页的整页遮罩
     * （VActivity）上都要画。而实例的 TextureView 是 {@link #makeTextureViewContainer} 塞进
     * **某一个 Activity 的 DecorView** 里的，那块 SurfaceTexture 随那个窗口一起生灭：窗口一走
     * {@code onSurfaceTextureDestroyed} 就把渲染线程 halt 掉且**永不重启**（它只在 surface
     * 重新 available 时才建线程，而挂在死窗口上的 TextureView 再也等不到）。共享一份的后果是
     * 「在详情页屏蔽一件作品、返回瀑布流，卡片模糊了但粒子一动不动」——holder 拿到的是上一个
     * 窗口留下的空纹理。平板分屏（Activity Embedding）下两个窗口同时可见，更是必须各一份。
     *
     * <p>按 rootView 找而不是按 Activity 找：这一层只认得 view 树，且 {@link #destroy()} 本来
     * 就是拿 container 的 parent 摘干净的。表长实际上就是 1（同一时刻通常只有一个窗口在画粒子）：
     * 最后一个 holder detach 30ms 后 {@code checkDestroy} 会销毁该实例并从这里摘掉，所以线性扫
     * 比 HashMap 还省。**只在主线程读写**（holder 的 attach/detach、checkDestroy 都在主线程），
     * 与上游对那个静态 map 的假设一致，不额外加锁。
     */
    private static final ArrayList<SpoilerEffect2> instances = new ArrayList<>();

    public static SpoilerEffect2 getInstance(View view) {
        return getInstance(TYPE_DEFAULT, view);
    }

    public static SpoilerEffect2 getInstance(int type, View view) {
        return getInstance(type, view, getRootView(view));
    }

    public static SpoilerEffect2 getInstance(int type, View view, ViewGroup rootView) {
        if (view == null || rootView == null) {
            return null;
        }
        // 先 initialize 再问 supports()：能力检查要用 applicationContext，
        // 反过来的话首次调用时 context 恒 null，粒子会被永远误判为不支持。
        AndroidUtilities.initialize(view);
        if (!supports()) {
            return null;
        }
        SpoilerEffect2 e = null;
        for (int i = instances.size() - 1; i >= 0; --i) {
            SpoilerEffect2 s = instances.get(i);
            // 已销毁、或所在窗口已经没了（Activity 销毁 → 整棵树 detach，container 的 parent
            // 还在、但不再 attachedToWindow）的一律摘掉：它的 surface 早随窗口销毁、渲染线程也
            // 已 halt，留着只会白攥住一个死 Activity 的 DecorView。这里只丢引用不 destroy()，
            // 是因为 destroy() 会反过来动 instances，索引循环里改两次表要出错。
            if (s.destroyed || !s.textureViewContainer.isAttachedToWindow()) {
                instances.remove(i);
                continue;
            }
            if (s.type == type && s.textureViewContainer.getRootView() == rootView) {
                e = s;
            }
        }
        if (e == null) {
            final int sz = getSize();
            e = new SpoilerEffect2(type, makeTextureViewContainer(rootView), sz, sz);
            instances.add(e);
        }
        e.attach(view);
        return e;
    }

    private static ViewGroup getRootView(View view) {
        Activity activity = AndroidUtilities.findActivity(view.getContext());
        if (activity == null) {
            return null;
        }
        View rootView = activity.findViewById(android.R.id.content).getRootView();
        if (!(rootView instanceof ViewGroup)) {
            return null;
        }
        return (ViewGroup) rootView;
    }

    public static void pause(boolean pause) {
        for (int i = 0; i < instances.size(); ++i) {
            SpoilerEffect2 s = instances.get(i);
            if (s.thread != null) s.thread.pause(pause);
        }
    }

    public static void pause(int type, boolean pause) {
        for (int i = 0; i < instances.size(); ++i) {
            SpoilerEffect2 s = instances.get(i);
            if (s.type == type && s.thread != null) s.thread.pause(pause);
        }
    }

    private static int getSize() {
        switch (SharedConfig.getDevicePerformanceClass()) {
            case SharedConfig.PERFORMANCE_CLASS_HIGH:
                return Math.min(1280, (int) ((AndroidUtilities.displaySize.x + AndroidUtilities.displaySize.y) / 2f * 1.0f));
            case SharedConfig.PERFORMANCE_CLASS_AVERAGE:
                return Math.min(900, (int) ((AndroidUtilities.displaySize.x + AndroidUtilities.displaySize.y) / 2f * .8f));
            case SharedConfig.PERFORMANCE_CLASS_LOW:
            default:
                return Math.min(720, (int) ((AndroidUtilities.displaySize.x + AndroidUtilities.displaySize.y) / 2f * .7f));
        }
    }


    private static FrameLayout makeTextureViewContainer(ViewGroup rootView) {
        FrameLayout container = new FrameLayout(rootView.getContext()) {
            @Override
            protected boolean drawChild(@NonNull Canvas canvas, View child, long drawingTime) {
                return false;
            }
        };
        rootView.addView(container);
        return container;
    }

    private final ViewGroup textureViewContainer;
    private final TextureView textureView;
    private SpoilerThread thread;
    private int width, height;
    public boolean destroyed;

    private final ArrayList<View> holders = new ArrayList<>();
    private final HashMap<View, Integer> holdersToIndex = new HashMap<>();
    private int holdersIndex = 0;

    public void attach(View view) {
        if (destroyed) {
            return;
        }
        if (!holders.contains(view)) {
            holders.add(view);
            holdersToIndex.put(view, holdersIndex++);
        }
    }

    public void reassignAttach(View view, int index) {
        holdersToIndex.put(view, index);
    }

    public int getAttachIndex(View view) {
        Integer index = holdersToIndex.get(view);
        if (index == null) {
            index = 0;
        }
        return index;
    }

    public void detach(View view) {
        holders.remove(view);
        holdersToIndex.remove(view);
        if (!destroyed) {
            AndroidUtilities.cancelRunOnUIThread(checkDestroy);
            AndroidUtilities.runOnUIThread(checkDestroy, 30);
        }
    }

    public void invalidate() {
        for (int i = 0; i < holders.size(); ++i) {
            holders.get(i).invalidate();
        }
    }

    private final Runnable checkDestroy = () -> {
        if (holders.isEmpty()) {
            destroy();
        }
    };

    public void draw(Canvas canvas, View view) {
        draw(canvas, view, view.getWidth(), view.getHeight(), 1f);
    }

    public void draw(Canvas canvas, View view, int w, int h) {
        draw(canvas, view, w, h, 1f);
    }

    public void draw(Canvas canvas, View view, int w, int h, float alpha) {
        draw(canvas, view, w, h, alpha, false);
    }

    public void draw(Canvas canvas, View view, int w, int h, float alpha, boolean toBitmap) {
        if (canvas == null || view == null) {
            return;
        }
        canvas.save();
        int ow = width, oh = height;
        Integer index = holdersToIndex.get(view);
        if (index == null) {
            index = 0;
        }
        if (w > ow || h > oh) {
            final float scale = Math.max(w / (float) ow, h / (float) oh);
            canvas.scale(scale, scale);
        }
        if ((index % 4) == 1) {
            canvas.rotate(180, ow / 2f, oh / 2f);
        }
        if ((index % 4) == 2) {
            canvas.scale(-1, 1, ow / 2f, oh / 2f);
        }
        if ((index % 4) == 3) {
            canvas.scale(1, -1, ow / 2f, oh / 2f);
        }
        if (toBitmap) {
            Bitmap bitmap = textureView.getBitmap();
            if (bitmap != null) {
                Paint paint = new Paint(Paint.DITHER_FLAG | Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
                paint.setColor(Color.WHITE);
                canvas.drawBitmap(bitmap, 0, 0, paint);
                bitmap.recycle();
            }
        } else {
            textureView.setAlpha(alpha);
            textureView.draw(canvas);
        }
        canvas.restore();
    }

    private void destroy() {
        destroyed = true;
        // 只摘自己这一条：别的窗口那份还有 holder 在用（上游这里是 instance = null，
        // 因为它全进程只有一份）
        instances.remove(this);
        if (thread != null) {
            thread.halt();
            thread = null;
        }
        textureViewContainer.removeView(textureView);
        if (textureViewContainer.getParent() instanceof ViewGroup) {
            ViewGroup rootView = (ViewGroup) textureViewContainer.getParent();
            rootView.removeView(textureViewContainer);
        }
    }

    private SpoilerEffect2(int type, ViewGroup container, int width, int height) {
        MAX_FPS = (int) AndroidUtilities.screenRefreshRate;
        MIN_DELTA = 1.0 / MAX_FPS;
        MAX_DELTA = MIN_DELTA * 4;

        this.type = type;
        this.width = width;
        this.height = height;

        textureViewContainer = container;
        textureView = new TextureView(textureViewContainer.getContext()) {
            @Override
            protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                setMeasuredDimension(SpoilerEffect2.this.width, SpoilerEffect2.this.height);
            }
        };
        textureView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(@NonNull SurfaceTexture surface, int width, int height) {
                if (thread == null) {
                    thread = new SpoilerThread(surface, width, height, SpoilerEffect2.this::invalidate);
                    thread.start();
                }
            }

            @Override
            public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture surface, int width, int height) {
                if (thread != null) {
                    thread.updateSize(width, height);
                }
            }

            @Override
            public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture surface) {
                if (thread != null) {
                    thread.halt();
                    thread = null;
                }
                return true;
            }

            @Override
            public void onSurfaceTextureUpdated(@NonNull SurfaceTexture surface) {

            }
        });
        textureView.setOpaque(false);
        textureViewContainer.addView(textureView);
    }

    private void resize(int w, int h) {
        if (this.width == w && this.height == h) {
            return;
        }
        this.width = w;
        this.height = h;
        textureView.requestLayout();
    }

    private class SpoilerThread extends Thread {
        private volatile boolean running = true;
        private volatile boolean paused = false;

        private final Runnable invalidate;
        private final SurfaceTexture surfaceTexture;
        private final Object resizeLock = new Object();
        private boolean resize;
        private int width, height;
        private int particlesCount;
        private final float radius = AndroidUtilities.dpf2(1.2f);

        public SpoilerThread(SurfaceTexture surfaceTexture, int width, int height, Runnable invalidate) {
            this.invalidate = invalidate;
            this.surfaceTexture = surfaceTexture;
            this.width = width;
            this.height = height;
            this.particlesCount = particlesCount();
        }

        private int particlesCount() {
            return (int) Utilities.clamp(width * height / (500f * 500f) * 1000, 10000, 500);
        }

        public void updateSize(int width, int height) {
            synchronized (resizeLock) {
                resize = true;
                this.width = width;
                this.height = height;
            }
        }

        public void halt() {
            running = false;
        }

        public void pause(boolean paused) {
            this.paused = paused;
        }

        @Override
        public void run() {
            // 整个线程 try-catch 兜底 + GL 危险窗口（init + 首帧）打点：
            // - probe 标记落盘期间进程死掉（驱动 native crash，这里根本拦不住）＝下次启动
            //   SpoilerParticleSupport 熔断，永久禁用粒子，打破「启动即崩」死循环；
            // - Java 异常兜住降级为本进程禁用，纯装饰的粒子不配崩进程。
            // finally 兜掉 init 优雅失败（running=false）和异常路径的标记清理——只有真正的
            // 进程死亡才会把标记留在盘上。
            try {
                ceui.pixiv.widget.SpoilerParticleSupport.onGlProbeStarted();
                init();
                boolean firstFrameDrawn = false;
                long lastTime = System.nanoTime();
                while (running) {
                    final long now = System.nanoTime();
                    double dt = (now - lastTime) / 1_000_000_000.;
                    lastTime = now;

                    if (dt < MIN_DELTA) {
                        double wait = MIN_DELTA - dt;
                        try {
                            long milli = (long) (wait * 1000L);
                            int nano = (int) ((wait - milli / 1000.) * 1_000_000_000);
                            sleep(milli, nano);
                        } catch (Exception ignore) {}
                        dt = MIN_DELTA;
                    } else if (dt > MAX_DELTA) {
                        dt = MAX_DELTA;
                    }

                    while (paused) {
                        try {
                            sleep(1000);
                        } catch (Exception ignore) {}
                    }

                    checkResize();
                    drawFrame((float) dt);
                    if (!firstFrameDrawn) {
                        firstFrameDrawn = true;
                        ceui.pixiv.widget.SpoilerParticleSupport.onGlProbeFinished();
                    }

                    AndroidUtilities.cancelRunOnUIThread(this.invalidate);
                    AndroidUtilities.runOnUIThread(this.invalidate);
                }
                die();
            } catch (Throwable t) {
                FileLog.e(t);
                ceui.pixiv.widget.SpoilerParticleSupport.onRenderFailed(t);
                try {
                    die();
                } catch (Throwable cleanupError) {
                    FileLog.e(cleanupError);
                }
            } finally {
                ceui.pixiv.widget.SpoilerParticleSupport.onGlProbeFinished();
            }
        }

        private EGL10 egl;
        private EGLDisplay eglDisplay;
        private EGLConfig eglConfig;
        private EGLSurface eglSurface;
        private EGLContext eglContext;

        private int drawProgram;
        private int resetHandle;
        private int timeHandle;
        private int deltaTimeHandle;
        private int sizeHandle;
        private int radiusHandle;
        private int seedHandle;

        private boolean reset = true;

        private int currentBuffer = 0;
        private int[] particlesData;

        private void init() {
            egl = (EGL10) javax.microedition.khronos.egl.EGLContext.getEGL();

            eglDisplay = egl.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
            if (eglDisplay == EGL10.EGL_NO_DISPLAY) {
                running = false;
                return;
            }
            int[] version = new int[2];
            if (!egl.eglInitialize(eglDisplay, version)) {
                running = false;
                return;
            }

            int[] configAttributes = {
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
                EGL14.EGL_NONE
            };
            EGLConfig[] eglConfigs = new EGLConfig[1];
            int[] numConfigs = new int[1];
            // 上游只看返回值。拿不到 ES3 config 的机器（Mali-400 等只支持 ES2 的老 GPU）上
            // eglChooseConfig 会返回 true 但 numConfigs=0，eglConfigs[0] 是 null，拿它去
            // eglCreateContext 直接死在平台 EGL 层——v4.8.4 启动闪退的其中一条路。
            if (!egl.eglChooseConfig(eglDisplay, configAttributes, eglConfigs, 1, numConfigs)
                    || numConfigs[0] <= 0 || eglConfigs[0] == null) {
                FileLog.e("SpoilerEffect2, no OpenGL ES 3.0 EGL config on this device");
                running = false;
                return;
            }
            eglConfig = eglConfigs[0];

            int[] contextAttributes = {
                EGL14.EGL_CONTEXT_CLIENT_VERSION, 3,
                EGL14.EGL_NONE
            };
            eglContext = egl.eglCreateContext(eglDisplay, eglConfig, EGL10.EGL_NO_CONTEXT, contextAttributes);
            // 失败时返回的是 EGL_NO_CONTEXT / EGL_NO_SURFACE 而不是 null，上游只判 null 等于没判
            if (eglContext == null || eglContext == EGL10.EGL_NO_CONTEXT) {
                running = false;
                return;
            }

            eglSurface = egl.eglCreateWindowSurface(eglDisplay, eglConfig, surfaceTexture, null);
            if (eglSurface == null || eglSurface == EGL10.EGL_NO_SURFACE) {
                running = false;
                return;
            }

            if (!egl.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
                running = false;
                return;
            }

            genParticlesData();

            // draw program (vertex and fragment shaders)
            int vertexShader = GLES31.glCreateShader(GLES31.GL_VERTEX_SHADER);
            int fragmentShader = GLES31.glCreateShader(GLES31.GL_FRAGMENT_SHADER);
            if (vertexShader == 0 || fragmentShader == 0) {
                running = false;
                return;
            }
            GLES31.glShaderSource(vertexShader, AndroidUtilities.readRes(R.raw.spoiler_vertex));
            GLES31.glCompileShader(vertexShader);
            int[] status = new int[1];
            GLES31.glGetShaderiv(vertexShader, GLES31.GL_COMPILE_STATUS, status, 0);
            if (status[0] == 0) {
                FileLog.e("SpoilerEffect2, compile vertex shader error: " + GLES31.glGetShaderInfoLog(vertexShader));
                GLES31.glDeleteShader(vertexShader);
                running = false;
                return;
            }
            GLES31.glShaderSource(fragmentShader, AndroidUtilities.readRes(R.raw.spoiler_fragment));
            GLES31.glCompileShader(fragmentShader);
            GLES31.glGetShaderiv(fragmentShader, GLES31.GL_COMPILE_STATUS, status, 0);
            if (status[0] == 0) {
                FileLog.e("SpoilerEffect2, compile fragment shader error: " + GLES31.glGetShaderInfoLog(fragmentShader));
                GLES31.glDeleteShader(fragmentShader);
                running = false;
                return;
            }
            drawProgram = GLES31.glCreateProgram();
            if (drawProgram == 0) {
                running = false;
                return;
            }
            GLES31.glAttachShader(drawProgram, vertexShader);
            GLES31.glAttachShader(drawProgram, fragmentShader);
            String[] feedbackVaryings = {"outPosition", "outVelocity", "outTime", "outDuration"};
            GLES31.glTransformFeedbackVaryings(drawProgram, feedbackVaryings, GLES31.GL_INTERLEAVED_ATTRIBS);

            GLES31.glLinkProgram(drawProgram);
            GLES31.glGetProgramiv(drawProgram, GLES31.GL_LINK_STATUS, status, 0);
            if (status[0] == 0) {
                FileLog.e("SpoilerEffect2, link draw program error: " + GLES31.glGetProgramInfoLog(drawProgram));
                running = false;
                return;
            }

            resetHandle = GLES31.glGetUniformLocation(drawProgram, "reset");
            timeHandle = GLES31.glGetUniformLocation(drawProgram, "time");
            deltaTimeHandle = GLES31.glGetUniformLocation(drawProgram, "deltaTime");
            sizeHandle = GLES31.glGetUniformLocation(drawProgram, "size");
            radiusHandle = GLES31.glGetUniformLocation(drawProgram, "r");
            seedHandle = GLES31.glGetUniformLocation(drawProgram, "seed");

            GLES31.glViewport(0, 0, width, height);
            GLES31.glEnable(GLES31.GL_BLEND);
            GLES31.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
            GLES31.glClearColor(0.0f, 0.0f, 0.0f, 0.0f);

            GLES31.glUseProgram(drawProgram);
            GLES31.glUniform2f(sizeHandle, width, height);
            GLES31.glUniform1f(resetHandle, reset ? 1 : 0);
            GLES31.glUniform1f(radiusHandle, radius);
            GLES31.glUniform1f(seedHandle, Utilities.fastRandom.nextInt(256) / 256f);
        }

        private float t;
        private final float timeScale = .65f;

        private void drawFrame(float dt) {
            if (!egl.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
                running = false;
                return;
            }

            t += dt * timeScale;
            if (t > 1000.f) {
                t = 0;
            }

            GLES31.glClear(GLES31.GL_COLOR_BUFFER_BIT);
            GLES31.glBindBuffer(GLES31.GL_ARRAY_BUFFER, particlesData[currentBuffer]);
            GLES31.glVertexAttribPointer(0, 2, GLES31.GL_FLOAT, false, 24, 0); // Position (vec2)
            GLES31.glEnableVertexAttribArray(0);
            GLES31.glVertexAttribPointer(1, 2, GLES31.GL_FLOAT, false, 24, 8); // Velocity (vec2)
            GLES31.glEnableVertexAttribArray(1);
            GLES31.glVertexAttribPointer(2, 1, GLES31.GL_FLOAT, false, 24, 16); // Time (float)
            GLES31.glEnableVertexAttribArray(2);
            GLES31.glVertexAttribPointer(3, 1, GLES31.GL_FLOAT, false, 24, 20); // Duration (float)
            GLES31.glEnableVertexAttribArray(3);
            GLES31.glBindBufferBase(GLES31.GL_TRANSFORM_FEEDBACK_BUFFER, 0, particlesData[1 - currentBuffer]);
            GLES31.glVertexAttribPointer(0, 2, GLES31.GL_FLOAT, false, 24, 0); // Position (vec2)
            GLES31.glEnableVertexAttribArray(0);
            GLES31.glVertexAttribPointer(1, 2, GLES31.GL_FLOAT, false, 24, 8); // Velocity (vec2)
            GLES31.glEnableVertexAttribArray(1);
            GLES31.glVertexAttribPointer(2, 1, GLES31.GL_FLOAT, false, 24, 16); // Time (float)
            GLES31.glEnableVertexAttribArray(2);
            GLES31.glVertexAttribPointer(3, 1, GLES31.GL_FLOAT, false, 24, 20); // Duration (float)
            GLES31.glEnableVertexAttribArray(3);
            GLES31.glUniform1f(timeHandle, t);
            GLES31.glUniform1f(deltaTimeHandle, dt * timeScale);
            GLES31.glBeginTransformFeedback(GLES31.GL_POINTS);
            GLES31.glDrawArrays(GLES31.GL_POINTS, 0, particlesCount);
            GLES31.glEndTransformFeedback();

            if (reset) {
                reset = false;
                GLES31.glUniform1f(resetHandle, 0f);
            }
            currentBuffer = 1 - currentBuffer;

            egl.eglSwapBuffers(eglDisplay, eglSurface);

            checkGlErrors();
        }

        private void die() {
            if (particlesData != null) {
                try { GLES31.glDeleteBuffers(2, particlesData, 0); } catch (Exception e) { FileLog.e(e); }
                particlesData = null;
            }
            if (drawProgram != 0) {
                try { GLES31.glDeleteProgram(drawProgram); } catch (Exception e) { FileLog.e(e); }
                drawProgram = 0;
            }
            if (egl != null) {
                try { egl.eglMakeCurrent(eglDisplay, EGL10.EGL_NO_SURFACE, EGL10.EGL_NO_SURFACE, EGL10.EGL_NO_CONTEXT); } catch (Exception e) { FileLog.e(e); }
                try { egl.eglDestroySurface(eglDisplay, eglSurface); } catch (Exception e) { FileLog.e(e); }
                try { egl.eglDestroyContext(eglDisplay, eglContext); } catch (Exception e) { FileLog.e(e); }
            }
            try { surfaceTexture.release(); } catch (Exception e) { FileLog.e(e); }

            checkGlErrors();
        }

        private void checkResize() {
            synchronized (resizeLock) {
                if (resize) {
                    GLES31.glUniform2f(sizeHandle, width, height);
                    GLES31.glViewport(0, 0, width, height);
                    int newParticlesCount = particlesCount();
                    if (newParticlesCount > this.particlesCount) {
                        reset = true;
                        genParticlesData();
                    }
                    this.particlesCount = newParticlesCount;
                    resize = false;
                }
            }
        }

        private void genParticlesData() {
            if (particlesData != null) {
                GLES31.glDeleteBuffers(2, particlesData, 0);
            }

            particlesData = new int[2];
            GLES31.glGenBuffers(2, particlesData, 0);

            for (int i = 0; i < 2; ++i) {
                GLES31.glBindBuffer(GLES31.GL_ARRAY_BUFFER, particlesData[i]);
                GLES31.glBufferData(GLES31.GL_ARRAY_BUFFER, this.particlesCount * 6 * 4, null, GLES31.GL_DYNAMIC_DRAW);
            }

            checkGlErrors();
        }

        private void checkGlErrors() {
            int err;
            while ((err = GLES31.glGetError()) != GLES31.GL_NO_ERROR) {
                FileLog.e("spoiler gles error " + err);
            }
        }
    }
}
