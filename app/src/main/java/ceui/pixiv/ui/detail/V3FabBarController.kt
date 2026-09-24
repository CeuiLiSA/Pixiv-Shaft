package ceui.pixiv.ui.detail

import android.content.res.ColorStateList
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.annotation.ColorInt
import androidx.annotation.DrawableRes
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.lisa.databinding.ViewV3FabBarBinding
import ceui.lisa.utils.Settings
import ceui.pixiv.witstudio.theme.V3Palette
import ceui.pixiv.utils.ppppx

/**
 * 悬浮「下载 + 收藏」胶囊([ceui.lisa.R.layout.view_v3_fab_bar])的共享逻辑,
 * 一级 V3 详情页([ArtworkV3Fragment])与二级大图页(ImageDetailActivity)共用:
 * 下载态着色 / 收藏心着色 / 位置与顺序偏好 / 距底部 = 导航栏 inset + 24dp。
 *
 * 点击行为两页各不相同(整作品下载 vs 保存当前页),由调用方自己挂在
 * [binding] 的 fabDownloadContainer / fabBookmark 上。
 */
class V3FabBarController(val binding: ViewV3FabBarBinding) {

    private val context get() = binding.root.context

    /** 胶囊前景(图标/分隔线/进度环)当前内容色。XML 默认深色胶囊白内容,[applyPalette] 后换 palette 内容色。 */
    @ColorInt
    private var contentColor: Int = context.getColor(R.color.white)

    /** 按 [V3Palette] 重刷胶囊背景与内容色,两页都必须调(浅色主题下 XML 默认深色值不可用)。 */
    fun applyPalette(palette: V3Palette) {
        val density = binding.root.resources.displayMetrics.density
        binding.root.background = palette.floatingPillBg(999f * density)
        contentColor = palette.floatingPillContent
        binding.fabDownload.imageTintList = ColorStateList.valueOf(contentColor)
        binding.fabDivider.setBackgroundColor(V3Palette.withAlpha(contentColor, 0.20f))
        binding.fabDownloadProgress.setIndicatorColor(contentColor)
        binding.fabDownloadProgress.trackColor = V3Palette.withAlpha(contentColor, 0.20f)
        binding.fabComment.imageTintList = ColorStateList.valueOf(contentColor)
        binding.fabCommentDivider.setBackgroundColor(V3Palette.withAlpha(contentColor, 0.20f))
    }

    /** 跳转评论区段(#970):仅一级 V3 详情页放出(点击行为由调用方挂在 fabComment 上)。 */
    fun setCommentJumpVisible(visible: Boolean) {
        binding.fabCommentDivider.visibility = if (visible) View.VISIBLE else View.GONE
        binding.fabComment.visibility = if (visible) View.VISIBLE else View.GONE
    }

    fun renderDownload(state: DownloadFab) {
        when (state) {
            DownloadFab.Idle ->
                paintDownload(R.drawable.ic_file_download_black_24dp, contentColor)

            DownloadFab.Done ->
                paintDownload(
                    R.drawable.ic_file_download_done_24dp,
                    context.getColor(R.color.has_downloaded),
                )

            is DownloadFab.Downloading -> {
                binding.fabDownload.visibility = View.INVISIBLE
                binding.fabDownloadProgress.visibility = View.VISIBLE
                binding.fabDownloadProgress.setProgressCompat(state.percent, true)
            }
        }
    }

    private fun paintDownload(@DrawableRes iconRes: Int, @ColorInt tint: Int) {
        binding.fabDownloadProgress.visibility = View.GONE
        binding.fabDownload.visibility = View.VISIBLE
        binding.fabDownload.setImageResource(iconRes)
        binding.fabDownload.imageTintList = ColorStateList.valueOf(tint)
    }

    /**
     * 收藏心着色。「未收藏」必须用 [contentColor] 而不是写死白色:取消收藏当帧会闪一帧白,
     * 随后才被权威观察者纠回内容色——浅色主题下那一帧几乎看不见图标。
     */
    fun setBookmarked(bookmarked: Boolean) {
        binding.fabBookmark.imageTintList = ColorStateList.valueOf(
            if (bookmarked) context.getColor(R.color.has_bookmarked) else contentColor,
        )
    }

