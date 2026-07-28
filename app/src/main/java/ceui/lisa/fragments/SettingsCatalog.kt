package ceui.lisa.fragments

import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.graphics.ColorUtils
import androidx.core.widget.NestedScrollView
import androidx.fragment.app.Fragment
import ceui.lisa.R
import ceui.lisa.activities.TemplateActivity
import ceui.lisa.utils.V3Palette

/**
 * 两级设置页的目录：分类定义 + 全量设置项索引（搜索用）。
 *
 * idName 是分类布局里那一行的 view id 名字，搜索命中后跳到分类页并滚动高亮该行；
 * 索引只收录用户可见的行（常驻隐藏的 r18/ai 分目录开关、lite 渠道隐藏的邮箱备份不进索引）。
 */
object SettingsCatalog {

    const val PAGE_CATEGORY = "设置分类"
    const val EXTRA_CATEGORY = "settings_category"
    const val EXTRA_HIGHLIGHT = "settings_highlight_id"

    class Category(
        val key: String,
        @StringRes val titleRes: Int,
        @DrawableRes val iconRes: Int,
    )

    class Entry(
        val category: Category,
        val idName: String,
        @StringRes val titleRes: Int,
        @StringRes val descRes: Int = 0,
        /** 搜索别名：同义说法/中英文/选项值，只参与匹配不展示，无需翻译。 */
        val keywords: String = "",
    )

    @JvmField val ACCOUNT = Category("account", R.string.account, R.drawable.ic_setcat_person)
    @JvmField val NETWORK = Category("network", R.string.about_network, R.drawable.ic_setcat_globe)
    @JvmField val APPEARANCE = Category("appearance", R.string.string_376, R.drawable.ic_setcat_palette)
    @JvmField val BROWSING = Category("browsing", R.string.settings_cat_browse, R.drawable.ic_setcat_search)
    @JvmField val VIEWING = Category("viewing", R.string.settings_cat_viewing, R.drawable.ic_setcat_photo)
    @JvmField val BOOKMARKS = Category("bookmarks", R.string.settings_cat_bookmark, R.drawable.ic_setcat_heart)
    @JvmField val DOWNLOAD = Category("download", R.string.string_377, R.drawable.ic_setcat_download)
    @JvmField val AI = Category("ai", R.string.settings_cat_ai, R.drawable.ic_setcat_sparkle)
    @JvmField val DATA = Category("data", R.string.settings_cat_data, R.drawable.ic_setcat_backup)
    @JvmField val EXPERIMENTAL = Category("experimental", R.string.experimental_section, R.drawable.ic_setcat_science)

    @JvmField
    val categories = listOf(
        ACCOUNT, NETWORK, APPEARANCE, BROWSING, VIEWING,
        BOOKMARKS, DOWNLOAD, AI, DATA, EXPERIMENTAL,
    )

