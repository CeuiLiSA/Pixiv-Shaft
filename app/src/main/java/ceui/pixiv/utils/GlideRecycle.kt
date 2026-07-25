package ceui.pixiv.utils

import android.view.View
import com.bumptech.glide.Glide

/**
 * 回收路径（`onViewRecycled` / renderer 的 `recycle`）专用的清图。
 *
 * **别在回收里写 `Glide.with(view).clear(view)`**：那条重载会顺着 view 往上找宿主 Activity，
 * 并在 `RequestManagerRetriever.assertNotDestroyed` 对已 destroy 的 Activity 抛
 * `IllegalArgumentException: You cannot start a load for a destroyed activity`。
 * 而回收**确实**会发生在 Activity 已 destroy 之后：destroy 时 RecyclerView 走
 * `onDetachedFromWindow` → `ItemAnimator.endAnimations()`，正在跑动画的 holder 当场被
 * `removeAnimatingView` 回收；卸 adapter（`setAdapter`/`removeAndRecycleViews`）同理。
 * 于是 `onViewRecycled` 恰好在断言已经会抛的时刻被调用，异常从 `performDestroyActivity`
 * 里冒出来变成 `Unable to destroy activity`（线上 Crashlytics）。
 *
 * 改用 application 作用域的 RequestManager：`Glide.with(Application)` 直接返回
 * applicationManager，不做任何 assert，永远不会因为宿主死了而抛。
 *
 * 语义完全等价——`clear(View)` 只是按 view 上的 glide tag 取出已挂的 Request 再 clear，
 * 请求是谁发起的由 `Glide.removeFromManagers` 兜底回原 manager 处理，与用哪个 manager
 * 调 clear 无关。顺带还比 `Glide.with(view)` 快：省掉「递归遍历宿主 fragment 树」那趟查找。
 *
 * 加载路径（bind）不适用本函数：那里必须绑宿主生命周期，页面走了请求要跟着停。
 */
fun View.clearGlideOnRecycle() {
    Glide.with(context.applicationContext).clear(this)
}
