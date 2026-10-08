package ceui.pixiv.ui.common

import androidx.annotation.StringRes
import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.lisa.utils.Local

/**
 * 插画列表布局（#1214，对齐 pixiv-viewer 的「图片列表布局选择」）。
 *
 * pixiv-viewer 那七项里 Masonry / Masonry2 / Masonry(CSSGrid)、Justified / Justified(Transform)
 * 只是网页端的不同实现，看上去一样；这里只保留四种视觉上真正不同的排布。
 *
 * 序号即 [ceui.lisa.utils.Settings.illustListLayout] 的存储值，只能往后追加、不能重排。
 */
enum class IllustListLayout(
    @StringRes val titleRes: Int,
    @StringRes val descRes: Int,
) {
    /** 等宽不等高（原有瀑布流，默认）。 */
    MASONRY(R.string.illust_list_layout_masonry, R.string.illust_list_layout_masonry_desc),

    /** 等宽等高：每张卡 1:1 居中裁切。 */
    GRID(R.string.illust_list_layout_grid, R.string.illust_list_layout_grid_desc),

    /** 等高不等宽：按宽高比把作品排成齐边的行，见 [JustifiedLayoutManager]。 */
    JUSTIFIED(R.string.illust_list_layout_justified, R.string.illust_list_layout_justified_desc),

    /** 单列全宽：一行一张，取大图缩略图。 */
    SINGLE_COLUMN(R.string.illust_list_layout_single, R.string.illust_list_layout_single_desc);

    companion object {
        fun current(): IllustListLayout =
            values().getOrElse(Shaft.sSettings.illustListLayout) { MASONRY }

        /** 设置页与卡片长按菜单共用的写入口。各列表在 onResume 时发现变化并重装。 */
        @JvmStatic
        fun save(layout: IllustListLayout) {
            Shaft.sSettings.illustListLayout = layout.ordinal
            Local.setSettings(Shaft.sSettings)
        }
    }
}