    val entries: List<Entry> = buildList {
        // 账号
        add(Entry(ACCOUNT, "user_manage", R.string.account_manage, keywords = "多账号 切换账号 添加账号 账户 switch account"))
        add(Entry(ACCOUNT, "edit_account", R.string.string_91, keywords = "邮箱 密码 pixiv id 绑定 email password"))
        if (!ceui.lisa.BuildConfig.IS_LITE) {
            add(Entry(ACCOUNT, "account_backup", R.string.email_backup_settings_entry, keywords = "备份账号 恢复 邮箱 email backup"))
        }
        add(Entry(ACCOUNT, "edit_file", R.string.string_92, keywords = "个人资料 头像 昵称 简介 性别 生日 profile avatar"))
        add(Entry(ACCOUNT, "work_space", R.string.string_267, keywords = "作业环境 workspace 电脑 显示器 手绘板"))
        add(Entry(ACCOUNT, "r18_space", R.string.string_398, keywords = "r18 r18g 成人 限制级 涩图 网页设置 adult"))
        add(Entry(ACCOUNT, "premium_space", R.string.string_399, keywords = "会员 高级会员 订阅 premium"))
        add(Entry(ACCOUNT, "login_out", R.string.login_out, keywords = "退出 登出 注销 切换 logout sign out"))

        // 网络
        add(Entry(NETWORK, "direct_connect_rela", R.string.open_direct_connection, R.string.see_pixiv_ez, keywords = "直连 代理 免代理 翻墙 梯子 vpn sni direct proxy"))
        add(Entry(NETWORK, "use_secure_dns_rela", R.string.use_secure_dns_title, R.string.use_secure_dns_summary, keywords = "dns doh cloudflare 域名解析 加密dns"))
        add(Entry(NETWORK, "image_host_rela", R.string.image_host_title, keywords = "图片代理 加速 反代 镜像 自定义域名 pixiv.cat pixiv.re pximg image proxy mirror"))
        add(Entry(NETWORK, "show_large_thumbnail_image_rela", R.string.string_450, R.string.string_334, keywords = "缩略图 大图 画质 流量 省流 加载速度 thumbnail"))
        add(Entry(NETWORK, "show_original_preview_image_rela", R.string.string_413, R.string.string_334, keywords = "原图 高清 画质 详情大图 加载 original"))

        // 界面
        add(Entry(APPEARANCE, "theme_mode_rela", R.string.theme_mode, keywords = "夜间 暗色 深色 黑暗 日间 浅色 白天 跟随系统 dark light night mode"))
        add(Entry(APPEARANCE, "color_select_rela", R.string.string_324, keywords = "主题色 颜色 配色 强调色 粉色 accent color"))
        add(Entry(APPEARANCE, "app_language_rela", R.string.language, keywords = "语言 简体 繁体 英文 日文 韩文 中文 language english"))
        add(Entry(APPEARANCE, "line_count_rela", R.string.string_336, keywords = "列数 几列 瀑布流 网格 columns grid"))
        add(Entry(APPEARANCE, "layout_mode_rela", R.string.layout_mode, keywords = "瀑布流 线性 列表 关注动态 staggered linear"))
        add(Entry(APPEARANCE, "show_novel_card_tags_rela", R.string.show_novel_card_tags_setting, keywords = "小说标签 卡片 tag novel"))
        add(Entry(APPEARANCE, "navigation_init_position_rela", R.string.string_426, keywords = "启动页 默认页 初始页 首页 导航 start page"))
        add(Entry(APPEARANCE, "bottom_bar_order_rela", R.string.string_342, keywords = "底部导航 tab 顺序 排序 页签 bottom bar"))
        add(Entry(APPEARANCE, "main_view_r18_rela", R.string.string_359, keywords = "r18 r页 主页 首页 涩图"))

        // 浏览与搜索
        add(Entry(BROWSING, "save_history_rela", R.string.save_view_history, keywords = "浏览历史 历史记录 足迹 history"))
        add(Entry(BROWSING, "cloud_history_sync_rela", R.string.cloud_history_sync, keywords = "云同步 历史同步 多设备 cloud sync"))
        add(Entry(BROWSING, "clear_cloud_history_rela", R.string.clear_cloud_history, keywords = "清除历史 删除云端 隐私 clear history"))
        add(Entry(BROWSING, "filter_comment_rela", R.string.string_379, keywords = "评论 垃圾 广告 屏蔽 spam comment"))
        add(Entry(BROWSING, "r18_filter_default_enable_rela", R.string.string_414, R.string.string_415, keywords = "r18 过滤 屏蔽 安全模式 safe filter"))
        add(Entry(BROWSING, "delete_ai_illust_rela", R.string.delete_ai_illust, keywords = "ai 屏蔽ai 不看ai ai生成 aigc 过滤"))
        add(Entry(BROWSING, "filter_rank_bookmarked_rela", R.string.filter_rank_bookmarked, keywords = "排行榜 已收藏 过滤 去重 rank"))
        add(Entry(BROWSING, "delete_star_illust_rela", R.string.delete_star_illust, keywords = "搜索 已收藏 过滤 去重"))
        add(Entry(BROWSING, "filter_invalid_bookmarks_rela", R.string.filter_invalid_bookmarks, keywords = "失效 无效 404 收藏夹 已删除作品"))
        add(Entry(BROWSING, "search_filter_rela", R.string.search_result_filter, keywords = "收藏量 热度 users入り 万users 筛选 bookmarks filter"))
        add(Entry(BROWSING, "search_default_sort_type_rela", R.string.string_439, keywords = "排序 时间 热门 最新 最旧 sort order"))
        add(Entry(BROWSING, "synonym_dict_enable_rela", R.string.synonym_dict_enable, keywords = "同义词 词典 别名 标签翻译 synonym"))
        add(Entry(BROWSING, "synonym_dict_rela", R.string.synonym_dict_title, keywords = "同义词 词典 管理 导入 导出 合并 synonym"))

        // 看图与详情
        add(Entry(VIEWING, "illust_detail_v3_rela", R.string.illust_detail_v3, keywords = "v3 沉浸式 详情页 新版 详情"))
        add(Entry(VIEWING, "novel_direct_reader_rela", R.string.novel_direct_reader, R.string.novel_direct_reader_desc, keywords = "小说 正文 阅读器 略过详情 跳过详情 直接阅读 novel reader skip detail"))
        add(Entry(VIEWING, "artwork_v3_fab_order_rela", R.string.artwork_v3_fab_order_title, keywords = "按钮顺序 下载按钮 收藏按钮 左右 fab"))
        add(Entry(VIEWING, "transform_type_rela", R.string.string_393, keywords = "翻页 动画 切页 过渡 特效 transformer"))
        add(Entry(VIEWING, "keep_status_bar_when_view_image_rela", R.string.keep_status_bar_when_view_image, keywords = "状态栏 刘海 挖孔 全屏 沉浸 notch"))
        add(Entry(VIEWING, "illust_detail_keep_screen_on_rela", R.string.string_451, keywords = "常亮 息屏 熄屏 屏幕 keep screen on"))
        add(Entry(VIEWING, "use_custom_double_tap_zoom_rela", R.string.use_custom_double_tap_zoom, keywords = "双击 放大 缩放 zoom"))
        add(Entry(VIEWING, "custom_zoom_scale_rela", R.string.custom_zoom_scale_title, R.string.custom_zoom_scale_link_text, keywords = "缩放 增量 倍率 zoom scale"))
        add(Entry(VIEWING, "use_custom_three_level_zoom_rela", R.string.use_three_level_zoom_title, R.string.three_level_zoom_link_text, keywords = "三级 缩放 智能 zoom"))
        add(Entry(VIEWING, "use_custom_long_press_reset_rela", R.string.use_custom_long_press_reset, R.string.use_custom_long_press_reset_link_text, keywords = "长按 复位 还原 缩放 reset"))

        // 收藏与互动
        add(Entry(BOOKMARKS, "show_like_button_rela", R.string.string_335, keywords = "私密收藏 非公开 私人 private bookmark"))
        add(Entry(BOOKMARKS, "hide_star_bar_rela", R.string.string_371, keywords = "隐藏 收藏按钮 我的收藏"))
        add(Entry(BOOKMARKS, "select_all_tag_rela", R.string.string_372, keywords = "标签 全选 tag 收藏"))
        add(Entry(BOOKMARKS, "show_related_when_star_rela", R.string.string_396, keywords = "相关作品 关联 推荐 related"))
        add(Entry(BOOKMARKS, "auto_follow_after_star_rela", R.string.string_456, keywords = "自动关注 关注 follow"))
        add(Entry(BOOKMARKS, "auto_download_after_star_rela", R.string.auto_download_after_star, keywords = "自动下载 收藏下载 download"))
        add(Entry(BOOKMARKS, "download_auto_post_like_rela", R.string.string_409, keywords = "自动收藏 点赞 下载 like"))

        // 下载
        add(Entry(DOWNLOAD, "storage_choice_rela", R.string.setting_storage_choice, keywords = "存储 保存位置 目录 文件夹 pictures downloads saf sd卡 storage"))
        add(Entry(DOWNLOAD, "file_name_rela", R.string.download_path_title, R.string.download_path_entry_desc, keywords = "文件名 命名 模板 路径 目录 分目录 重命名 filename path template"))
        add(Entry(DOWNLOAD, "overwrite_policy_rela", R.string.setting_overwrite_policy, keywords = "重复 覆盖 跳过 重命名 副本 同名 duplicate overwrite"))
        add(Entry(DOWNLOAD, "page_index_rela", R.string.setting_page_index, keywords = "页码 序号 p0 p1 多图 起始"))
        add(Entry(DOWNLOAD, "default_image_resolution_rela", R.string.setting_default_image_resolution, keywords = "清晰度 分辨率 画质 原图 保存 resolution"))
        add(Entry(DOWNLOAD, "default_novel_format_rela", R.string.setting_default_novel_format, keywords = "小说 格式 txt epub pdf markdown 导出 export"))
        add(Entry(DOWNLOAD, "novel_header_rela", R.string.novel_header_settings_title, R.string.novel_header_settings_entry_desc, keywords = "信息头 元信息 头部 字段 作者信息 txt"))
        add(Entry(DOWNLOAD, "download_limit_type_rela", R.string.string_452, keywords = "限制 wifi 流量 蜂窝 移动网络 limit"))
        add(Entry(DOWNLOAD, "max_concurrent_downloads_rela", R.string.setting_max_concurrent_downloads, keywords = "并发 同时 多任务 线程 速度 concurrent"))
        add(Entry(DOWNLOAD, "illust_long_press_download_rela", R.string.string_405, keywords = "长按 下载 long press"))
        add(Entry(DOWNLOAD, "toast_download_result_rela", R.string.toast_download_result, keywords = "提示 通知 完成 toast"))
        add(Entry(DOWNLOAD, "write_exif_tags_rela", R.string.setting_write_exif_tags_title, R.string.setting_write_exif_tags_desc, keywords = "标签 关键词 exif xmp 元数据 相册 keywords metadata tags dc:subject"))
        add(Entry(DOWNLOAD, "silent_download_rela", R.string.setting_silent_download_title, R.string.setting_silent_download_desc, keywords = "低调 静默 隐藏 隐身 最近 微信 qq 相册 时间 recent hide silent wechat"))
        add(Entry(DOWNLOAD, "aria2_rela", R.string.aria2_settings_title, R.string.aria2_settings_entry_desc, keywords = "aria2 远程 nas rpc 服务器 remote"))

        // AI 功能
        add(Entry(AI, "default_upscale_model_rela", R.string.string_default_upscale_model, keywords = "超分 放大 高清 修复 esrgan realcugan waifu2x upscale ai放大"))
        add(Entry(AI, "default_rembg_model_rela", R.string.string_default_rembg_model, keywords = "抠图 去背景 背景移除 透明 rembg"))
        add(Entry(AI, "bubble_detector_model_rela", R.string.string_bubble_detector_model, keywords = "气泡 文本框 检测 漫画翻译 detector"))
        add(Entry(AI, "ocr_model_rela", R.string.string_ocr_model, keywords = "ocr 识别 文字 日文 漫画翻译"))

        // 备份与缓存
        add(Entry(DATA, "backup_rela", R.string.string_420, keywords = "备份 导出 设置 json backup"))
        add(Entry(DATA, "restore_rela", R.string.string_421, keywords = "还原 恢复 导入 restore"))
        add(Entry(DATA, "moon_upload_rela", R.string.moon_upload_title, keywords = "云端 上传 配置 同步 cloud upload"))
        add(Entry(DATA, "moon_sync_rela", R.string.moon_manual_sync_title, keywords = "云端 下载 配置 同步 cloud sync"))
        add(Entry(DATA, "clear_image_cache", R.string.string_101, keywords = "缓存 清理 空间 占用 图片 cache"))
        add(Entry(DATA, "clear_gif_cache", R.string.string_102, keywords = "缓存 清理 gif 动图 cache"))
        add(Entry(DATA, "clear_bulk_download_cache", R.string.clear_bulk_download_cache, keywords = "批量下载 数据库 清理 占用 瘦身 空间 cache"))

        // 试验性
        add(Entry(EXPERIMENTAL, "show_chat_room_entry_rela", R.string.setting_show_chat_room_entry, R.string.setting_chat_room_entry_warning, keywords = "聊天室 聊天 侧边栏 chat"))
        add(Entry(EXPERIMENTAL, "show_chat_room_push_banner_rela", R.string.setting_show_chat_room_push_banner, keywords = "推送 横幅 新消息 通知 banner push"))
        add(Entry(EXPERIMENTAL, "show_plaza_entry_rela", R.string.setting_show_plaza_entry, keywords = "广场 侧边栏 plaza"))
        add(Entry(EXPERIMENTAL, "is_firebase_enable_rela", R.string.string_367, keywords = "统计 分析 隐私 数据收集 遥测 firebase analytics"))
    }

