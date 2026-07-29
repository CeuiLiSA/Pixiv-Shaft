package ceui.pixiv.ui.navigation;

import androidx.annotation.DrawableRes;
import androidx.annotation.IdRes;

import ceui.lisa.R;

/**
 * 侧边栏动作的唯一图标目录。
 *
 * <p>侧边栏和「我的」页会展示同一批动作。图标按动作而不是按页面配置，避免两个入口
 * 独立维护后出现语义漂移。未知动作使用中性的帮助图标，保证动态入口不会因图标缺失崩溃。</p>
 */
public final class DrawerIconCatalog {

    private DrawerIconCatalog() {
    }

    @DrawableRes
    public static int iconFor(@IdRes int actionId) {
        if (actionId == R.id.illust_star) {
            return R.drawable.ic_baseline_palette_24;
        } else if (actionId == R.id.novel_star) {
            return R.drawable.ic_baseline_menu_book_24;
        } else if (actionId == R.id.watch_later) {
            return R.drawable.ic_watch_later_24;
        } else if (actionId == R.id.nav_pinned_tags) {
            return R.drawable.ic_loyalty_black_24dp;
        } else if (actionId == R.id.nav_feature) {
            return R.drawable.ic_huangguan;
        } else if (actionId == R.id.watchlist) {
            return R.drawable.ic_baseline_remove_red_eye_24;
        } else if (actionId == R.id.novel_markers) {
            return R.drawable.ic_baseline_bookmark_24;
        } else if (actionId == R.id.follow_user) {
            return R.drawable.ic_baseline_how_to_reg_24;
        } else if (actionId == R.id.nav_fans) {
            return R.drawable.ic_supervisor_account_black_24dp;
        } else if (actionId == R.id.nav_slideshow) {
            return R.drawable.ic_history_black_24dp;
        } else if (actionId == R.id.nav_gallery) {
            return R.drawable.ic_file_download_black_24dp;
        } else if (actionId == R.id.nav_notifications) {
            return R.drawable.ic_notifications_black_24dp;
        } else if (actionId == R.id.muted_list) {
            return R.drawable.ic_not_interested_black_24dp;
        } else if (actionId == R.id.nav_event_history) {
            return R.drawable.ic_date_range_black_24dp;
        } else if (actionId == R.id.nav_manage) {
            return R.drawable.ic_baseline_settings_24;
        } else if (actionId == R.id.nav_ai_upscale) {
            return R.drawable.baseline_auto_awesome_24;
        } else if (actionId == R.id.nav_reverse) {
            return R.drawable.ic_collections_black_24dp;
        } else if (actionId == R.id.nav_share) {
            return R.drawable.ic_help_outline_black_24dp;
        } else if (actionId == R.id.nav_discovery) {
            return R.drawable.ic_baseline_explore_24;
        } else if (actionId == R.id.nav_local_novel) {
            return R.drawable.ic_local_folder;
        } else if (actionId == R.id.nav_chat_room) {
            return R.drawable.ic_chat_black_24dp;
        } else if (actionId == R.id.nav_plaza) {
            return R.drawable.ic_plaza_forum_24;
        } else if (actionId == R.id.nav_debug_bulk_dl) {
            return R.drawable.ic_file_download_done_24dp;
        } else if (actionId == R.id.nav_saf_perf_test) {
            return R.drawable.ic_baseline_data_usage_24;
        } else if (actionId == R.id.nav_network_test) {
            return R.drawable.ic_baseline_dns_24;
        } else if (actionId == R.id.nav_tag_popular_export) {
            return R.drawable.ic_v3_export_24;
        } else if (actionId == R.id.nav_prime_tags || actionId == R.id.nav_current_hot) {
            return R.drawable.outline_whatshot_24;
        } else if (actionId == R.id.nav_new_work) {
            return R.drawable.ic_fiber_new_black_24dp;
        } else if (actionId == R.id.nav_site_recommend) {
            return R.drawable.ic_baseline_star_24;
        }
        return R.drawable.ic_help_outline_black_24dp;
    }
}
