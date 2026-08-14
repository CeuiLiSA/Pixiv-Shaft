package ceui.pixiv.ui.dynamic

import androidx.lifecycle.ViewModel
import ceui.lisa.utils.Params

/**
 * 「动态」页里某一条列表当前（或即将）加载所用的筛选范围（全部 / 公开 / 私人）。
 * 插画列表和小说列表**各持一份**（各自 Fragment 的 store），与
 * [DynamicPageViewModel.restrict]（「用户选了什么」）故意分开——切模式时才判断得出
 * 后台那条列表要不要重拉，不为看不见的列表白发请求。
 *
 * 归 VM 的两个理由：
 * 1. 数据源（FeedSource）归 FeedViewModel 长期持有、比 Fragment 实例活得久，按零捕获约定
 *    不能读 Fragment 字段；而本 VM 与 FeedViewModel 同一个 store、同生共死，捕获它既不漏、
 *    也不会读到上一代的值；
 * 2. 它必须与列表数据一起跨视图重建存活——否则旋转后数据还是「私人」而这里复位成「全部」，
 *    下次 setRestrict 会误判成「没变」而不重拉，筛选条和内容当场对不上。
 *
 * 写在主线程（筛选条回调）。读发生在数据源里：目前 `load` 跑在 viewModelScope
 * （Main.immediate），但那是 FeedViewModel 的内部实现细节、不是本类能依赖的契约，
 * 所以标 @Volatile，让「换个调度器也不会读到陈旧值」这件事不依赖别处的实现。
 */
class RestrictViewModel : ViewModel() {

    @Volatile
    var restrict: String = Params.TYPE_ALL
}