    fun entriesOf(category: Category): List<Entry> = entries.filter { it.category === category }

    /**
     * 分词 AND 匹配：每个词都要命中 标题/描述/分类名/keywords 之一；
     * 结果按相关度排序（标题命中 > 标题+描述命中 > 仅别名/分类命中），同档保持目录顺序。
     */
    fun search(context: Context, query: String): List<Entry> {
        val tokens = query.trim().split(WHITESPACE).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return emptyList()
        val ranked = ArrayList<Pair<Entry, Int>>()
        for (entry in entries) {
            val title = context.getString(entry.titleRes)
            val desc = if (entry.descRes != 0) context.getString(entry.descRes) else ""
            val haystack = buildString {
                append(title).append(' ').append(desc).append(' ')
                append(context.getString(entry.category.titleRes)).append(' ')
                append(entry.keywords)
            }
            if (!tokens.all { haystack.contains(it, ignoreCase = true) }) continue
            val rank = when {
                tokens.all { title.contains(it, ignoreCase = true) } -> 0
                tokens.all { title.contains(it, true) || desc.contains(it, true) } -> 1
                else -> 2
            }
            ranked.add(entry to rank)
        }
        ranked.sortBy { it.second } // 稳定排序，同档保持目录顺序
        return ranked.map { it.first }
    }

