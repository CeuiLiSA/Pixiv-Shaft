package ceui.pixiv.ui.me

import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.annotation.IdRes
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import ceui.lisa.BuildConfig
import ceui.lisa.R
import ceui.lisa.activities.MainActivity
import ceui.lisa.utils.GlideUrlChild
import ceui.lisa.utils.GlideUtil
import ceui.pixiv.api.Client
import ceui.pixiv.session.SessionManager
import ceui.pixiv.ui.navigation.BottomSafeInsets
import ceui.pixiv.ui.navigation.DrawerIconCatalog
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions.withCrossFade
import com.bumptech.glide.request.RequestOptions.bitmapTransform
import jp.wasabeef.glide.transformations.BlurTransformation
import kotlinx.coroutines.launch

/**
 * 「我」 tab 设计参考自一张外部截图(hero 图 + 3 列亮点卡 + 分组网格),业务全部
 * 复用 [MainActivity.handleDrawerAction] —— 所有点击都是侧边栏入口的别名,不
 * 重复跳转逻辑。Debug 入口仍按 BuildConfig.DEBUG 隐藏,与侧边栏可见性保持一致。
 */
class MeFragment : Fragment(R.layout.fragment_me) {

    private data class Entry(
        @StringRes val title: Int,
        @IdRes val drawerActionId: Int,
    )

    private data class Section(
        @StringRes val title: Int,
        val entries: List<Entry>,
    )

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // hero 不需要 statusBar top padding —— 背景图就该延伸到 status bar 下面;
        // 内容(avatar 行)靠 layout_gravity=bottom 自然在底部,不受 inset 影响。

        // 底部安全区:首页底栏浮在内容之上且会跟随滚动收起,页面铺满整屏,末尾靠 padding 让位。
        BottomSafeInsets.applyTo(view.findViewById(R.id.meScroll))

