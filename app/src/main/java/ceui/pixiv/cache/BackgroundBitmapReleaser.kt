package ceui.pixiv.cache

import android.app.Activity
import android.app.Application
import android.content.ComponentCallbacks2
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import com.bumptech.glide.request.Request
import com.github.panpf.sketch.SingletonSketch
import com.github.panpf.sketch.Sketch
import timber.log.Timber
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.WeakHashMap

/**
 * 进程退到后台后，把用户看不见的页面上由 Glide 挂着的位图还回去，回到该页时原样补回。
 *
 * Play Vitals「缓存状态下位图内存」的不良阈值是 400 MB。Glide / Sketch 自己的 onTrimMemory 只清
 * **缓存**，正挂在 ImageView 上的 active resource 一张不动：返回栈里每个作品详情页都留着按控件宽解码的
 * 大图（横屏 / 平板 2400px 宽，一页 ≈45 MB，底层 large + 顶层原图各一份），叠几层页面后进程进 cached
 * 时实测仍有 450 MB 位图。
 *
 * - [ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN]（整个 app 不可见）：只释放切走那一刻用户就看不见的页面
 *   —— 切出去马上回来，眼前的画面不重载。半透明的大图页（ImageViewerTheme）下面那层详情页也算看得见；
 * - [ComponentCallbacks2.TRIM_MEMORY_BACKGROUND]（进程进 cached LRU）：全部释放，并清空 Sketch 内存缓存
 *   （Sketch 自己在这一级只清一半，整清要等 API 34 起已不再下发的 MODERATE）。
 *
 * 释放与 Glide 自己的 `RequestTracker.pauseAllRequests` 同一个动作：对已完成的请求调 [Request.clear]，
 * target 收到 onLoadCleared 换回占位、资源引用计数还给 Glide，请求本身仍挂在 View 上。只碰 View 上的
 * 请求，不调 `RequestManager.pauseAllRequests` —— 那会把取文件、生成海报这类一次性 target 也清掉，恢复时
 * 再回调一遍 onResourceReady。Activity 重新 STARTED 时，View 上仍是同一个、仍处于 cleared 的请求才
 * [Request.begin]；其间业务换了新请求或主动 clear（tag 已变）就不碰。
 *
 * 对业务的两条要求：
 * - Glide 的既有契约：交付给 target 的 Bitmap 不得借给别处继续绘制，否则 Glide 回收它之后那边会画到
 *   已回收的位图（见 IllustAdapter#tryKeepPinnedPage 的注释）；
 * - 补回时请求上的 RequestListener 会再收到一次 onResourceReady，监听器里的副作用要幂等
 *   （SlideshowFragment#loadIntoImageView 为此只放行第一次）。
 *
 * 只在主线程使用：生命周期回调与 onTrimMemory 都在主线程分发。
 */
class BackgroundBitmapReleaser : Application.ActivityLifecycleCallbacks {

    private val alive: MutableSet<Activity> = Collections.newSetFromMap(WeakHashMap<Activity, Boolean>())
    private val started: MutableSet<Activity> = Collections.newSetFromMap(WeakHashMap<Activity, Boolean>())
    private val released = WeakHashMap<Activity, MutableList<WeakReference<View>>>()

    // 最近一次 onPause 那一刻仍 STARTED 的页面 = app 被切走时用户眼前的画面。只认「最后 resume 的那个」
    // 会漏掉半透明页下面那层：它一直 STARTED、拖拽退出大图时就露出来。
    private val visibleAtLastPause: MutableSet<Activity> = Collections.newSetFromMap(WeakHashMap<Activity, Boolean>())

    // SingletonSketch 没有「取已建实例」的 API，直接 get 会为了清缓存反倒把整套 Sketch（含网络监听）建出来；
    // 所以由下面的工厂记一笔，没人用过 Sketch 就不碰它。工厂在加载线程上执行，故 @Volatile。
    @Volatile
    private var sketch: Sketch? = null

    /** 必须在第一个 Activity 创建、第一次使用 Sketch 之前调用。 */
    fun install(app: Application) {
        app.registerActivityLifecycleCallbacks(this)
        // 与 SingletonSketch 的默认工厂相同（Sketch(context)），只多记一笔引用。
        SingletonSketch.setSafe { context -> Sketch(context).also { sketch = it } }
    }

    /**
     * 由 Application.onTrimMemory 在 `super.onTrimMemory` **之前**调用：先把资源还给 Glide，随后 Glide 自己
     * 的 trim（UI_HIDDEN 减半、BACKGROUND 清空内存缓存与 BitmapPool）才清得到它们。
     */
    fun onTrimMemory(level: Int) {
        if (level < ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) return
        val keepVisible = level < ComponentCallbacks2.TRIM_MEMORY_BACKGROUND
        var count = 0
        for (activity in alive.toList()) {
            if (keepVisible && activity in visibleAtLastPause) continue
            count += release(activity)
        }
        if (!keepVisible) sketch?.memoryCache?.clear()
        Timber.tag(TAG).d("trim level=%d released %d image requests, keepVisible=%b", level, count, keepVisible)
    }

    private fun release(activity: Activity): Int {
        if (activity in started || activity.isDestroyed) return 0
        val root = activity.window?.peekDecorView() ?: return 0
        var count = 0
        val stack = ArrayDeque<View>()
        stack.addLast(root)
        while (stack.isNotEmpty()) {
            val view = stack.removeLast()
            val request = view.getTag(GLIDE_TARGET_TAG) as? Request
            if (request != null && request.isComplete) {
                request.clear()
                released.getOrPut(activity) { ArrayList() }.add(WeakReference(view))
                count++
            }
            if (view is ViewGroup) {
                for (i in 0 until view.childCount) stack.addLast(view.getChildAt(i))
            }
        }
        return count
    }

    override fun onActivityStarted(activity: Activity) {
        started += activity
        // 先于 Fragment / Activity 的 Lifecycle ON_START（Glide RequestManager 在那时 resumeRequests）。
        // 这里补回的请求已在跑，Glide 会跳过；全局 RequestManager 发出的请求没有 ON_START，只能靠这里补回。
        val views = released.remove(activity) ?: return
        for (ref in views) {
            val request = ref.get()?.getTag(GLIDE_TARGET_TAG) as? Request ?: continue
            if (request.isCleared) request.begin()
        }
    }

    override fun onActivityStopped(activity: Activity) {
        started -= activity
    }

    override fun onActivityPaused(activity: Activity) {
        visibleAtLastPause.clear()
        visibleAtLastPause.addAll(started)
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
        alive += activity
    }

    override fun onActivityDestroyed(activity: Activity) {
        alive -= activity
        started -= activity
        released.remove(activity)
    }

    override fun onActivityResumed(activity: Activity) = Unit

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

    private companion object {
        const val TAG = "BitmapReleaser"

        // ViewTarget 与 CustomViewTarget 默认都把请求存在这个 tag 上（本项目没调过 ViewTarget.setTagId）。
        val GLIDE_TARGET_TAG = com.bumptech.glide.R.id.glide_custom_view_target_tag
    }
}
