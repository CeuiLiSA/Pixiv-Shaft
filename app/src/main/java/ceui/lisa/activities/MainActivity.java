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
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.splashscreen.SplashScreen;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentPagerAdapter;
import androidx.viewpager.widget.ViewPager;

import com.blankj.utilcode.util.BarUtils;
import com.bumptech.glide.Glide;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.qmuiteam.qmui.widget.dialog.QMUIDialog;
import com.qmuiteam.qmui.widget.dialog.QMUIDialogAction;


import ceui.lisa.R;
import ceui.lisa.core.Manager;
import ceui.lisa.databinding.ActivityCoverBinding;
import ceui.lisa.fragments.FragmentCenter;
import ceui.lisa.fragments.FragmentLeft;
import ceui.lisa.fragments.FragmentRight;
import ceui.lisa.fragments.FragmentViewPager;
import ceui.pixiv.ui.me.MeFragment;
import ceui.lisa.helper.DrawerLayoutHelper;
import ceui.lisa.helper.NavigationLocationHelper;
import ceui.lisa.utils.Common;
import ceui.lisa.utils.Dev;
import ceui.lisa.utils.GlideUtil;
import ceui.lisa.utils.Params;
import ceui.lisa.utils.ReverseImage;
import ceui.lisa.view.DrawerLayoutViewPager;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import ceui.pixiv.session.SessionManager;
import ceui.pixiv.ui.navigation.DrawerIconCatalog;

/**
 * 主页
 */
public class MainActivity extends BaseActivity<ActivityCoverBinding> {

    public static final String[] ALL_SELECT_WAY = new String[]{"图库选图", "文件管理器选图"};
    private long mExitTime;
    private Fragment[] baseFragments = null;

    /**
     * 开屏动画安全兜底超时：万一首页推荐插画 tab 没能按预期跑到（异常 / 未来改了默认
     * tab），也不能让开屏永久卡住——超时后强制放行，最坏情况退化回「开屏消失后闪一帧
     * 常规 loading」，而不是白屏假死。ColdStartSplashGate 本身没有超时保护，靠这里兜底。
     */
    private static final long SPLASH_SAFETY_TIMEOUT_MS = 1200L;

    private final android.content.BroadcastReceiver profileReadyReceiver = new android.content.BroadcastReceiver() {
        @Override
        public void onReceive(android.content.Context context, Intent intent) {
            android.util.Log.d("Discovery/Gate", "received PROFILE_READY broadcast");
            buildDrawerMenu();
        }
    };

    /**
     * installSplashScreen 必须在 super.onCreate 之前调用（AndroidX SplashScreen 契约）。
     * keepOnScreenCondition 只等 ColdStartSplashGate（首页推荐插画 tab 的本地优先裁决），
     * 不等网络；安全超时兜底见 SPLASH_SAFETY_TIMEOUT_MS。
     *
     * 只有这次冷启动真的会落在首页推荐插画 tab（getNavigationInitPosition()==0）时，
     * 才值得等：用户设置了「启动到最近使用的页」或把默认 tab 指到别处时，
     * RecmdIllustFeedFragment(插画) 根本不会被创建，ColdStartSplashGate 永远等不到
     * Fragment 那边的信号，只能靠安全超时兜底——那样每次冷启动都白等满 1200ms，
     * 比完全不做这个功能还差。落在其它 tab 时直接放行，不占这些用户便宜。
     * getNavigationInitPosition() 依赖 initView() 建好的 baseFragments，必须放在
     * super.onCreate() 之后读；initView() 提前异常导致 baseFragments 仍为 null 时
     * 同样直接放行，不让一次初始化失败连带把开屏焊死。
     */
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        ColdStartSplashGate.reset();
        SplashScreen splashScreen = SplashScreen.installSplashScreen(this);
        splashScreen.setKeepOnScreenCondition(() -> !ColdStartSplashGate.isResolved());
        super.onCreate(savedInstanceState);
        if (baseFragments == null || getNavigationInitPosition() != 0) {
            ColdStartSplashGate.markResolved();
        } else {
            new Handler(Looper.getMainLooper())
                    .postDelayed(ColdStartSplashGate::markResolved, SPLASH_SAFETY_TIMEOUT_MS);
        }
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

