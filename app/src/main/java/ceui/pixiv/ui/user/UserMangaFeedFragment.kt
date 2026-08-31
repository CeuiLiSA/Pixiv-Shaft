package ceui.pixiv.ui.user

import android.os.Bundle
import androidx.fragment.app.Fragment
import ceui.lisa.R
import ceui.lisa.helper.UserIllustJumpHelper
import ceui.lisa.utils.Params
import ceui.pixiv.ui.navigation.TemplateRoute

/**
 * 「某人创作的漫画」列表页（feeds 框架版，替代 legacy FragmentUserManga + UserMangaRepo）。
 *
 * pixiv 把漫画当插画的一个 type（同一个 `/v1/user/illusts`，只是 `type=manga`），所以整页逻辑
 * ——两种形态(内嵌 tab / 独立 toolbar)、initialOffset 首屏偏移、targetDate 定位高亮、
 * 收藏到精华、跳转——全部复用 [UserIllustFeedFragment]，这里只换类型相关的几处。
 *
 * 「下载全部」对齐 legacy 不提供：legacy FragmentUserManga 的 toolbar 只有收藏到精华 + 跳转，
 * 且基类那套数量口径读的是 `total_illusts`（漫画得读 `total_manga`）。
 */
class UserMangaFeedFragment : UserIllustFeedFragment() {

    override val workType: String get() = Params.TYPE_MANGA

    override val titleRes: Int get() = R.string.string_233 // 漫画作品

    override val featureDataType: String get() = DATA_TYPE_FEATURE

    override val jumpKind: UserIllustJumpHelper.Kind get() = UserIllustJumpHelper.Kind.MANGA

    override val supportsDownloadAll: Boolean get() = false

    override fun newInstanceForJump(offset: Int, pickedDate: String?): Fragment {
        return newInstance(userId, showToolbar, offset, pickedDate)
    }

    companion object {
        /** legacy 精华功能的 dataType 路由字面量（按它分支重建页面），不是展示文案，别本地化。 */
        private val DATA_TYPE_FEATURE = TemplateRoute.USER_MANGA.key

        @JvmStatic
        @JvmOverloads
        fun newInstance(
            userID: Long,
            showToolbar: Boolean,
            initialOffset: Int = 0,
            targetDate: String? = null,
        ): UserMangaFeedFragment {
            return UserMangaFeedFragment().apply {
                arguments = Bundle().apply {
                    putLong(Params.USER_ID, userID)
                    putBoolean(Params.FLAG, showToolbar)
                    putInt(Params.INITIAL_OFFSET, initialOffset)
                    if (targetDate != null) putString(Params.TARGET_DATE, targetDate)
                }
            }
        }
    }
}
