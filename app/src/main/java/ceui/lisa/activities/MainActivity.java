package ceui.lisa.activities;

import static ceui.lisa.R.id.nav_gallery;
import static ceui.lisa.R.id.nav_slideshow;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.pm.PackageManager;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.BackEventCompat;
import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.splashscreen.SplashScreen;
import androidx.core.view.GravityCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentPagerAdapter;
import androidx.viewpager.widget.ViewPager;

import com.blankj.utilcode.util.BarUtils;
import com.bumptech.glide.Glide;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import ceui.pixiv.witstudio.dialog.WitDialog;
import ceui.pixiv.witstudio.dialog.WitDialogAction;


import ceui.lisa.R;
import ceui.lisa.core.Manager;
import ceui.lisa.databinding.ActivityCoverBinding;
import ceui.lisa.fragments.FragmentCenter;
import ceui.lisa.fragments.FragmentLeft;
import ceui.lisa.fragments.FragmentRight;
import ceui.lisa.fragments.FragmentViewPager;
import ceui.pixiv.ui.me.MeFragment;
import ceui.lisa.helper.DrawerLayoutHelper;
import ceui.lisa.helper.DrawerPredictiveBack;
import ceui.lisa.helper.NavigationLocationHelper;
import ceui.lisa.utils.Common;
import ceui.lisa.utils.Dev;
import ceui.lisa.utils.GlideUtil;
import ceui.lisa.utils.Params;
import ceui.lisa.utils.ReverseImage;
import ceui.lisa.view.DrawerLayoutViewPager;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import ceui.pixiv.shaftapi.Nana7miPlan;
import ceui.pixiv.services.ServicesProvider;
import ceui.pixiv.config.RemoteAppConfig;
import ceui.pixiv.push.InAppPushCenter;
import ceui.pixiv.session.SessionManager;
import ceui.pixiv.ui.navigation.BottomBarAutoHide;
import ceui.pixiv.ui.navigation.DrawerIconCatalog;
import ceui.pixiv.ui.navigation.TemplateRoute;

/**
 * 主页
 */
public class MainActivity extends BaseActivity<ActivityCoverBinding> implements ColdStartSplashHost {

    public static final String[] ALL_SELECT_WAY = new String[]{"图库选图", "文件管理器选图"};
    private long mExitTime;
    private static final long EXIT_WINDOW_MS = 2000;
    private OnBackPressedCallback mainBackCallback;
    private DrawerPredictiveBack drawerPredictiveBack;
    private BottomBarAutoHide bottomBarAutoHide;
    private Fragment[] baseFragments = null;
    // 与 baseFragments 一一对应的底部菜单 item id;TAB 顺序可配置后,
    // id 和位置的关系不再固定,所有 id<->position 换算都查这张表
    private int[] tabMenuIds = null;

    /**
     * 开屏动画安全兜底超时：万一首页推荐插画 tab 没能按预期跑到（异常 / 未来改了默认
     * tab），也不能让开屏永久卡住——超时后强制放行，最坏情况退化回「开屏消失后闪一帧
     * 常规 loading」，而不是白屏假死。splashResolved 本身没有超时保护，靠这里兜底。
     */
    private static final long SPLASH_SAFETY_TIMEOUT_MS = 1200L;

    /** 开屏是否已放行。实例字段：每个 MainActivity 实例天然从 false 起，不需要 reset。 */
    private volatile boolean splashResolved = false;

    private final android.content.BroadcastReceiver profileReadyReceiver = new android.content.BroadcastReceiver() {
        @Override
        public void onReceive(android.content.Context context, Intent intent) {
            android.util.Log.d("Discovery/Gate", "received PROFILE_READY broadcast");
            buildDrawerMenu();
        }
    };