        // 头像/昵称跟随会话变化自动重绑:登录态、切号、编辑资料、前台静默同步都会写回新值。
        // observe 会在视图 STARTED 时先回放一次当前账号完成首绑,取代原来的 bindUserHeader(view)。
        SessionManager.loggedInAccount.observe(viewLifecycleOwner) { bindUserHeader(view) }
        bindHeroBackground(view)
        bindStatsRow(view)
        bindSections(view)
    }

    /**
     * Hero 背景:优先用户 profile.background_image_url(参考 V3UActivity / MineProfileFragment),
     * fallback 走 XML 里挂的 user_bg_main 静态图。Blur 跟 MineProfileFragment 一致(15/3)。
     */
    private fun bindHeroBackground(root: View) {
        val heroBg = root.findViewById<ImageView>(R.id.heroBackground)
        val uid = SessionManager.loggedInUid
        if (uid == 0L) return

        viewLifecycleOwner.lifecycleScope.launch {
            // 这次请求本身就带着自己的最新 user，顺手回写会话，头像/昵称一起更新。
            // 会员状态单独从 profile 里传：那是它的权威位置，而 ingestFreshUser 只认显式
            // 传进去的这一份（理由见那边的注释），不会自己从 user 对象里捡。
            val profile = runCatching { Client.appApi.getUserProfile(uid) }.getOrNull()
            SessionManager.ingestFreshUser(profile?.user, uid, profile?.profile?.is_premium)
            val bannerUrl = profile?.profile?.background_image_url
            if (!bannerUrl.isNullOrEmpty() && view != null) {
                Glide.with(this@MeFragment)
                    .load(GlideUrlChild(bannerUrl))
                    .apply(bitmapTransform(BlurTransformation(15, 3)))
                    .transition(withCrossFade())
                    .into(heroBg)
            }
        }
    }

    private fun bindUserHeader(root: View) {
        val avatar = root.findViewById<ImageView>(R.id.avatar)
        val greeting = root.findViewById<TextView>(R.id.greetingText)
        val subInfo = root.findViewById<TextView>(R.id.subInfoText)

        val user = SessionManager.loggedInUser
        if (user != null) {
            Glide.with(this).load(GlideUtil.getHead(user)).into(avatar)
            val displayName = user.name?.takeIf { it.isNotBlank() }
                ?: SessionManager.accountName
                ?: ""
            greeting.text = getString(R.string.me_greeting_format, displayName)
            val mail = SessionManager.mailAddress
            subInfo.text = if (!mail.isNullOrBlank()) {
                mail
            } else {
                getString(R.string.me_subinfo_uid_format, SessionManager.loggedInUid)
            }
        } else {
            greeting.text = getString(R.string.me_greeting_guest)
            subInfo.text = ""
        }
    }

    private fun bindStatsRow(root: View) {
        val row = root.findViewById<LinearLayout>(R.id.statsRow)
        val inflater = LayoutInflater.from(requireContext())

        // 每格独立 accent 色(参考图就是 cloud 蓝 / clock 绿 / refresh 灰各家自扫的样式),
        // 用 V3 调色板,光暗自动适配
        data class Stat(
            @DrawableRes val icon: Int,
            @StringRes val title: Int,
            @ColorRes val accent: Int,
            @IdRes val drawerActionId: Int,
        )

        val stats = listOf(
            Stat(R.drawable.ic_home_black_24dp, R.string.user_main_page, R.color.v3_blue, R.id.main_page),
            Stat(R.drawable.ic_file_download_black_24dp, R.string.download_manager, R.color.v3_green, R.id.nav_gallery),
            Stat(R.drawable.ic_baseline_settings_24, R.string.app_settings, R.color.v3_purple, R.id.nav_manage),
        )
        stats.forEach { entry ->
            val cell = inflater.inflate(R.layout.cell_me_stat, row, false)
            val accent = ContextCompat.getColor(requireContext(), entry.accent)
            val icon = cell.findViewById<ImageView>(R.id.statIcon)
            icon.setImageResource(entry.icon)
            icon.setColorFilter(accent)
            // 圆角图标背景:XML 里 inflate 出来是 GradientDrawable,setColor 派一档 accent 的浅 tint
            (icon.parent as? FrameLayout)?.background?.let { bg ->
                (bg.mutate() as? GradientDrawable)?.setColor(accent.withAlpha(0x24))
            }
            cell.findViewById<TextView>(R.id.statLabel).setText(entry.title)
            cell.setOnClickListener { dispatch(entry.drawerActionId) }
            row.addView(cell)
        }
    }

    private fun bindSections(root: View) {
        val host = root.findViewById<LinearLayout>(R.id.sectionsHost)
        val inflater = LayoutInflater.from(requireContext())

        sections().forEach { section ->
            host.addView(buildSectionHeader(section.title))
            host.addView(buildGrid(inflater, section.entries))
        }
    }

    private fun buildSectionHeader(@StringRes titleRes: Int): TextView {
        val tv = TextView(requireContext())
        tv.setText(titleRes)
        // V3 中性 text_2(光暗两套 alpha 已内置)—— 不染主题色,试过了观感太重
        tv.setTextColor(ContextCompat.getColor(requireContext(), R.color.v3_text_2))
        tv.textSize = 13f
        val sidePad = dp(20)
        val topPad = dp(16)
        val bottomPad = dp(8)
        tv.setPadding(sidePad, topPad, sidePad, bottomPad)
        return tv
    }

    private fun buildGrid(inflater: LayoutInflater, entries: List<Entry>): GridLayout {
        val grid = GridLayout(requireContext())
        grid.columnCount = 3
        val side = dp(8)
        grid.setPadding(side, 0, side, dp(8))
        grid.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )
        entries.forEach { entry ->
            val cell = inflater.inflate(R.layout.cell_me_entry, grid, false)
            // icon tint / label 颜色都走 XML 里 v3_text_2 / v3_text_1,保持中性 —— 主题色只染顶上 stat card
            cell.findViewById<ImageView>(R.id.cellIcon)
                .setImageResource(DrawerIconCatalog.iconFor(entry.drawerActionId))
            cell.findViewById<TextView>(R.id.cellLabel).setText(entry.title)
            cell.setOnClickListener { dispatch(entry.drawerActionId) }
            val params = GridLayout.LayoutParams().apply {
                width = 0
                height = GridLayout.LayoutParams.WRAP_CONTENT
                // 必须显式 FILL:android.widget.GridLayout 的 column 默认 alignment 是 START,
                // 缺 FILL 时 width=0 的子 view 测不到 column 的 weighted 宽度,会塌成 0 宽
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1, GridLayout.FILL, 1f)
                rowSpec = GridLayout.spec(GridLayout.UNDEFINED)
            }
            grid.addView(cell, params)
        }
        return grid
    }

    private fun dispatch(@IdRes drawerActionId: Int) {
        (activity as? MainActivity)?.handleDrawerAction(drawerActionId)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    /** Color int 替 alpha 通道,alpha 是 0..0xFF。常用于派生「主题色系列」深浅。 */
    private fun Int.withAlpha(alpha: Int): Int = (this and 0x00FFFFFF) or ((alpha and 0xFF) shl 24)

    /**
     * 与 MainActivity.buildDrawerMenu(侧边栏)对齐:常用主入口 + 我的收藏(原 string_318)+
     * 其它(the_others)+ 试验性(experimental_section,debug only 项目同 MainActivity)。
     */
    private fun sections(): List<Section> {
        val mine = Section(
            title = R.string.string_318,
            entries = listOf(
                Entry(R.string.string_319, R.id.illust_star),
                Entry(R.string.string_320, R.id.novel_star),
                Entry(R.string.watchlist, R.id.watchlist),
                Entry(R.string.core_string_novel_marker, R.id.novel_markers),
                Entry(R.string.string_321, R.id.follow_user),
            ),
        )
        val quickAccess = Section(
            title = R.string.me_section_quick,
            entries = listOf(
                Entry(R.string.view_history, R.id.nav_slideshow),
                Entry(R.string.watch_later, R.id.watch_later),
                Entry(R.string.snapshot_manager_title, R.id.nav_snapshot),
                Entry(R.string.prime_tags, R.id.nav_prime_tags),
                Entry(R.string.muted_history, R.id.muted_list),
            ),
        )
        val others = Section(
            title = R.string.the_others,
            entries = listOf(
                Entry(R.string.string_ai_upscale_standalone, R.id.nav_ai_upscale),
                Entry(R.string.search_image_origin, R.id.nav_reverse),
                Entry(R.string.latest_work, R.id.nav_new_work),
                Entry(R.string.referral_entry, R.id.nav_referral_plan),
                Entry(R.string.about_app, R.id.nav_share),
            ),
        )

        val out = mutableListOf(mine, quickAccess, others)

        // 试验性分区:
        //   github 渠道 release 保留 批量下载 Debug + 操作记录 + 站长推荐(对齐 MainActivity drawer 可见性);其它仅 debug。
        //   google play 渠道为合规起见整段不出现,且服务端依赖入口(站长推荐 / 操作记录)在任何 google build 都不展示。
        val isLite = BuildConfig.IS_LITE
        if (!(isLite && !BuildConfig.DEBUG)) {
            val experimentalEntries = mutableListOf(
                Entry(R.string.local_novel_entry, R.id.nav_local_novel),
                Entry(R.string.debug_bulk_dl_entry, R.id.nav_debug_bulk_dl),
            )
            // 站长推荐 / 操作记录:非 google 渠道常驻(release 也放出);google flavor 合规起见不展示。
            if (!isLite) {
                experimentalEntries += listOf(
                    Entry(R.string.event_history, R.id.nav_event_history),
                    Entry(R.string.current_hot, R.id.nav_current_hot),
                    Entry(R.string.site_recommend, R.id.nav_site_recommend),
                )
            }
            if (BuildConfig.DEBUG) {
                experimentalEntries += listOf(
                    Entry(R.string.chat_drawer_entry, R.id.nav_chat_room),
                    Entry(R.string.plaza_drawer_entry, R.id.nav_plaza),
                )
            }
            out += Section(R.string.experimental_section, experimentalEntries)
        }

        return out
    }
}