    private val WHITESPACE = Regex("\\s+")

    @JvmStatic
    @JvmOverloads
    fun open(context: Context, category: Category, highlightIdName: String? = null) {
        val intent = Intent(context, TemplateActivity::class.java)
        intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, PAGE_CATEGORY)
        intent.putExtra(EXTRA_CATEGORY, category.key)
        if (!highlightIdName.isNullOrEmpty()) {
            intent.putExtra(EXTRA_HIGHLIGHT, highlightIdName)
        }
        context.startActivity(intent)
    }

    @JvmStatic
    fun fragmentFor(categoryKey: String?): Fragment {
        return when (categoryKey) {
            ACCOUNT.key -> FragmentSettingsAccount()
            NETWORK.key -> FragmentSettingsNetwork()
            APPEARANCE.key -> FragmentSettingsAppearance()
            BROWSING.key -> FragmentSettingsBrowsing()
            VIEWING.key -> FragmentSettingsViewing()
            BOOKMARKS.key -> FragmentSettingsBookmarks()
            DOWNLOAD.key -> FragmentSettingsDownload()
            AI.key -> FragmentSettingsAi()
            DATA.key -> FragmentSettingsData()
            EXPERIMENTAL.key -> FragmentSettingsExperimental()
            else -> FragmentSettingsHub()
        }
    }

    /**
     * 把设置页分段行的中性底色换成 [V3Palette.cardFill] 的主题 tint（同搜索 sheet 卡片）。
     * bg_m3_row_* 是 ripple 包 GradientDrawable，原地 mutate 换 fill + hairline，
     * 圆角与 ripple 保持不变；递归处理整棵子树，行以外的背景不受影响。
     */
    @JvmStatic
    fun applyThemedRowBg(view: View) {
        val palette = V3Palette.from(view.context)
        val strokePx = (0.5f * view.resources.displayMetrics.density).coerceAtLeast(1f).toInt()
        tintRowsRecursively(view, palette, strokePx)
    }

    private fun tintRowsRecursively(v: View, palette: V3Palette, strokePx: Int) {
        val bg = v.background
        if (bg is RippleDrawable && bg.numberOfLayers > 0 && bg.getDrawable(0) is GradientDrawable) {
            bg.mutate()
            (bg.getDrawable(0) as GradientDrawable).apply {
                setColor(palette.cardFill)
                setStroke(strokePx, palette.cardHairline)
            }
        }
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) {
                tintRowsRecursively(v.getChildAt(i), palette, strokePx)
            }
        }
    }

    /**
     * 搜索跳转的落点高亮：分类页打开时若 intent 带 EXTRA_HIGHLIGHT，
     * 滚动到那一行并用主题色 foreground 闪一下。
     */
    @JvmStatic
    fun maybeHighlight(fragment: Fragment, root: View) {
        val idName = fragment.activity?.intent?.getStringExtra(EXTRA_HIGHLIGHT) ?: return
        val viewId = root.resources.getIdentifier(idName, "id", root.context.packageName)
        if (viewId == 0) return
        val target = root.findViewById<View>(viewId) ?: return
        val scroll = root.findViewById<NestedScrollView>(R.id.scroll_view) ?: return
        root.post {
            // 条件隐藏的行（如 V3 关闭时的 FAB 顺序、词典关闭时的管理入口）此刻是 GONE：
            // 没有布局位置，滚动/闪烁都是错的，直接正常打开页面即可。
            if (!target.isShown || target.height == 0) {
                return@post
            }
            var y = 0
            var v: View = target
            while (v !== scroll) {
                y += v.top
                v = (v.parent as? View) ?: break
            }
            scroll.smoothScrollTo(0, (y - scroll.height / 4).coerceAtLeast(0))
            flash(target)
        }
    }

    private fun flash(target: View) {
        val tv = TypedValue()
        target.context.theme.resolveAttribute(androidx.appcompat.R.attr.colorPrimary, tv, true)
        val overlay = ColorDrawable(ColorUtils.setAlphaComponent(tv.data, 255))
        overlay.alpha = 0
        target.foreground = overlay
        ValueAnimator.ofInt(0, 60, 60, 0).apply {
            duration = 1800
            startDelay = 250
            addUpdateListener {
                overlay.alpha = it.animatedValue as Int
                target.invalidate()
            }
            start()
        }
    }
}
