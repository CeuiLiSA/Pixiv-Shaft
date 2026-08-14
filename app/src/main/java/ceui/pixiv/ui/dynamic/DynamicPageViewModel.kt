package ceui.pixiv.ui.dynamic

import androidx.lifecycle.ViewModel
import ceui.lisa.utils.Params

/**
 * 「动态」页（[ceui.lisa.fragments.FragmentRight]）的页面级状态：用户当前选的筛选范围，
 * 以及看的是插画还是小说。
 *
 * 为什么不留在 Fragment 字段上（legacy 就是那样）：两个列表的数据现在住在各自的
 * FeedViewModel 里、跨视图重建存活，而 Fragment 字段不会。旋转后 shell 复位成
 * 「全部 / 插画」，列表却还是上次的「私人 / 小说」——筛选条、类型菜单和内容当场对不上。
 * legacy 是靠「旋转后整页重拉一遍」把这个不一致洗掉的，feeds 不重拉，就得让状态跟着数据一起活。
 *
 * [restrict] 是「用户选了什么」；「某条列表实际按什么拉的」由两条列表各自的
 * [RestrictViewModel] 记——两者故意分开，切模式时才好判断后台那条列表要不要重拉，
 * 不为看不见的列表白发请求。
 *
 * 只在主线程读写（筛选条 / 类型条回调、shell 的视图重建）。
 */
class DynamicPageViewModel : ViewModel() {

    /** 取 [Params] 的 TYPE_ALL / TYPE_PUBLIC / TYPE_PRIVATE。 */
    var restrict: String = Params.TYPE_ALL

    /** true = 插画/漫画（默认），false = 小说。fixes #844 */
    var isIllustMode: Boolean = true
}