    /**
     * 胶囊水平位置(#1090)+ 段内顺序偏好。
     *
     * 居中时顺序按「下载/收藏顺序」设置;靠左 / 靠右时收藏心固定在贴屏幕边的外侧(单手拇指
     * 最容易够到),其余段镜像排开,评论段(#970)落在靠屏幕内侧的一端。
     *
     * @param sideMargin 靠边时胶囊到父容器边的距离(另叠加侧边系统栏 inset,见 [applySideMargins])。
     *                   胶囊的父容器须是 FrameLayout。
     */
    fun applyLayoutPreference(sideMargin: Int) {
        val position = Shaft.sSettings.artworkV3FabPosition
        this.position = position
        this.sideMargin = sideMargin
        val bar = binding.root
        val lp = bar.layoutParams as FrameLayout.LayoutParams
        lp.gravity = (lp.gravity and Gravity.VERTICAL_GRAVITY_MASK) or when (position) {
            Settings.ARTWORK_V3_FAB_POSITION_LEFT -> Gravity.START
            Settings.ARTWORK_V3_FAB_POSITION_RIGHT -> Gravity.END
            else -> Gravity.CENTER_HORIZONTAL
        }
        bar.layoutParams = lp
        applySideMargins()

        val download = binding.fabDownloadContainer
        val divider = binding.fabDivider
        val bookmark = binding.fabBookmark
        val commentDivider = binding.fabCommentDivider
        val comment = binding.fabComment
        val ordered = when {
            position == Settings.ARTWORK_V3_FAB_POSITION_RIGHT ->
                listOf(comment, commentDivider, download, divider, bookmark)
            position == Settings.ARTWORK_V3_FAB_POSITION_LEFT ||
                !Shaft.sSettings.isArtworkV3FabDownloadOnLeft ->
                listOf(bookmark, divider, download, commentDivider, comment)
            else -> listOf(download, divider, bookmark, commentDivider, comment)
        }
        bar.removeAllViews()
        ordered.forEach(bar::addView)
    }

    private var position = Settings.ARTWORK_V3_FAB_POSITION_CENTER
    private var sideMargin = 0
    private var sideInsets = Insets.NONE

    /**
     * 靠边时的左右边距 = [sideMargin] + 那一侧的系统栏 / 挖孔 inset:两页都是 edge-to-edge,
     * 横屏三键导航的导航栏在侧边,不让开会压住外侧的收藏心。居中时两侧边距归零。
     */
    private fun applySideMargins() {
        val bar = binding.root
        val lp = bar.layoutParams as ViewGroup.MarginLayoutParams
        val rtl = bar.layoutDirection == View.LAYOUT_DIRECTION_RTL
        val start = if (position == Settings.ARTWORK_V3_FAB_POSITION_LEFT) {
            sideMargin + if (rtl) sideInsets.right else sideInsets.left
        } else 0
        val end = if (position == Settings.ARTWORK_V3_FAB_POSITION_RIGHT) {
            sideMargin + if (rtl) sideInsets.left else sideInsets.right
        } else 0
        if (lp.marginStart != start || lp.marginEnd != end) {
            lp.marginStart = start
            lp.marginEnd = end
            bar.layoutParams = lp
        }
    }

    /**
     * 距底部 = 导航栏 inset + 24dp。一级 V3 与二级大图页都必须走这里,
     * 保证两页胶囊到屏幕底部的距离一致。同一份 inset 顺带喂给靠边时的左右边距。
     *
     * @param target 吃这份底距的 view,默认胶囊本身;二级大图页传胶囊 + 页码所在的整行,
     *               让页码跟着胶囊一起动。target 的父容器须能可靠响应 bottomMargin
     *               (FrameLayout 可以;RelativeLayout 的 alignParentBottom + wrap_content
     *               组合会吃掉 bottomMargin,别用)。
     */
    fun attachBottomInsetMargin(target: View = binding.root) {
        ViewCompat.setOnApplyWindowInsetsListener(target) { v, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.navigationBars())
            val lp = v.layoutParams as ViewGroup.MarginLayoutParams
            val bottom = insets.bottom + 24.ppppx
            if (lp.bottomMargin != bottom) {
                lp.bottomMargin = bottom
                v.layoutParams = lp
            }
            sideInsets = windowInsets.getInsets(
                WindowInsetsCompat.Type.navigationBars() or WindowInsetsCompat.Type.displayCutout()
            )
            applySideMargins()
            windowInsets
        }
    }
}