    /**
     * installSplashScreen 必须在 super.onCreate 之前调用（AndroidX SplashScreen 契约）。
     * keepOnScreenCondition 只等 splashResolved（首页推荐插画 tab 经 ColdStartSplashHost 回调的本地优先裁决），
     * 不等网络；安全超时兜底见 SPLASH_SAFETY_TIMEOUT_MS。
     *
     * 只有这次冷启动真的会落在首页推荐插画 tab（FragmentLeft，位置随 TAB 顺序设置变化）时，
     * 才值得等：用户设置了「启动到最近使用的页」或把默认 tab 指到别处时，
     * RecmdIllustFeedFragment(插画) 根本不会被创建，splashResolved 永远等不到
     * Fragment 那边的信号，只能靠安全超时兜底——那样每次冷启动都白等满 1200ms，
     * 比完全不做这个功能还差。落在其它 tab 时直接放行，不占这些用户便宜。
     * getNavigationInitPosition() 依赖 initView() 建好的 baseFragments，必须放在
     * super.onCreate() 之后读；initView() 提前异常导致 baseFragments 仍为 null 时
     * 同样直接放行，不让一次初始化失败连带把开屏焊死。
     */
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        SplashScreen splashScreen = SplashScreen.installSplashScreen(this);
        splashScreen.setKeepOnScreenCondition(() -> !splashResolved);
        super.onCreate(savedInstanceState);
        if (baseFragments == null || !(baseFragments[getNavigationInitPosition()] instanceof FragmentLeft)) {
            markSplashResolved();
        } else {
            new Handler(Looper.getMainLooper())
                    .postDelayed(this::markSplashResolved, SPLASH_SAFETY_TIMEOUT_MS);
        }
    }

    @Override
    public void markSplashResolved() {
        splashResolved = true;
    }

    @Override
    protected int initLayout() {
        return R.layout.activity_cover;
    }

    @Override
    public boolean hideStatusBar() {
        return Dev.hideMainActivityStatus;
    }

    @Override
    protected void initView() {
        baseBind.drawerLayout.setScrimColor(Color.TRANSPARENT);

        setUpAutoHidingBottomBar();

        // 抽屉整体 edge-to-edge:顶部补 status bar,底部补 nav bar(BaseActivity 开了 EdgeToEdge)
        baseBind.drawerContent.setPaddingRelative(
                baseBind.drawerContent.getPaddingStart(),
                BarUtils.getStatusBarHeight() + dp(12),
                baseBind.drawerContent.getPaddingEnd(),
                BarUtils.getNavBarHeight() + dp(24));
        // MD3 modal drawer:容器右缘 28dp 圆角(outline 来自 bg_drawer_sheet)
        baseBind.navView.setClipToOutline(true);
        buildDrawerMenu();

        // 监听画像构建完成，刷新发现入口可见性
        android.content.IntentFilter profileFilter = new android.content.IntentFilter(
                ceui.pixiv.db.discovery.ProfileManager.ACTION_PROFILE_READY);
        LocalBroadcastManager.getInstance(this).registerReceiver(profileReadyReceiver, profileFilter);

        // 侧边栏账号区完全由会话驱动：observe 在 onStart 先回放当前账号完成首绑,
        // 之后登录/切号/编辑资料/前台静默同步的每次写回都会自动重绑,不再需要
        // 手动 initDrawerHeader() 首绑 + Dev.refreshUser 在 onResume 补刷那一套。
        SessionManager.INSTANCE.getLoggedInAccount().observe(this, account -> initDrawerHeader());
        // 订阅档位是冷启动异步拉回来的,落地时机比账号晚,所以单独观察一次;不然徽章要等
        // 下一次冷启动才出现,首装的人则永远看不到。
        RemoteAppConfig remoteAppConfig = ((ServicesProvider) getApplication()).getRemoteAppConfig();
        remoteAppConfig.getNana7miPlanLive().observe(this, plan -> bindPlanBadge());
        // 应用内推送(付费用户公告)也是这次冷启动配置捎回来的,同样异步落地。只弹一次、
        // 弹过就回执,去重和让路(评分框)都在 InAppPushCenter 里。
        remoteAppConfig.getInAppPushLive().observe(this,
                arrival -> InAppPushCenter.INSTANCE.onConfigArrived(this, arrival));
        baseBind.drawerHeader.setOnClickListener(v -> openMyUserPage());
        // 侧边栏头像单击进自己主页；长按仍是 R18 临时过滤开关
        baseBind.userHead.setOnClickListener(v -> openMyUserPage());
        baseBind.userHead.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                boolean filterEnable = Shaft.sSettings.isR18FilterTempEnable();
                Shaft.sSettings.setR18FilterTempEnable(!filterEnable);
                Common.showToast(filterEnable ? "ԅ(♡﹃♡ԅ)" : "X﹏X");
                return true;
            }
        });
        baseBind.navigationView.setOnNavigationItemSelectedListener(new BottomNavigationView.OnNavigationItemSelectedListener() {
            @Override
            public boolean onNavigationItemSelected(@NonNull MenuItem item) {
                if (tabMenuIds == null) {
                    return false;
                }
                for (int i = 0; i < tabMenuIds.length; i++) {
                    if (tabMenuIds[i] == item.getItemId()) {
                        baseBind.viewPager.setCurrentItem(i);
                        return true;
                    }
                }
                return false;
            }
        });
        baseBind.navigationView.setOnNavigationItemReselectedListener(new BottomNavigationView.OnNavigationItemReselectedListener() {
            @Override
            public void onNavigationItemReselected(@NonNull MenuItem item) {
                if (item.getItemId() == R.id.action_1) {
                    for (Fragment baseFragment : baseFragments) {
                        if (baseFragment instanceof FragmentLeft) {
                            ((FragmentLeft) baseFragment).forceRefresh();
                        }
                    }
                } else if (item.getItemId() == R.id.action_2) {
                    for (Fragment baseFragment : baseFragments) {
                        if (baseFragment instanceof FragmentCenter) {
                            ((FragmentCenter) baseFragment).forceRefresh();
                        }
                    }
                } else if (item.getItemId() == R.id.action_3) {
                    for (Fragment baseFragment : baseFragments) {
                        if (baseFragment instanceof FragmentRight) {
                            ((FragmentRight) baseFragment).forceRefresh();
                        }
                    }
                } else if (item.getItemId() == R.id.action_4) {
                    for (Fragment baseFragment : baseFragments) {
                        if (baseFragment instanceof FragmentViewPager) {
                            ((FragmentViewPager) baseFragment).forceRefresh();
                        }
                    }
                }
            }
        });
        baseBind.viewPager.addOnPageChangeListener(new ViewPager.OnPageChangeListener() {
            @Override
            public void onPageScrolled(int position, float positionOffset, int positionOffsetPixels) {

            }

            @Override
            public void onPageSelected(int position) {
                if (tabMenuIds != null && position < tabMenuIds.length) {
                    baseBind.navigationView.setSelectedItemId(tabMenuIds[position]);
                }
                // 换 tab 必须把底栏放回来:收起状态下滑到别的 tab,否则没底栏可点。
                bottomBarAutoHide.reveal();
            }

            @Override
            public void onPageScrollStateChanged(int state) {

            }
        });

        baseBind.viewPager.setTouchEventForwarder(new DrawerLayoutViewPager.IForwardTouchEvent() {
            @Override
            public void forwardTouchEvent(MotionEvent ev) {
                getDrawer().onTouchEvent(ev);
            }
        });
        DrawerLayoutHelper.setCustomLeftEdgeSize(getDrawer(), 1.0f);

        // 返回键/返回手势:抽屉开着先关抽屉(Android 14+ 跟手滑出,见 DrawerPredictiveBack),
        // 否则走双击退出。targetSdk 35+ 后预测式返回默认开启,系统不再回调 onKeyDown,
        // 必须用 OnBackPressedDispatcher 接管。
        //
        // 预测式「回桌面」动画只在 app 没注册任何返回回调时才播,所以第一次按返回 toast 之后
        // 把这个 callback 关掉 2 秒(refreshMainBackCallback):第二次返回直接交给系统,
        // 跟手的回桌面预览照常播;2 秒过了再重新接管。抽屉开着时始终接管。
        // 不带 owner 注册(与 TemplateActivity 同理):垫在所有 Fragment callback 之下。
        drawerPredictiveBack = new DrawerPredictiveBack(baseBind.drawerLayout, baseBind.navView);
        mainBackCallback = new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackStarted(@NonNull BackEventCompat backEvent) {
                if (isDrawerOpen()) drawerPredictiveBack.onStarted();
            }

            @Override
            public void handleOnBackProgressed(@NonNull BackEventCompat backEvent) {
                if (isDrawerOpen()) drawerPredictiveBack.onProgressed(backEvent.getProgress());
            }

            @Override
            public void handleOnBackCancelled() {
                drawerPredictiveBack.onCancelled();
            }

            @Override
            public void handleOnBackPressed() {
                if (isDrawerOpen()) {
                    drawerPredictiveBack.close();
                } else {
                    exit();
                }
            }
        };
        getOnBackPressedDispatcher().addCallback(mainBackCallback);
        baseBind.drawerLayout.addDrawerListener(new DrawerLayout.SimpleDrawerListener() {
            @Override
            public void onDrawerOpened(@NonNull View drawerView) {
                refreshMainBackCallback();
            }

            @Override
            public void onDrawerClosed(@NonNull View drawerView) {
                refreshMainBackCallback();
            }
        });
    }

    private boolean isDrawerOpen() {
        return baseBind.drawerLayout.isDrawerOpen(GravityCompat.START);
    }

    /** 抽屉开着 || 不在「再按一次退出」的 2 秒窗口内 → 接管返回;否则交给系统播预测式回桌面。 */
    private void refreshMainBackCallback() {
        if (mainBackCallback == null) return;
        boolean exitArmed = System.currentTimeMillis() - mExitTime <= EXIT_WINDOW_MS;
        mainBackCallback.setEnabled(isDrawerOpen() || !exitArmed);
    }

    /**
     * 底栏跟随列表滚动收起 / 上滑恢复。
     *
     * 布局上底栏已经浮在 view_pager 之上(见 activity_cover.xml),所以内容页现在铺满整屏——
     * 被底栏压住的那一截靠这里补:把底栏高度(它自己已经吃掉了系统导航栏 inset)当作底部安全区
     * 重新分发给内容区,各列表沿用既有的「底部 systemBars inset -> paddingBottom」那套读法
     * (FeedFragment.applyBottomSafeInset / 发现页与「我」页的 NestedScrollView),不用各自去认
     * 「宿主有没有底栏」。首帧底栏还没量到高度,量到后由 layout 监听补发一次 inset。
     * inset 的 listener 挂在 content_host 这层壳上而不是 view_pager 上,原因见 activity_cover.xml。
     *
     * 收起 / 恢复的触发见 {@link BottomBarAutoHide}(不能靠 CoordinatorLayout 的嵌套滚动分发)。
     */
    private void setUpAutoHidingBottomBar() {
        ViewCompat.setOnApplyWindowInsetsListener(baseBind.contentHost, (v, windowInsets) -> {
            Insets navBars = windowInsets.getInsets(WindowInsetsCompat.Type.navigationBars());
            int bottom = Math.max(navBars.bottom, baseBind.navigationView.getHeight());
            return new WindowInsetsCompat.Builder(windowInsets)
                    .setInsets(WindowInsetsCompat.Type.navigationBars(),
                            Insets.of(navBars.left, navBars.top, navBars.right, bottom))
                    .build();
        });
        baseBind.navigationView.addOnLayoutChangeListener(
                (v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
                    if ((bottom - top) != (oldBottom - oldTop)) {
                        ViewCompat.requestApplyInsets(baseBind.contentHost);
                    }
                });

        bottomBarAutoHide = new BottomBarAutoHide(baseBind.navigationView);
        bottomBarAutoHide.install(this);
    }

    private void initFragment() {
        // 底部 TAB:前三个内容页(推荐/发现/动态)按设置的顺序排列,R18 与「我」固定在末尾。
        // 六种顺序对应设置页 string_343~348 的排列;menu xml 无法换序,菜单按最终顺序
        // 程序化构建(#969:换序的消费端在「主页显示R18」改造时丢失,此后设置一直不生效)
        final int[][] TAB_ORDERS = {{0, 1, 2}, {0, 2, 1}, {1, 0, 2}, {1, 2, 0}, {2, 0, 1}, {2, 1, 0}};
        final int[] TAB_MENU_IDS = {R.id.action_1, R.id.action_2, R.id.action_3};
        final int[] TAB_TITLES = {R.string.recommend, R.string.discover, R.string.whats_new};
        final int[] TAB_ICONS = {R.drawable.ic_tuijian, R.drawable.ic_discover, R.drawable.ic_dongtai};
        final Fragment[] contentPages = {new FragmentLeft(), new FragmentCenter(), new FragmentRight()};

        final int orderIndex = Shaft.sSettings.getBottomBarOrder();
        final int[] order = TAB_ORDERS[orderIndex >= 0 && orderIndex < TAB_ORDERS.length ? orderIndex : 0];

        boolean showR18Tab = Shaft.sSettings.isMainViewR18();
        boolean showMeTab = Dev.showMeTab;
        int count = order.length + (showR18Tab ? 1 : 0) + (showMeTab ? 1 : 0);
        baseFragments = new Fragment[count];
        tabMenuIds = new int[count];
        Menu menu = baseBind.navigationView.getMenu();
        int position = 0;
        for (int tab : order) {
            baseFragments[position] = contentPages[tab];
            tabMenuIds[position] = TAB_MENU_IDS[tab];
            menu.add(Menu.NONE, TAB_MENU_IDS[tab], Menu.NONE, TAB_TITLES[tab]).setIcon(TAB_ICONS[tab]);
            position++;
        }
        if (showR18Tab) {
            baseFragments[position] = FragmentViewPager.newInstance(Params.VIEW_PAGER_R18);
            tabMenuIds[position] = R.id.action_4;
            menu.add(Menu.NONE, R.id.action_4, Menu.NONE, R.string.string_r).setIcon(R.drawable.ic_xiongbu);
            position++;
        }
        if (showMeTab) {
            baseFragments[position] = new MeFragment();
            tabMenuIds[position] = R.id.action_5;
            menu.add(Menu.NONE, R.id.action_5, Menu.NONE, R.string.me_tab).setIcon(R.drawable.ic_me);
        }
        baseBind.viewPager.setAdapter(new FragmentPagerAdapter(getSupportFragmentManager()) {
            @Override
            public Fragment getItem(int i) {
                return baseFragments[i];
            }

            @Override
            public int getCount() {
                return baseFragments.length;
            }
        });
        baseBind.viewPager.setOffscreenPageLimit(baseFragments.length - 1);
        baseBind.viewPager.setCurrentItem(getNavigationInitPosition());
        Manager.get().restore();

        // Show rate dialog after a short delay to avoid disrupting app startup.
        // 浏览记录云同步同意框不在首页弹,改到用户点进浏览历史页时再问(见 FragmentHistoryTabs / issue #889)。
        baseBind.viewPager.postDelayed(() -> {
            // 应用内推送正在展示就这次不弹评分框(showIfNeeded 没跑到就不消耗那一次机会),
            // 两个框叠在一起谁都看不清。
            if (InAppPushCenter.INSTANCE.isShowing()) return;
            ceui.pixiv.widgets.RateAppDialog.Companion.showIfNeeded(getSupportFragmentManager());
        }, 2000);
    }

    @Override
    protected void initData() {
        if (SessionManager.INSTANCE.isLoggedIn()) {
            if (Common.isAndroidQ() || ContextCompat.checkSelfPermission(this,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
                // Android 10+ 无需 WRITE_EXTERNAL_STORAGE；pre-Q 已授权也直接进。
                initFragment();
            } else {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},
                        REQUEST_CODE_STORAGE_PERMISSION);
            }
        } else {
            // 不能走「startActivity + finish()」的蹦床：TabletActivityEmbedding 的
            // SplitPairRule(MainActivity, *) 带 finishSecondaryWithPrimary=ALWAYS，而且
            // TaskFragment 配对在手机窗宽（<600dp）下照样成立——minWidthDp 只管摆不摆成双栏。
            // 蹦床一 finish，刚 RESUME 的登录页作为 secondary 被连坐 finish，task 清空回桌面：
            // 未登录用户在有 WM Extensions 的设备上表现为「点开秒退、无崩溃无日志」的死循环
            // （v4.8.4/4.8.5 线上事故）。改用 CLEAR_TASK 让登录页直接成为 task root，
            // 系统清 task 不构成 primary-finish 连坐；与 Common.logOut 的写法保持一致。
            Intent intent = new Intent(mContext, TemplateActivity.class);
            intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, TemplateRoute.LOGIN.key);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(intent);
        }
    }

    private static final int REQUEST_CODE_STORAGE_PERMISSION = 1001;

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
            @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_CODE_STORAGE_PERMISSION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                initFragment();
            } else {
                Common.showToast(getString(R.string.access_denied));
                finish();
            }
        }
    }

    /** 侧边栏一条入口:分发 id + 标题 + 可见性门控。图标由动作目录统一提供。 */
    private static class DrawerEntry {
        final int id;
        final int titleRes;
        final boolean visible;
        /** 行右侧小胶囊角标文案(如「NEW」);null = 不显示。 */
        final String badge;

        DrawerEntry(int id, int titleRes, boolean visible, String badge) {
            this.id = id;
            this.titleRes = titleRes;
            this.visible = visible;
            this.badge = badge;
        }

        DrawerEntry(int id, int titleRes, boolean visible) {
            this(id, titleRes, visible, null);
        }

        DrawerEntry(int id, int titleRes) {
            this(id, titleRes, true);
        }
    }

    /**
     * 重建侧边栏分组(MD3-E 分段样式,同设置页)。所有入口的可见性门控收口在这里:
     * - 发现:画像完备(PROFILE_READY 广播 / onResume 时重建)
     * - 试验性分区:github 渠道 release 保留(其中 聊天室/广场 跟「设置 - 试验性」开关,
     *   标签热度导出 仅 debug);google play 渠道为合规起见整段隐藏。
     * - 当前最热 / 站长推荐 / 操作记录 / 通知中心:服务端或官方 API 依赖,google flavor 不展示。
     * 行按可见项重新生成,分段圆角(top/mid/bottom/single)永远贴合,不存在隐藏行破角问题。
     */
    private void buildDrawerMenu() {
        boolean isDebugBuild = ceui.lisa.BuildConfig.DEBUG;
        boolean isLite = ceui.lisa.BuildConfig.IS_LITE;
        boolean experimentalAllowed = !(isLite && !isDebugBuild);

        ceui.pixiv.db.discovery.UserProfile profile = ((ServicesProvider) getApplication()).getProfileManager().cached();
        boolean discoveryReady = profile != null && profile.isReady();
        android.util.Log.d("Discovery/Gate", "buildDrawerMenu, discoveryReady=" + discoveryReady);

        LinearLayout sections = baseBind.drawerSections;
        sections.removeAllViews();

        // 「浏览与发现」独立小组已撤:发现内容(最新/热度标签/特辑/本月收藏/当前最热)已铺进
        // 「发现」tab 的内容货架,「置顶标签」是用户自己钉的标签、并入「我的」;「发现」(算法流)
        // 只作深链兜底、gate 后极少可见,挪进「试验性」分区。点击 handler 全部保留。
        // 「个人主页」入口去掉——顶部账号整块点击即进自己主页,不再重复一行。
        // 「投稿」(pixiv upload.php 网页链接)已整体移除。
        addDrawerSection(sections, R.string.drawer_section_mine, new DrawerEntry[]{
                new DrawerEntry(R.id.illust_star, R.string.string_319),
                new DrawerEntry(R.id.novel_star, R.string.string_320),
                new DrawerEntry(R.id.watch_later, R.string.watch_later),
                new DrawerEntry(R.id.nav_pinned_tags, R.string.pinned_content),
                // 精华列:各处「收藏到精华」写进 feature_table 的本地列表快照。c3f08172 侧栏
                // 「发现」分组瘦身时被连带删掉,但它不属于搬进「发现」tab 的那批(最新/热度标签/
                // 特辑/本月收藏/当前最热),页面和 handler 一直都在——只是没入口,存了看不了。
                new DrawerEntry(R.id.nav_feature, R.string.string_248),
                new DrawerEntry(R.id.watchlist, R.string.watchlist),
                new DrawerEntry(R.id.novel_markers, R.string.core_string_novel_marker),
                new DrawerEntry(R.id.follow_user, R.string.string_321),
                new DrawerEntry(R.id.nav_fans, R.string.string_322),
        });

        // 借号用量:服务端两只配额桶的只读视图,紧贴「我的」之后、「记录与管理」之前 ——
        // 它是「查自己用了多少」,不属于任何一组功能入口。渠道口径跟着借号功能本身走
        // (google flavor 整个借号搜索都不出现),所以是 !isLite 而不是 experimentalAllowed:
        // 后者在 Lite debug 下仍会放行,会给一个功能不存在的包留下查不到东西的入口。
        if (!isLite) {
            addDrawerSection(sections, R.string.drawer_section_usage, new DrawerEntry[]{
                    new DrawerEntry(R.id.nav_nana7mi_usage, R.string.nana7mi_usage_title, true, "NEW"),
            });
        }

        // 高频入口前置:浏览历史 排在「记录与管理」首位,设置 排在「其他」首位。
        addDrawerSection(sections, R.string.drawer_section_records, new DrawerEntry[]{
                new DrawerEntry(nav_slideshow, R.string.view_history),
                new DrawerEntry(nav_gallery, R.string.download_manager),
                new DrawerEntry(R.id.nav_snapshot, R.string.snapshot_manager_title),
                new DrawerEntry(R.id.nav_notifications, R.string.notifications_and_info, experimentalAllowed),
                new DrawerEntry(R.id.muted_list, R.string.muted_history),
                new DrawerEntry(R.id.nav_event_history, R.string.event_history, !isLite),
        });

        addDrawerSection(sections, R.string.the_others, new DrawerEntry[]{
                new DrawerEntry(R.id.nav_manage, R.string.app_settings),
                new DrawerEntry(R.id.nav_ai_upscale, R.string.string_ai_upscale_standalone),
                new DrawerEntry(R.id.nav_reverse, R.string.search_image_origin),
                new DrawerEntry(R.id.nav_share, R.string.about_app),
        });

        addDrawerSection(sections, R.string.experimental_section, new DrawerEntry[]{
                new DrawerEntry(R.id.nav_discovery, R.string.string_discovery,
                        experimentalAllowed && discoveryReady),
                new DrawerEntry(R.id.nav_local_novel, R.string.local_novel_entry, experimentalAllowed),
                new DrawerEntry(R.id.nav_chat_room, R.string.chat_drawer_entry,
                        experimentalAllowed && Shaft.sSettings.isShowChatRoomEntry()),
                new DrawerEntry(R.id.nav_plaza, R.string.plaza_drawer_entry,
                        experimentalAllowed && Shaft.sSettings.isShowPlazaEntry()),
                new DrawerEntry(R.id.nav_debug_bulk_dl, R.string.debug_bulk_dl_entry, experimentalAllowed),
                new DrawerEntry(R.id.nav_saf_perf_test, R.string.saf_perf_test_entry, experimentalAllowed),
                new DrawerEntry(R.id.nav_network_test, R.string.nav_network_test_entry, experimentalAllowed),
                new DrawerEntry(R.id.nav_tag_popular_export, R.string.tag_popular_export_entry, isDebugBuild),
                // 中心页那个「Web 首页」chip 至今是 showComingSoon() 占位,StreetMainFragment
                // 一直没有可用入口。而网页登录(同步 PHPSESSID)只能从这个页面走,拉黑、按 tag
                // 筛画师作品都指着它 —— 没入口等于那些功能对普通用户是死的。用 !isLite 而不是
                // experimentalAllowed:后者在 Lite debug 下仍然放行,和 FragmentCenter 那个直接
                // 认 IS_LITE 的同名 chip 对不齐,Lite 就是所有 buildType 都不出现。
                new DrawerEntry(R.id.nav_web_home, R.string.street_title, !isLite),
                // FANBOX 没有官方 App,网页那套 API 里 post.info 还被 Cloudflare 挡了非浏览器
                // 客户端(正文得靠 FanboxWebBridge 从 WebView 里发)。Lite 不出现:同渠道口径,
                // Play 版不带这类站外付费内容入口。
                new DrawerEntry(R.id.nav_fanbox, R.string.fanbox_entry, !isLite),
        });
    }

    /**
     * 生成一个分组(MD3 drawer section):分割线 + 小节标题 + 透明底胶囊行。
     * 全组不可见则整组(含分割线/标题)不出现。
     */
    private void addDrawerSection(LinearLayout parent, int titleRes, DrawerEntry[] entries) {
        java.util.List<DrawerEntry> visible = new java.util.ArrayList<>();
        for (DrawerEntry entry : entries) {
            if (entry.visible) {
                visible.add(entry);
            }
        }
        if (visible.isEmpty()) {
            return;
        }
        LayoutInflater inflater = LayoutInflater.from(this);
        if (parent.getChildCount() > 0) {
            parent.addView(inflater.inflate(R.layout.item_drawer_divider, parent, false));
        }
        TextView header = (TextView) inflater.inflate(R.layout.item_drawer_section, parent, false);
        header.setText(titleRes);
        parent.addView(header);
        for (DrawerEntry entry : visible) {
            View row = inflater.inflate(R.layout.item_drawer_row, parent, false);
            ((ImageView) row.findViewById(R.id.drawer_row_icon)).setImageResource(DrawerIconCatalog.iconFor(entry.id));
            ((TextView) row.findViewById(R.id.drawer_row_title)).setText(entry.titleRes);
            TextView badge = row.findViewById(R.id.drawer_row_badge);
            if (entry.badge != null) {
                badge.setText(entry.badge);
                badge.setVisibility(View.VISIBLE);
            }
            row.setOnClickListener(v -> {
                handleDrawerAction(entry.id);
                baseBind.drawerLayout.closeDrawer(GravityCompat.START);
            });
            parent.addView(row);
        }
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }

    public DrawerLayout getDrawer() {
        return baseBind.drawerLayout;
    }

    /**
     * 侧边栏 / MeFragment 共用的入口分发。switch 跟 menu/activity_main_drawer.xml 的 id 对齐;
     * MeFragment 直接传 R.id.xxx 走这里,避免两边维护同样的跳转。
     */
    @SuppressLint("NonConstantResourceId")
    public void handleDrawerAction(int id) {
        Intent intent = null;
        if (id == nav_gallery) {
            intent = new Intent(mContext, TemplateActivity.class);
            intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, TemplateRoute.DOWNLOAD_MANAGER.key);
            intent.putExtra("hideStatusBar", false);
        } else if (id == R.id.nav_snapshot) {
            intent = new Intent(mContext, TemplateActivity.class);
            intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, TemplateRoute.SNAPSHOT_MANAGER.key);
            intent.putExtra("hideStatusBar", false);
        } else if (id == nav_slideshow) {
            intent = new Intent(mContext, TemplateActivity.class);
            intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, TemplateRoute.HISTORY.key);
        } else if (id == R.id.watch_later) {
            intent = new Intent(mContext, TemplateActivity.class);
            intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, TemplateRoute.WATCH_LATER.key);
        } else if (id == R.id.nav_notifications) {
            intent = new Intent(mContext, TemplateActivity.class);
            intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, TemplateRoute.NOTIFICATION_CENTER.key);
        } else if (id == R.id.nav_manage) {
            intent = new Intent(mContext, TemplateActivity.class);
            intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, TemplateRoute.SETTINGS.key);
        } else if (id == R.id.nav_nana7mi_usage) {
            intent = new Intent(mContext, TemplateActivity.class);
            intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, TemplateRoute.NANA7MI_USAGE.key);
        } else if (id == R.id.nav_prime_tags) {
            intent = new Intent(mContext, TemplateActivity.class);
            intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, TemplateRoute.PRIME_TAGS.key);
        } else if (id == R.id.nav_pinned_tags) {
            intent = new Intent(mContext, TemplateActivity.class);
            intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, TemplateRoute.PINNED_CONTENT.key);
        } else if (id == R.id.nav_discovery) {
            intent = new Intent(mContext, TemplateActivity.class);
            intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, TemplateRoute.DISCOVERY.key);
        } else if (id == R.id.nav_share) {
            intent = new Intent(mContext, TemplateActivity.class);
            intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, TemplateRoute.ABOUT.key);
        } else if (id == R.id.main_page) {
            intent = new Intent(mContext, UActivity.class);
            intent.putExtra(Params.USER_ID, SessionManager.INSTANCE.getLoggedInUid());
        } else if (id == R.id.nav_ai_upscale) {
            intent = new Intent(mContext, TemplateActivity.class);
            intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, TemplateRoute.AI_UPSCALE.key);
        } else if (id == R.id.nav_reverse) {
            selectPhoto();
        } else if (id == R.id.nav_new_work) {
            intent = new Intent(mContext, TemplateActivity.class);
            intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, TemplateRoute.NEW_WORKS.key);
            intent.putExtra("hideStatusBar", false);
        } else if (id == R.id.muted_list) {
            intent = new Intent(mContext, TemplateActivity.class);
            intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, TemplateRoute.MUTED_TAGS.key);
        } else if (id == R.id.nav_feature) {
            intent = new Intent(mContext, TemplateActivity.class);
            intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, TemplateRoute.FEATURE_LIST.key);
        } else if (id == R.id.nav_fans) {
            intent = new Intent(mContext, TemplateActivity.class);
            intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, TemplateRoute.FANS.key);
        } else if (id == R.id.illust_star) {
            intent = new Intent(mContext, TemplateActivity.class);
            intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, TemplateRoute.MY_ILLUST_COLLECTION.key);
            intent.putExtra("hideStatusBar", false);
        } else if (id == R.id.novel_star) {
            intent = new Intent(mContext, TemplateActivity.class);
            intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, TemplateRoute.MY_NOVEL_COLLECTION.key);
            intent.putExtra("hideStatusBar", false);
        } else if (id == R.id.watchlist) {
            intent = new Intent(mContext, TemplateActivity.class);
            intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, TemplateRoute.WATCHLIST.key);
            intent.putExtra("hideStatusBar", false);
        } else if (id == R.id.novel_markers) {
            intent = new Intent(mContext, TemplateActivity.class);
            intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, TemplateRoute.NOVEL_MARKERS.key);
            intent.putExtra("hideStatusBar", false);
        } else if (id == R.id.follow_user) {
            intent = new Intent(mContext, TemplateActivity.class);
            intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, TemplateRoute.MY_FOLLOWING.key);
            intent.putExtra("hideStatusBar", false);
        } else if (id == R.id.nav_current_hot) {
            intent = new Intent(mContext, TemplateActivity.class);
            intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, TemplateRoute.RECENT_RECOMMEND.key);
        } else if (id == R.id.nav_site_recommend) {
            intent = new Intent(mContext, TemplateActivity.class);
            intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, TemplateRoute.SITE_RECOMMEND.key);
        } else if (id == R.id.nav_event_history) {
            intent = new Intent(mContext, TemplateActivity.class);
            intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, TemplateRoute.EVENT_HISTORY.key);
        } else if (id == R.id.nav_local_novel) {
            intent = new Intent(mContext, TemplateActivity.class);
            intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, TemplateRoute.LOCAL_NOVEL_LIBRARY.key);
        } else if (id == R.id.nav_debug_bulk_dl) {
            intent = new Intent(mContext, TemplateActivity.class);
            intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, TemplateRoute.DEBUG_BULK_DOWNLOAD.key);
        } else if (id == R.id.nav_saf_perf_test) {
            intent = new Intent(mContext, TemplateActivity.class);
            intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, TemplateRoute.DEBUG_SAF_PERF.key);
        } else if (id == R.id.nav_network_test) {
            intent = new Intent(mContext, TemplateActivity.class);
            intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, TemplateRoute.DEBUG_NETWORK_TEST.key);
        } else if (id == R.id.nav_tag_popular_export) {
            intent = new Intent(mContext, TemplateActivity.class);
            intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, TemplateRoute.DEBUG_POPULAR_TAG_EXPORT.key);
        } else if (id == R.id.nav_web_home) {
            intent = new Intent(mContext, TemplateActivity.class);
            intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, TemplateRoute.WEB_HOME.key);
        } else if (id == R.id.nav_fanbox) {
            intent = new Intent(mContext, TemplateActivity.class);
            if (ceui.pixiv.ui.fanbox.FanboxHomeFeedKt.hasFanboxSession()) {
                // 有 FANBOXSESSID 才进原生首页 —— post.listHome 未登录一律 401,
                // 直接进去只会看到一屏错误态。
                intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, TemplateRoute.FANBOX_HOME.key);
            } else {
                // 没登录过:送去网页版登录。cookie 存在 WebView 的 CookieManager 里,
                // 登完下次点进来 hasFanboxSession() 就为真,自动走原生。
                // 走「网页链接」这条现成路由(FragmentWebView,带标准 toolbar),
                // 不是 WebFragment —— 后者是个没有 toolbar 的裸 WebView,外观对不上。
                intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, TemplateRoute.WEB_LINK.key);
                intent.putExtra(Params.URL, "https://www.fanbox.cc/");
                intent.putExtra(Params.TITLE, getString(R.string.fanbox_entry));
                intent.putExtra(Params.PREFER_PRESERVE, true);
            }
        } else if (id == R.id.nav_chat_room) {
            intent = new Intent(mContext, TemplateActivity.class);
            intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, TemplateRoute.CHAT.key);
        } else if (id == R.id.nav_plaza) {
            intent = new Intent(mContext, TemplateActivity.class);
            intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, TemplateRoute.PLAZA.key);
        }
        if (intent != null) {
            // 当前最热 / 本月收藏 / 操作记录:服务端聚合内容可能含 R-18,进去前过一次警示框
            // (「坚持查看」点一次后全局不再弹)。其它入口照常直接进。
            if (id == R.id.nav_current_hot || id == R.id.nav_site_recommend || id == R.id.nav_event_history) {
                final Intent gated = intent;
                ceui.pixiv.ui.recommend.SensitiveContentGate.gateOrProceed(this, () -> startActivity(gated));
            } else {
                startActivity(intent);
            }
        }
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.clear();
    }

    private void selectPhoto() {
        new WitDialog.CheckableDialogBuilder(mActivity)
                .addItems(ALL_SELECT_WAY, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        if (which == 0) {
                            Intent intentToPickPic = new Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
                            intentToPickPic.setDataAndType(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "image/*");
                            startActivityForResult(intentToPickPic, Params.REQUEST_CODE_CHOOSE);
                        } else {
                            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                            intent.addCategory(Intent.CATEGORY_OPENABLE);//必须
                            intent.setType("image/*");//必须
                            startActivityForResult(intent, Params.REQUEST_CODE_CHOOSE);
                        }
                        dialog.dismiss();
                    }
                })
                .show();
    }

    private void openMyUserPage() {
        Intent userIntent = new Intent(mContext, UActivity.class);
        userIntent.putExtra(Params.USER_ID, (long) SessionManager.INSTANCE.getLoggedInUid());
        startActivity(userIntent);
        baseBind.drawerLayout.closeDrawer(GravityCompat.START);
    }

    private void initDrawerHeader() {
        if (SessionManager.INSTANCE.isLoggedIn() && SessionManager.INSTANCE.getLoggedInUser() != null) {
            Glide.with(mContext)
                    .load(GlideUtil.getHead(SessionManager.INSTANCE.getLoggedInUser()))
                    .into(baseBind.userHead);
            baseBind.userName.setText(SessionManager.INSTANCE.getLoggedInUser().getName());
            String mailAddress = SessionManager.INSTANCE.getMailAddress();
            baseBind.userEmail.setText(TextUtils.isEmpty(mailAddress) ?
                    mContext.getString(R.string.no_mail_address) : mailAddress);
        }
        bindPlanBadge();
    }

    /**
     * 侧边栏的订阅徽章。免费用户什么都不显示 —— 没订阅的人这一栏应该和加这个功能之前
     * 一模一样，不该多出一块空白或者一个「免费」标签。
     *
     * 读的是冷启动缓存的档位（{@link RemoteAppConfig}），所以抽屉第一次拉开就有答案，
     * 不等网络。刚买完的人要么等下次冷启动、要么进一趟用量页 —— 那页会拿额度接口返回的
     * 最新档位回写缓存，回来抽屉就更新了。
     *
     * 认的是「他买了什么」而不是「按什么计量」：试运营期间服务端把所有人抬到 Max，
     * 拿计量档位去显示会给每个没付钱的人发一颗 MAX 徽章。
     */
    private void bindPlanBadge() {
        if (ceui.lisa.BuildConfig.IS_LITE) {
            baseBind.userPlanBadge.setVisibility(View.GONE);
            return;
        }
        Nana7miPlan plan = ((ServicesProvider) getApplication()).getRemoteAppConfig().getNana7miPlan();
        String label = plan == null ? null : plan.getBadgeLabel();
        if (label == null) {
            baseBind.userPlanBadge.setVisibility(View.GONE);
        } else {
            baseBind.userPlanBadge.setText(label);
            baseBind.userPlanBadge.setVisibility(View.VISIBLE);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == Params.REQUEST_CODE_CHOOSE && resultCode == RESULT_OK) {
            Uri imageUri = data == null ? null : data.getData();
            if (imageUri != null) {
                ReverseImage.searchFrom(this, imageUri, ReverseImage.DEFAULT_ENGINE, null);
            }
        }
    }

    public void exit() {
        if ((System.currentTimeMillis() - mExitTime) > 2000) {
            if (Manager.get().getContent().size() != 0) {
                new WitDialog.MessageDialogBuilder(mContext)
                        .setTitle(getString(R.string.shaft_hint))
                        .setMessage(mContext.getString(R.string.you_have_download_plan))
                        .addAction(R.string.cancel, (d, i) -> d.dismiss())
                        .addAction(0, R.string.see_download_task, WitDialogAction.ACTION_PROP_NEUTRAL, (d, i) -> {
                            Intent intent = new Intent(mContext, TemplateActivity.class);
                            intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, TemplateRoute.DOWNLOAD_MANAGER.key);
                            intent.putExtra("hideStatusBar", true);
                            startActivity(intent);
                            d.dismiss();
                        })
                        .addAction(0, R.string.sure, WitDialogAction.ACTION_PROP_NEGATIVE, (d, i) -> {
                            Manager.get().stopAll();
                            finish();
                        })
                        .show();
            } else {
                Common.showToast(getString(R.string.double_click_finish));
                mExitTime = System.currentTimeMillis();
                refreshMainBackCallback();
                baseBind.getRoot().postDelayed(this::refreshMainBackCallback, EXIT_WINDOW_MS + 100);
            }
        } else {
            // 2 秒窗口内 callback 已关闭,正常情况下返回直接由系统处理(launcher 根 Activity 被移到后台
            // 并播回桌面动画);这里只兜第一次按返回后抽屉又被打开再关掉之类的边角。
            finish();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 回到前台时静默拉一次自己的资料(去抖 + 失败静默),在站外换头像后也能自动更新;
        // 侧边栏账号区由 loggedInAccount 观察者负责重绑。
        SessionManager.INSTANCE.syncLoggedInProfileIfNeeded();
        // 发现入口(画像)/ 聊天室 / 广场开关可能在别的页面变化,回来时重建抽屉
        buildDrawerMenu();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        LocalBroadcastManager.getInstance(this).unregisterReceiver(profileReadyReceiver);
    }

    @Override
    public void finish() {
        int currentPosition = baseBind.viewPager.getCurrentItem();
        Shaft.getMMKV().putInt(Params.MAIN_ACTIVITY_NAVIGATION_POSITION, currentPosition);
        super.finish();
    }

    private int getNavigationInitPosition() {
        int defaultPosition = 0;
        String settingValue = Shaft.sSettings.getNavigationInitPosition();
        if (settingValue.equals(NavigationLocationHelper.LATEST)) {
            int latestPosition = Shaft.getMMKV().getInt(Params.MAIN_ACTIVITY_NAVIGATION_POSITION, 0);
            return latestPosition < baseFragments.length ? latestPosition : defaultPosition;
        }
        NavigationLocationHelper.NavigationItem navigationValue = NavigationLocationHelper.NAVIGATION_MAP.getOrDefault(settingValue, null);
        if (navigationValue == null) {
            return defaultPosition;
        }
        Class clazz = navigationValue.getInstanceClass();
        for (int i = 0; i < baseFragments.length; i++) {
            Fragment fragment = baseFragments[i];
            if (clazz == fragment.getClass()) {
                return i;
            }
        }
        return defaultPosition;
    }
}
