package ceui.pixiv.ui.embedding

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.window.embedding.ActivityFilter
import androidx.window.embedding.ActivityRule
import androidx.window.embedding.RuleController
import androidx.window.embedding.SplitAttributes
import androidx.window.embedding.SplitPairFilter
import androidx.window.embedding.SplitPairRule
import androidx.window.embedding.SplitPlaceholderRule
import androidx.window.embedding.SplitRule
import ceui.lisa.activities.ImageDetailActivity
import ceui.lisa.activities.MainActivity
import ceui.lisa.activities.VPActivity
import ceui.pixiv.ui.embedding.TabletActivityEmbedding.install
import ceui.pixiv.ui.slideshow.SlideshowActivity

/**
 * 平板大屏双栏（issue #931）：androidx.window Activity Embedding。
 *
 * 本仓是多 Activity 架构（首页 MainActivity 之上叠 VActivity / UActivity /
 * SearchActivity / TemplateActivity …），所以不重写任何导航，只声明分栏规则，
 * 由 WindowManager 把同一个 task 里的 Activity 摆成左 1/3 列表 + 右 2/3 详情。
 * 规则只在平板（sw >= 600dp）上注册；手机完全不注册（原因见 [install]，issue #1002）。
 * 已注册的设备上，窗口宽度 < 600dp（平板分屏后的窄窗）时规则不激活；
 * 无 WM Extensions 的老设备上 RuleController 是 no-op。
 */
object TabletActivityEmbedding {

    private const val SPLIT_MIN_WIDTH_DP = 600

    /** 主栏（信息流）占 1/3，详情占 2/3，跟随 RTL。 */
    private val splitAttributes = SplitAttributes.Builder()
        .setSplitType(SplitAttributes.SplitType.ratio(3f / 7f))
        .setLayoutDirection(SplitAttributes.LayoutDirection.LOCALE)
        .build()

    fun install(context: Context) {
        // issue #1002：只在平板（sw >= 600dp）上注册规则。「规则不激活 = 没影响」是错的——
        // 只要 setRules 注册过，WM Extensions 的 TaskFragmentOrganizer 就接管整个 task，
        // 手机上照样生效：finish 连坐（见 SplitPairRule 注释）之外，Android 14+ 的 shell
        // transition 把 task 拉回前台时还要等 app 进程侧的 organizer 应答，进程被后台冻结时
        // 应答迟到，桌面/多任务会整体卡住约 5 秒（转场同步超时）才放行。手机永远分不了栏，
        // 不该为一个用不上的特性挂 organizer。折叠屏折叠态启动时 sw < 600dp 会跳过注册、
        // 展开后本进程内无分栏，属于可接受的取舍——规则本来就只能在进程启动时注册一次。
        if (context.resources.configuration.smallestScreenWidthDp < SPLIT_MIN_WIDTH_DP) {
            return
        }
        // 配对规则只认 MainActivity 做 primary：首页开出的任何页面进右栏。
        // 右栏内部的链式跳转（详情 → 评论 / 画师主页 / 二级详情）故意不配规则——
        // 按 Activity Embedding 的默认行为它们会堆叠在右栏容器顶部，左栏信息流常驻。
        // 千万不要放宽成 (*, *)：那会让右栏发起方被提升为新 split 的 primary（信息流
        // 被挤出屏幕），且 UActivity 这类「startActivity 后立刻 finish」的蹦床会因
        // finishSecondaryWithPrimary=ALWAYS 把刚拉起的目标页连坐 finish 掉（真机已踩）。
        val contentSplit = SplitPairRule.Builder(
            setOf(
                SplitPairFilter(
                    ComponentName(context, MainActivity::class.java),
                    ComponentName("*", "*"),
                    null,
                )
            )
        )
            .setDefaultSplitAttributes(splitAttributes)
            .setMinWidthDp(SPLIT_MIN_WIDTH_DP)
            .setFinishPrimaryWithSecondary(SplitRule.FinishBehavior.NEVER)
            .setFinishSecondaryWithPrimary(SplitRule.FinishBehavior.ALWAYS)
            .build()

        // 首页没打开任何内容时右栏放占位页，保持 1/3 + 2/3 的稳定布局。
        val placeholder = SplitPlaceholderRule.Builder(
            setOf(ActivityFilter(ComponentName(context, MainActivity::class.java), null)),
            Intent(context, SplitPlaceholderActivity::class.java)
        )
            .setDefaultSplitAttributes(splitAttributes)
            .setMinWidthDp(SPLIT_MIN_WIDTH_DP)
            .setFinishPrimaryWithPlaceholder(SplitRule.FinishBehavior.ADJACENT)
            .build()

        // 沉浸式查看器始终铺满整窗，不塞进 2/3 的格子里。
        val fullscreenViewers = ActivityRule.Builder(
            setOf(
                ImageDetailActivity::class.java,
                SlideshowActivity::class.java,
                VPActivity::class.java,
            ).map { ActivityFilter(ComponentName(context, it), null) }.toSet()
        )
            .setAlwaysExpand(true)
            .build()

        RuleController.getInstance(context)
            .setRules(setOf(contentSplit, placeholder, fullscreenViewers))
    }
}
