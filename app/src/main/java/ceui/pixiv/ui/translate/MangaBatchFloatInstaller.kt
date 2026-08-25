package ceui.pixiv.ui.translate

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.Observer
import ceui.lisa.R
import ceui.lisa.activities.ImageDetailActivity
import ceui.loxia.appServices
import ceui.pixiv.banner.BannerHostOwner
import timber.log.Timber
import java.util.WeakHashMap

/**
 * 把「翻译整部」悬浮小窗挂到每个 app Activity 上(issue #925)。
 *
 * 做法与 [ceui.pixiv.banner.BannerHostInstaller] 同一路数:监听 Application 的 Activity 生命周期,
 * 每个 [BannerHostOwner](= BaseActivity 体系)Activity 拿它的 lifecycle 观察
 * [MangaBatchTranslateCenter.status];**第一次**看到非 null 状态才 inflate 卡片并 addView 到
 * `android.R.id.content` —— 没有整批任务在跑的时候,所有页面零成本。
 *
 * 卡片位置(用户拖到哪)记在 [sharedTx]/[sharedTy],切 Activity 时新卡片落在同一位置。
 * 点卡片本体跳回正在翻译那部作品的看图页(已经在那部的看图页上就不动)。
 */
class MangaBatchFloatInstaller : Application.ActivityLifecycleCallbacks {

    private class Slot(val activity: Activity, val owner: LifecycleOwner) {
        private val center: MangaBatchTranslateCenter = activity.appServices().mangaBatchTranslateCenter
        var card: MangaBatchTranslateFloatCard? = null
        var root: View? = null
        val observer = Observer<MangaBatchTranslateCenter.BatchStatus?> { status ->
            if (status == null && card == null) return@Observer   // 从没显示过,不用为了隐藏去 inflate
            ensureCard()?.render(status)
        }

        fun attach() {
            center.status.observe(owner, observer)
        }

        fun detach() {
            center.status.removeObserver(observer)
            root?.let { (it.parent as? ViewGroup)?.removeView(it) }
            root = null
            card = null
        }

        private fun ensureCard(): MangaBatchTranslateFloatCard? {
            card?.let { return it }
            if (activity.isFinishing) return null
            val content = try {
                activity.findViewById<ViewGroup>(android.R.id.content)
            } catch (t: Throwable) {
                Timber.tag(TAG).w(t, "findViewById(content) failed on %s", activity.javaClass.simpleName)
                null
            } ?: return null
            val density = activity.resources.displayMetrics.density
            val view = LayoutInflater.from(activity).inflate(R.layout.view_manga_batch_translate_float, content, false)
            view.layoutParams = FrameLayout.LayoutParams(
                (CARD_WIDTH_DP * density).toInt(),
                FrameLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                marginStart = (12 * density).toInt()
                topMargin = (8 * density).toInt()
            }
            // 顶部让出状态栏:edge-to-edge 页面 content 顶到屏幕顶,拿 statusBars inset 补;
            // 非 edge-to-edge 页面 inset 已被 decor 吃掉,这里拿到 0,正好不重复让
            ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
                val top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
                (v.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
                    val want = top + (8 * density).toInt()
                    if (lp.topMargin != want) {
                        lp.topMargin = want
                        v.layoutParams = lp
                    }
                }
                insets
            }
            view.translationX = sharedTx
            view.translationY = sharedTy
            content.addView(view)
            root = view
            val built = MangaBatchTranslateFloatCard(
                view,
                onCancel = { center.cancel() },
                onTap = { openViewer(activity, center) },
                onMoved = { tx, ty ->
                    sharedTx = tx
                    sharedTy = ty
                },
            )
            card = built
            return built
        }
    }

    private val slots = WeakHashMap<Activity, Slot>()

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
        if (activity !is BannerHostOwner || activity !is LifecycleOwner) return
        if (slots.containsKey(activity)) return
        val slot = Slot(activity, activity)
        slots[activity] = slot
        // LiveData 观察是懒的:回调在 STARTED 之后才来,此时 setContentView 早已完成,
        // 不存在 BannerHostInstaller 注释里那个「onCreate 中途碰 content」的坑
        slot.attach()
    }

    override fun onActivityDestroyed(activity: Activity) {
        slots.remove(activity)?.detach()
    }

    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

    companion object {
        private const val TAG = "MangaBatchFloat"
        private const val CARD_WIDTH_DP = 268

        /** 用户把卡片拖到的位置,跨 Activity 共享。 */
        private var sharedTx = 0f
        private var sharedTy = 0f

        /** 点卡片:跳到正在翻的那部作品的看图页;已经在它的看图页上就什么都不做。 */
        private fun openViewer(from: Activity, center: MangaBatchTranslateCenter) {
            val illust = center.currentIllust ?: return
            if (from is ImageDetailActivity && from.mIllust?.id == illust.id) return
            val page = center.status.value?.pageDone ?: 0
            from.startActivity(
                Intent(from, ImageDetailActivity::class.java).apply {
                    putExtra("illust", illust)
                    putExtra("dataType", "二级详情")
                    putExtra("index", page.coerceIn(0, (illust.page_count - 1).coerceAtLeast(0)))
                },
            )
        }
    }
}
