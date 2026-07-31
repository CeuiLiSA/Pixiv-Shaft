package ceui.pixiv.utils

import android.view.View
import com.bumptech.glide.Glide
import com.bumptech.glide.RequestManager

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

/**
 * 在 `onViewCreated` 里调一次，把 `by lazy { Glide.with(this) }` 这类缓存 RequestManager 的
 * **首次取值**钉死在 attach 期内。传进来的实参在求值时就被兑现，函数体本身什么都不做。
 *
 * 为什么需要：这些 manager 同时被 bind 加载与 renderer 的 `recycle` 清图使用，而回收可能晚到
 * ——destroy 期 `ItemAnimator.endAnimations()` 会当场回收正在跑动画的 holder，卸 adapter 同理。
 * 若首次取值恰好落在那一刻，`Glide.with(Fragment)` 的 `requireNonNull(fragment.context)` 直接抛
 * `NullPointerException: You cannot start a load on a fragment before it is attached or after it
 * is destroyed`。今天不炸只是因为「同一个 holder 必然先 bind 再 recycle」，那是时序巧合而非不变量
 * ——同一种病已经在 [ceui.lisa.fragments.FragmentImageDetail] 的手势回调上真实崩过（见其 `onAttach`）。
 * 兑现之后 lazy 只返回缓存实例，再也不碰宿主状态。
 *
 * 与 [clearGlideOnRecycle] 互补：没有缓存 manager 可用的回收路径走后者。
 */
fun pinHostGlide(@Suppress("UNUSED_PARAMETER") vararg managers: RequestManager) = Unit