        initDrawerHeader();
        baseBind.drawerHeader.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent userIntent = new Intent(mContext, UActivity.class);
                userIntent.putExtra(Params.USER_ID, (int) SessionManager.INSTANCE.getLoggedInUid());
                startActivity(userIntent);
                baseBind.drawerLayout.closeDrawer(GravityCompat.START);
            }
        });
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
                if (item.getItemId() == R.id.action_1) {
                    baseBind.viewPager.setCurrentItem(0);
                    return true;
                } else if (item.getItemId() == R.id.action_2) {
                    baseBind.viewPager.setCurrentItem(1);
                    return true;
                } else if (item.getItemId() == R.id.action_3) {
                    baseBind.viewPager.setCurrentItem(2);
                    return true;
                } else if (item.getItemId() == R.id.action_4) {
                    baseBind.viewPager.setCurrentItem(3);
                    return true;
                } else if (item.getItemId() == R.id.action_5) {
                    // 「我」始终在最末位:非 R18 模式 = 3,R18 模式 = 4
                    baseBind.viewPager.setCurrentItem(baseFragments.length - 1);
                    return true;
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
                if (position == 0) {
                    baseBind.navigationView.setSelectedItemId(R.id.action_1);
                } else if (position == 1) {
                    baseBind.navigationView.setSelectedItemId(R.id.action_2);
                } else if (position == 2) {
                    baseBind.navigationView.setSelectedItemId(R.id.action_3);
                } else if (position == 3) {
                    // 非 R18 模式 position 3 就是「我」(action_4 在该菜单不存在);R18 模式 position 3 才是 R18 (action_4)
                    boolean isR18Tab = baseFragments[3] instanceof FragmentViewPager;
                    baseBind.navigationView.setSelectedItemId(isR18Tab ? R.id.action_4 : R.id.action_5);
                } else if (position == 4) {
                    // 仅 R18 模式存在 position 4 = 「我」
                    baseBind.navigationView.setSelectedItemId(R.id.action_5);
                }
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

        // 返回键/返回手势:抽屉开着先关抽屉,否则走双击退出。
        // targetSdk 35+ 后预测式返回默认开启,系统不再回调 onKeyDown(KEYCODE_BACK),
        // 必须用 OnBackPressedDispatcher 接管。
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (baseBind.drawerLayout.isDrawerOpen(GravityCompat.START)) {
                    baseBind.drawerLayout.closeDrawer(GravityCompat.START);
                } else {
                    exit();
                }
            }
        });
    }

    private void initFragment() {
        boolean showMeTab = Dev.showMeTab;
        if (Shaft.sSettings.isMainViewR18()) {
            baseBind.navigationView.inflateMenu(R.menu.main_activity0_with_r18);
            if (showMeTab) {
                baseFragments = new Fragment[]{
                        new FragmentLeft(),
                        new FragmentCenter(),
                        new FragmentRight(),
                        FragmentViewPager.newInstance(Params.VIEW_PAGER_R18),
                        new MeFragment(),
                };
            } else {
                baseFragments = new Fragment[]{
                        new FragmentLeft(),
                        new FragmentCenter(),
                        new FragmentRight(),
                        FragmentViewPager.newInstance(Params.VIEW_PAGER_R18),
                };
            }
        } else {
            baseBind.navigationView.inflateMenu(R.menu.main_activity0);
            if (showMeTab) {
                baseFragments = new Fragment[]{
                        new FragmentLeft(),
                        new FragmentCenter(),
                        new FragmentRight(),
                        new MeFragment(),
                };
            } else {
                baseFragments = new Fragment[]{
                        new FragmentLeft(),
                        new FragmentCenter(),
                        new FragmentRight(),
                };
            }
        }
        if (!showMeTab) {
            baseBind.navigationView.getMenu().removeItem(R.id.action_5);
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
            Intent intent = new Intent(mContext, TemplateActivity.class);
            intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "登录注册");
            startActivity(intent);
            finish();
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

        DrawerEntry(int id, int titleRes, boolean visible) {
            this.id = id;
            this.titleRes = titleRes;
            this.visible = visible;
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

        ceui.pixiv.db.discovery.UserProfile profile = ceui.pixiv.db.discovery.ProfileManager.INSTANCE.cached();
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
                new DrawerEntry(R.id.nav_pinned_tags, R.string.pinned_tags),
                // 精华列:各处「收藏到精华」写进 feature_table 的本地列表快照。c3f08172 侧栏
                // 「发现」分组瘦身时被连带删掉,但它不属于搬进「发现」tab 的那批(最新/热度标签/
                // 特辑/本月收藏/当前最热),页面和 handler 一直都在——只是没入口,存了看不了。
                new DrawerEntry(R.id.nav_feature, R.string.string_248),
                new DrawerEntry(R.id.watchlist, R.string.watchlist),
                new DrawerEntry(R.id.novel_markers, R.string.core_string_novel_marker),
                new DrawerEntry(R.id.follow_user, R.string.string_321),
                new DrawerEntry(R.id.nav_fans, R.string.string_322),
        });

        // 高频入口前置:浏览历史 排在「记录与管理」首位,设置 排在「其他」首位。
        addDrawerSection(sections, R.string.drawer_section_records, new DrawerEntry[]{
                new DrawerEntry(nav_slideshow, R.string.view_history),
                new DrawerEntry(nav_gallery, R.string.download_manager),
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
                // 筛画师作品都指着它 —— 没入口等于那些功能对普通用户是死的。渠道划分对齐
                // FragmentCenter 的同名 chip:Lite 不出现。
                new DrawerEntry(R.id.nav_web_home, R.string.street_title, experimentalAllowed),
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
            intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "下载管理");
            intent.putExtra("hideStatusBar", false);
        } else if (id == nav_slideshow) {
            intent = new Intent(mContext, TemplateActivity.class);
            intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "浏览记录");
        } else if (id == R.id.watch_later) {
            intent = new Intent(mContext, TemplateActivity.class);
            intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "稍后再看");
        } else if (id == R.id.nav_notifications) {
            intent = new Intent(mContext, TemplateActivity.class);
            intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "通知中心");
        } else if (id == R.id.nav_manage) {
            intent = new Intent(mContext, TemplateActivity.class);
            intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "设置");
        } else if (id == R.id.nav_prime_tags) {
            intent = new Intent(mContext, TemplateActivity.class);
            intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "PrimeTagsList");
        } else if (id == R.id.nav_pinned_tags) {
            intent = new Intent(mContext, TemplateActivity.class);
            intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "PinnedTagsList");
        } else if (id == R.id.nav_discovery) {
            intent = new Intent(mContext, TemplateActivity.class);
            intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "发现");
        } else if (id == R.id.nav_share) {
            intent = new Intent(mContext, TemplateActivity.class);
            intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "关于软件");
        } else if (id == R.id.main_page) {
            intent = new Intent(mContext, UActivity.class);
            intent.putExtra(Params.USER_ID, (int) SessionManager.INSTANCE.getLoggedInUid());
        } else if (id == R.id.nav_ai_upscale) {
            intent = new Intent(mContext, TemplateActivity.class);
            intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "AI画质提升");
        } else if (id == R.id.nav_reverse) {
            selectPhoto();
        } else if (id == R.id.nav_new_work) {
            intent = new Intent(mContext, TemplateActivity.class);
            intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "最新作品");
            intent.putExtra("hideStatusBar", false);
        } else if (id == R.id.muted_list) {
            intent = new Intent(mContext, TemplateActivity.class);
            intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "标签屏蔽记录");
        } else if (id == R.id.nav_feature) {
            intent = new Intent(mContext, TemplateActivity.class);
            intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "精华列");
        } else if (id == R.id.nav_fans) {
            intent = new Intent(mContext, TemplateActivity.class);
            intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "粉丝");
        } else if (id == R.id.illust_star) {
            intent = new Intent(mContext, TemplateActivity.class);
            intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "我的插画收藏");
            intent.putExtra("hideStatusBar", false);
        } else if (id == R.id.novel_star) {
            intent = new Intent(mContext, TemplateActivity.class);
            intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "我的小说收藏");
            intent.putExtra("hideStatusBar", false);
        } else if (id == R.id.watchlist) {
            intent = new Intent(mContext, TemplateActivity.class);
            intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "追更列表");
            intent.putExtra("hideStatusBar", false);
        } else if (id == R.id.novel_markers) {
            intent = new Intent(mContext, TemplateActivity.class);
            intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "小说书签");
            intent.putExtra("hideStatusBar", false);
        } else if (id == R.id.follow_user) {
            intent = new Intent(mContext, TemplateActivity.class);
            intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "我的关注");
            intent.putExtra("hideStatusBar", false);
        } else if (id == R.id.nav_current_hot) {
            intent = new Intent(mContext, TemplateActivity.class);
            intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "当前最热");
        } else if (id == R.id.nav_site_recommend) {
            intent = new Intent(mContext, TemplateActivity.class);
            intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "站长推荐");
        } else if (id == R.id.nav_event_history) {
            intent = new Intent(mContext, TemplateActivity.class);
            intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "操作记录");
        } else if (id == R.id.nav_local_novel) {
            intent = new Intent(mContext, TemplateActivity.class);
            intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "本地小说库");
        } else if (id == R.id.nav_debug_bulk_dl) {
            intent = new Intent(mContext, TemplateActivity.class);
            intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "批量下载Debug");
        } else if (id == R.id.nav_saf_perf_test) {
            intent = new Intent(mContext, TemplateActivity.class);
            intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "SAF写入压测");
        } else if (id == R.id.nav_network_test) {
            intent = new Intent(mContext, TemplateActivity.class);
            intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "网络测试");
        } else if (id == R.id.nav_tag_popular_export) {
            intent = new Intent(mContext, TemplateActivity.class);
            intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "标签热度导出");
        } else if (id == R.id.nav_web_home) {
            intent = new Intent(mContext, TemplateActivity.class);
            intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "Web首页");
        } else if (id == R.id.nav_chat_room) {
            intent = new Intent(mContext, TemplateActivity.class);
            intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "聊天室");
        } else if (id == R.id.nav_plaza) {
            intent = new Intent(mContext, TemplateActivity.class);
            intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "广场");
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
        new QMUIDialog.CheckableDialogBuilder(mActivity)
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
                new QMUIDialog.MessageDialogBuilder(mContext)
                        .setTitle(getString(R.string.shaft_hint))
                        .setMessage(mContext.getString(R.string.you_have_download_plan))
                        .addAction(R.string.cancel, (d, i) -> d.dismiss())
                        .addAction(0, R.string.see_download_task, QMUIDialogAction.ACTION_PROP_NEUTRAL, (d, i) -> {
                            Intent intent = new Intent(mContext, TemplateActivity.class);
                            intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "下载管理");
                            intent.putExtra("hideStatusBar", true);
                            startActivity(intent);
                            d.dismiss();
                        })
                        .addAction(0, R.string.sure, QMUIDialogAction.ACTION_PROP_NEGATIVE, (d, i) -> {
                            Manager.get().stopAll();
                            finish();
                        })
                        .show();
            } else {
                Common.showToast(getString(R.string.double_click_finish));
                mExitTime = System.currentTimeMillis();
            }
        } else {
            finish();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (Dev.refreshUser) {
            initDrawerHeader();
            Dev.refreshUser = false;
        }
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
