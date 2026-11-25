package ceui.lisa.fragments;

import android.animation.ValueAnimator;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.Toolbar;
import androidx.coordinatorlayout.widget.CoordinatorLayout;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.ColorUtils;
import androidx.core.graphics.drawable.DrawableCompat;
import androidx.core.view.GravityCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentPagerAdapter;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager.widget.ViewPager;

import com.google.android.material.appbar.AppBarLayout;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

import ceui.lisa.R;
import ceui.lisa.activities.MainActivity;
import ceui.lisa.activities.Shaft;
import ceui.lisa.activities.TemplateActivity;
import ceui.lisa.databinding.FragmentLeftBinding;
import ceui.lisa.utils.Common;
import ceui.lisa.utils.DensityUtil;
import ceui.lisa.utils.Dev;
import ceui.lisa.utils.MyOnTabSelectedListener;
import ceui.lisa.utils.Params;
import ceui.lisa.utils.SystemBarMetrics;
import ceui.lisa.view.OnCheckChangeListener;
import ceui.pixiv.feeds.FeedFragment;
import ceui.pixiv.ui.home.RecmdIllustFeedFragment;
import ceui.pixiv.ui.navigation.DrawerAvatarHelper;
import ceui.pixiv.ui.navigation.HomeShellHost;
import ceui.pixiv.ui.navigation.TemplateRoute;
import ceui.pixiv.ui.trending.HotTagsFeedFragment;
import ceui.pixiv.witstudio.theme.V3Palette;
import ceui.pixiv.witstudio.widget.WitLiquidGlassQuality;
import ceui.pixiv.witstudio.widget.WitLiquidGlassTabBar;
import ceui.pixiv.witstudio.widget.WitScrollEdgeBlurView;

public class FragmentLeft extends BaseLazyFragment<FragmentLeftBinding> {

    private static final long FLOATING_SNAP_DURATION_MS = 160L;

    private Fragment[] mFragments = null;
    private int statusBarHeightPx = 0;
    private boolean railModeShown = false;
    private float floatingCollapsePx = 0f;
    private int currentTopInsetPx = 0;
    private String lastChromeStyleKey = null;
    private final Rect appBarClipRect = new Rect();
    private ValueAnimator floatingCollapseAnimator = null;
    @Nullable
    private WitLiquidGlassTabBar liquidGlassTabBar = null;
    private final Set<RecyclerView> observedRecyclerViews =
            Collections.newSetFromMap(new WeakHashMap<>());
    private FragmentManager.FragmentLifecycleCallbacks childFeedLifecycleCallbacks = null;
    private final RecyclerView.OnScrollListener feedScrollListener =
            new RecyclerView.OnScrollListener() {
                @Override
                public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                    if (!isSoftEdgeFloatingMode() || recyclerView != getCurrentTabRecyclerView()) {
                        return;
                    }
                    if (isFloatingCollapseActive()) {
                        int toolbarH = baseBind.toolbar.getHeight();
                        if (toolbarH > 0) {
                            int maxCollapse = computeScrolledFromTop(recyclerView, toolbarH);
                            if (dy != 0) {
                                cancelFloatingCollapseAnimator();
                                float next = floatingCollapsePx - dy;
                                applyFloatingCollapse(clampFloat(next, -maxCollapse, 0f));
                            } else if (floatingCollapsePx < -maxCollapse) {
                                cancelFloatingCollapseAnimator();
                                applyFloatingCollapse(-maxCollapse);
                            }
                        }
                    }
                    syncScrollEdgeActivation(recyclerView);
                }

                @Override
                public void onScrollStateChanged(@NonNull RecyclerView recyclerView, int newState) {
                    if (!isFloatingCollapseActive() || recyclerView != getCurrentTabRecyclerView()) {
                        return;
                    }
                    if (newState == RecyclerView.SCROLL_STATE_DRAGGING) {
                        cancelFloatingCollapseAnimator();
                    } else if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                        snapFloatingCollapseIfNeeded(recyclerView);
                    }
                }
            };

    @Override
    public void initLayout() {
        mLayoutID = R.layout.fragment_left;
    }

    @NonNull
    @Override
    protected FragmentLeftBinding bindView(@NonNull View root) {
        return FragmentLeftBinding.bind(root);
    }

    @Override
    public void initView() {
        if (Dev.hideMainActivityStatus) {
            statusBarHeightPx = SystemBarMetrics.statusBarHeight(mContext);
            ViewGroup.LayoutParams headParams = baseBind.head.getLayoutParams();
            headParams.height = statusBarHeightPx;
            baseBind.head.setLayoutParams(headParams);
            setTopMargin(baseBind.appBar, statusBarHeightPx);
            setTopMargin(baseBind.wideHeader, statusBarHeightPx);
        }

        baseBind.toolbar.setNavigationOnClickListener(v -> {
            if (mActivity instanceof MainActivity) {
                ((MainActivity) mActivity).getDrawer().openDrawer(GravityCompat.START, true);
            }
        });
        baseBind.toolbarTitle.setText(R.string.string_207);
        baseBind.toolbar.inflateMenu(R.menu.fragment_left);
        baseBind.toolbar.setOnMenuItemClickListener(new Toolbar.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(MenuItem item) {
                if (item.getItemId() == R.id.action_search) {
                    openSearch();
                    return true;
                }
                return false;
            }
        });
        baseBind.wideSearch.setOnClickListener(v -> openSearch());
        liquidGlassTabBar = WitLiquidGlassTabBar.attach(baseBind.tabGlassPlatter, baseBind.tabLayout);
        baseBind.appBar.addOnOffsetChangedListener((appBarLayout, verticalOffset) -> {
            if (!isSoftEdgeFloatingMode() && !railModeShown) {
                syncTabGlassPosition();
            }
        });
        baseBind.appBar.addOnLayoutChangeListener(
                (v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
                    if ((bottom - top) != (oldBottom - oldTop)
                            || (right - left) != (oldRight - oldLeft)
                            || top != oldTop) {
                        syncTopEdgeMetrics();
                    }
                });
        baseBind.tabGlassPlatter.addOnLayoutChangeListener(
                (v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
                    int h = bottom - top;
                    if (h > 0 && h != (oldBottom - oldTop)) {
                        ViewGroup.LayoutParams sp = baseBind.tabSpacer.getLayoutParams();
                        if (sp.height != h) {
                            sp.height = h;
                            baseBind.tabSpacer.setLayoutParams(sp);
                        }
                        syncTopEdgeMetrics();
                    }
                });
        baseBind.wideHeader.addOnLayoutChangeListener(
                (v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
                    if ((bottom - top) != (oldBottom - oldTop)) {
                        syncTopEdgeMetrics();
                    }
                });
        HomeShellHost.observe(this, this::applyRailMode);
        DrawerAvatarHelper.bindToolbar(baseBind.toolbar, getViewLifecycleOwner());
        applyTopBarVisualStyle();
        applyTopBarScrollSetting();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        // BaseFragment 复用缓存 rootView 时不会重跑 initView()，必须在与 onDestroyView 成对的
        // onViewCreated 中挂回子列表滚动监听与液态玻璃 TabBar，避免视图重建/复附着后联动失效。
        if (liquidGlassTabBar == null && baseBind != null) {
            liquidGlassTabBar = WitLiquidGlassTabBar.attach(baseBind.tabGlassPlatter, baseBind.tabLayout);
            lastChromeStyleKey = null;
            applyTopBarVisualStyle();
        }
        installChildFeedScrollObserver();
    }

    @Override
    public void onResume() {
        super.onResume();
        // 「顶栏跟随滚动收起」与「液态玻璃效果等级」可能刚在设置里切过，回到首页即时生效，不用重启
        DrawerAvatarHelper.applyToToolbar(baseBind.toolbar);
        applyTopBarVisualStyle();
        applyTopBarScrollSetting();
    }

    @Override
    public void onDestroyView() {
        cancelFloatingCollapseAnimator();
        uninstallChildFeedScrollObserver();
        if (liquidGlassTabBar != null) {
            liquidGlassTabBar.detach();
            liquidGlassTabBar = null;
        }
        lastChromeStyleKey = null;
        super.onDestroyView();
    }

    private void openSearch() {
        Intent intent = new Intent(mContext, TemplateActivity.class);
        intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, TemplateRoute.SEARCH.key);
        startActivity(intent);
    }

    /**
     * 宽窗口（首页显示侧边导航栏）用 V3 页头，手机排版按「液态玻璃效果等级」二选一：
     * - 标准 / 极致：悬浮透明顶栏 + iOS 26 UIScrollEdgeEffect.Style.soft 顶部渐进模糊，作品流直接侵入顶栏与状态栏后方；
     * - 无：保留经典主题色实底 Toolbar + TabLayout。
     */
    private void applyRailMode(boolean railShown) {
        railModeShown = railShown;
        baseBind.toolbar.setVisibility(railShown ? View.GONE : View.VISIBLE);
        baseBind.tabSpacer.setVisibility(railShown ? View.GONE : View.VISIBLE);
        baseBind.tabGlassPlatter.setVisibility(railShown ? View.GONE : View.VISIBLE);
        baseBind.wideHeader.setVisibility(railShown ? View.VISIBLE : View.GONE);
        if (railShown) {
            resetFloatingCollapse();
            baseBind.appBar.setExpanded(true, false);
        }
        lastChromeStyleKey = null;
        applyTopBarVisualStyle();
        applyTopBarScrollSetting();
    }

    private boolean isSoftEdgeFloatingMode() {
        if (railModeShown) {
            return true;
        }
        WitLiquidGlassQuality quality =
                WitLiquidGlassQuality.fromOrdinal(Shaft.sSettings.getLiquidGlassQuality());
        return quality.isLiveEnabled();
    }

    private boolean isFloatingCollapseActive() {
        return baseBind != null
                && !railModeShown
                && isSoftEdgeFloatingMode()
                && Shaft.sSettings.isTopBarHideOnScroll();
    }

    private void applyTopBarVisualStyle() {
        if (baseBind == null) {
            return;
        }
        WitLiquidGlassQuality quality =
                WitLiquidGlassQuality.fromOrdinal(Shaft.sSettings.getLiquidGlassQuality());
        boolean softEdge = isSoftEdgeFloatingMode();
        V3Palette palette = V3Palette.from(mContext);
        boolean useAvatar = Shaft.sSettings.isUseAccountAvatarAsDrawerIcon();
        String key = railModeShown + "|" + quality.ordinal() + "|" + softEdge + "|"
                + palette.isDark() + "|" + palette.getPrimary() + "|" + useAvatar;
        if (key.equals(lastChromeStyleKey)) {
            syncTopEdgeMetrics();
            return;
        }
        lastChromeStyleKey = key;

        baseBind.glassHost.setQuality(softEdge ? quality : WitLiquidGlassQuality.NONE);
        baseBind.topScrollEdge.setScrimColor(ContextCompat.getColor(mContext, R.color.v3_bg));
        baseBind.topScrollEdge.setVisibility(softEdge ? View.VISIBLE : View.GONE);

        CoordinatorLayout.LayoutParams appBarLp =
                (CoordinatorLayout.LayoutParams) baseBind.appBar.getLayoutParams();
        CoordinatorLayout.LayoutParams pagerLp =
                (CoordinatorLayout.LayoutParams) baseBind.viewPager.getLayoutParams();
        CoordinatorLayout.LayoutParams tabPlatterLp =
                (CoordinatorLayout.LayoutParams) baseBind.tabGlassPlatter.getLayoutParams();
        ViewGroup.LayoutParams tabInnerLp = baseBind.tabLayout.getLayoutParams();
        if (softEdge) {
            // 切进悬浮态前把经典 AppBarLayout.Behavior 的内部 topOffset 归零，再换成空 Behavior：
            // 避免 AppBarLayout.Behavior 在 onNestedPreScroll 抢走列表前 50dp 滚动导致顶栏单独上移、列表卡住。
            if (appBarLp.getBehavior() instanceof AppBarLayout.Behavior) {
                ((AppBarLayout.Behavior) appBarLp.getBehavior()).setTopAndBottomOffset(0);
            }
            if (!(appBarLp.getBehavior() instanceof FloatingPassthroughBehavior)) {
                appBarLp.setBehavior(new FloatingPassthroughBehavior());
                baseBind.appBar.setLayoutParams(appBarLp);
            }
            if (pagerLp.getBehavior() != null) {
                pagerLp.setBehavior(null);
                baseBind.viewPager.setLayoutParams(pagerLp);
            }
            baseBind.head.setBackgroundColor(Color.TRANSPARENT);
            baseBind.appBar.setBackgroundColor(Color.TRANSPARENT);
            baseBind.appBar.setElevation(0f);
            baseBind.appBar.setStateListAnimator(null);
            baseBind.toolbar.setBackgroundColor(Color.TRANSPARENT);
            baseBind.tabLayout.setBackgroundColor(Color.TRANSPARENT);

            int textPrimary = ContextCompat.getColor(mContext, R.color.v3_text_1);
            int selectedAccent = palette.isDark() && ColorUtils.calculateLuminance(palette.getPrimary()) < 0.35
                    ? palette.getTextAccent()
                    : palette.getPrimary();

            baseBind.toolbarTitle.setTextColor(textPrimary);
            WitScrollEdgeBlurView.applyFloatingTextLegibility(baseBind.toolbarTitle, palette.isDark(), true);
            tintToolbarIcons(textPrimary, !useAvatar);

            if (!railModeShown) {
                int marginH = DensityUtil.dp2px(WitLiquidGlassTabBar.DEFAULT_SCREEN_MARGIN_H_DP);
                if (tabPlatterLp.leftMargin != marginH || tabPlatterLp.rightMargin != marginH) {
                    tabPlatterLp.leftMargin = marginH;
                    tabPlatterLp.rightMargin = marginH;
                    baseBind.tabGlassPlatter.setLayoutParams(tabPlatterLp);
                }
                int innerH = DensityUtil.dp2px(42f);
                if (tabInnerLp.height != innerH) {
                    tabInnerLp.height = innerH;
                    baseBind.tabLayout.setLayoutParams(tabInnerLp);
                }
                baseBind.tabLayout.setPaddingRelative(0, 0, 0, 0);
                WitLiquidGlassTabBar.applyFloatingGlassChrome(
                        baseBind.tabGlassPlatter, quality, palette.isDark());
                baseBind.tabGlassPlatter.setPlatterAlpha(1f);

                int unselectedColor = palette.isDark()
                        ? ColorUtils.setAlphaComponent(Color.WHITE, 204)
                        : ColorUtils.setAlphaComponent(0xFF1A1A2E, 168);
                int indicatorFill = WitLiquidGlassTabBar.defaultRestingPillColor(palette.isDark());
                if (liquidGlassTabBar != null) {
                    liquidGlassTabBar.applyThemeColors(
                            selectedAccent, unselectedColor, indicatorFill, palette.isDark());
                    liquidGlassTabBar.setEnabled(true);
                    liquidGlassTabBar.syncSelection(false);
                }
            } else if (liquidGlassTabBar != null) {
                liquidGlassTabBar.setEnabled(false);
            }
        } else {
            resetFloatingCollapse();
            if (liquidGlassTabBar != null) {
                liquidGlassTabBar.setEnabled(false);
            }
            if (!(appBarLp.getBehavior() instanceof AppBarLayout.Behavior)) {
                appBarLp.setBehavior(new AppBarLayout.Behavior());
                baseBind.appBar.setLayoutParams(appBarLp);
            }
            if (!(pagerLp.getBehavior() instanceof StatusBarAwareScrollingBehavior)) {
                pagerLp.setBehavior(new StatusBarAwareScrollingBehavior(statusBarHeightPx));
                baseBind.viewPager.setLayoutParams(pagerLp);
            }
            if (tabPlatterLp.leftMargin != 0 || tabPlatterLp.rightMargin != 0) {
                tabPlatterLp.leftMargin = 0;
                tabPlatterLp.rightMargin = 0;
                baseBind.tabGlassPlatter.setLayoutParams(tabPlatterLp);
            }
            int classicTabH = DensityUtil.dp2px(48f);
            if (tabInnerLp.height != classicTabH) {
                tabInnerLp.height = classicTabH;
                baseBind.tabLayout.setLayoutParams(tabInnerLp);
            }
            baseBind.tabGlassPlatter.setQuality(WitLiquidGlassQuality.NONE);
            baseBind.tabGlassPlatter.setCornerRadiusDp(0f);
            baseBind.tabGlassPlatter.setTintColor(Color.TRANSPARENT);
            baseBind.tabGlassPlatter.setElevation(0f);
            baseBind.tabGlassPlatter.setPlatterAlpha(0f);
            baseBind.tabGlassPlatter.setPadding(0, 0, 0, 0);

            int primaryColor = Common.resolveThemeAttribute(mContext, androidx.appcompat.R.attr.colorPrimary);
            baseBind.head.setBackgroundColor(primaryColor);
            baseBind.appBar.setBackgroundColor(primaryColor);
            baseBind.appBar.setClipBounds(null);
            baseBind.toolbar.setBackgroundColor(Color.TRANSPARENT);
            baseBind.toolbar.setAlpha(1f);
            baseBind.tabLayout.setBackgroundColor(primaryColor);
            baseBind.tabLayout.setPaddingRelative(0, 0, 0, 0);

            int white = Color.WHITE;
            int unselected = ContextCompat.getColor(mContext, R.color.light_new_primary_color);
            WitScrollEdgeBlurView.applyFloatingTextLegibility(baseBind.toolbarTitle, palette.isDark(), false);
            baseBind.toolbarTitle.setTextColor(white);
            baseBind.tabLayout.setTabTextColors(unselected, white);
            baseBind.tabLayout.setSelectedTabIndicatorColor(white);
            tintToolbarIcons(white, !useAvatar);
        }
        syncTopEdgeMetrics();
    }

    private void tintToolbarIcons(int color, boolean tintNavIcon) {
        if (tintNavIcon) {
            Drawable nav = baseBind.toolbar.getNavigationIcon();
            if (nav != null) {
                Drawable wrapped = DrawableCompat.wrap(nav.mutate());
                DrawableCompat.setTint(wrapped, color);
                baseBind.toolbar.setNavigationIcon(wrapped);
            }
        }
        MenuItem searchItem = baseBind.toolbar.getMenu().findItem(R.id.action_search);
        if (searchItem != null && searchItem.getIcon() != null) {
            Drawable wrapped = DrawableCompat.wrap(searchItem.getIcon().mutate());
            DrawableCompat.setTint(wrapped, color);
            searchItem.setIcon(wrapped);
        }
    }

    private void syncTopEdgeMetrics() {
        if (baseBind == null) {
            return;
        }
        boolean softEdge = isSoftEdgeFloatingMode();
        if (!softEdge) {
            applyTopInsetToFeeds(0);
            syncTabGlassPosition();
            return;
        }
        int chromeHeight;
        if (railModeShown) {
            int wideH = baseBind.wideHeader.getHeight();
            chromeHeight = statusBarHeightPx + (wideH > 0 ? wideH : DensityUtil.dp2px(108f));
            baseBind.topScrollEdge.setAnchorHeightPx(chromeHeight);
            baseBind.topScrollEdge.setVisualAnchorHeightPx(-1);
        } else {
            int toolbarH = baseBind.toolbar.getHeight() > 0
                    ? baseBind.toolbar.getHeight()
                    : DensityUtil.dp2px(50f);
            int tabH = baseBind.tabGlassPlatter.getHeight() > 0
                    ? baseBind.tabGlassPlatter.getHeight()
                    : DensityUtil.dp2px(48f);
            chromeHeight = statusBarHeightPx + toolbarH + tabH + DensityUtil.dp2px(4f);
            // 顶部软边模糊仅覆盖状态栏 + 可见 Toolbar，TabBar 复用与底栏同款的 WitLiquidGlassTabBar 浮空液态玻璃胶囊。
            baseBind.topScrollEdge.setAnchorHeightPx(statusBarHeightPx + toolbarH);
            applyFloatingCollapse(isFloatingCollapseActive() ? floatingCollapsePx : 0f);
            syncTabGlassPosition();
        }
        applyTopInsetToFeeds(chromeHeight);
        syncScrollEdgeActivation(getCurrentTabRecyclerView());
    }

    /**
     * 对齐 iOS 26 UIScrollEdgeEffect：列表静止在顶部（scroll == 0）时顶部软边模糊 alpha = 0，
     * 首行作品卡片 100% 锐利且零多余 GPU 开销；上滑 24dp 内平滑激活保护状态栏与可见 Toolbar。
     */
    private void syncScrollEdgeActivation(@Nullable RecyclerView recyclerView) {
        if (baseBind == null) {
            return;
        }
        if (!isSoftEdgeFloatingMode()) {
            baseBind.topScrollEdge.setActivationProgress(0f);
            return;
        }
        int fadeRange = Math.max(1, DensityUtil.dp2px(24f));
        int scrolledPx = recyclerView != null ? computeScrolledFromTop(recyclerView, fadeRange) : 0;
        float progress = Math.max(
                clampFloat(scrolledPx / (float) fadeRange, 0f, 1f),
                clampFloat(-floatingCollapsePx / (float) fadeRange, 0f, 1f));
        baseBind.topScrollEdge.setActivationProgress(progress);
    }

    private void syncTabGlassPosition() {
        if (baseBind == null || railModeShown) {
            return;
        }
        int toolbarH = baseBind.toolbar.getHeight() > 0
                ? baseBind.toolbar.getHeight()
                : DensityUtil.dp2px(50f);
        float baseY;
        if (isSoftEdgeFloatingMode()) {
            baseY = statusBarHeightPx + toolbarH + floatingCollapsePx;
        } else {
            int appBarBottom = baseBind.appBar.getBottom();
            int tabH = baseBind.tabGlassPlatter.getHeight() > 0
                    ? baseBind.tabGlassPlatter.getHeight()
                    : DensityUtil.dp2px(48f);
            baseY = appBarBottom > 0
                    ? Math.max((float) statusBarHeightPx, (float) (appBarBottom - tabH))
                    : (float) (statusBarHeightPx + toolbarH);
        }
        if (liquidGlassTabBar != null) {
            liquidGlassTabBar.setBaseTranslationY(baseY);
        } else {
            baseBind.tabGlassPlatter.setTranslationY(baseY);
        }
    }

    private void applyFloatingCollapse(float targetOffsetPx) {
        if (baseBind == null || railModeShown || !isSoftEdgeFloatingMode()) {
            return;
        }
        int appBarH = baseBind.appBar.getHeight();
        int toolbarH = baseBind.toolbar.getHeight();
        float clamped = (toolbarH > 0 && Shaft.sSettings.isTopBarHideOnScroll())
                ? clampFloat(targetOffsetPx, -toolbarH, 0f)
                : 0f;
        floatingCollapsePx = clamped;

        baseBind.appBar.setTranslationY(clamped);
        syncTabGlassPosition();

        if (appBarH > 0) {
            int visibleToolbar = Math.max(0, toolbarH + Math.round(clamped));
            baseBind.topScrollEdge.setVisualAnchorHeightPx(statusBarHeightPx + visibleToolbar);

            // AppBarLayout 上移收起时精确裁切在状态栏底缘（appBar.top + translationY + (-clamped) == statusBarHeightPx），
            // 避免透明悬浮 Toolbar 向上滑动时与系统状态栏时钟重叠。
            int appBarW = baseBind.appBar.getWidth();
            int clipTop = Math.round(-clamped);
            if (clipTop > 0 && appBarW > 0) {
                appBarClipRect.set(0, clipTop, appBarW, appBarH);
                baseBind.appBar.setClipBounds(appBarClipRect);
            } else {
                baseBind.appBar.setClipBounds(null);
            }
        }

        if (toolbarH > 0) {
            // 标题与图标居中于 50dp Toolbar（顶缘约在 13dp），在滚出 ~42% toolbarH（~21dp）内先用
            // 平滑二次曲线淡出至 0，避免半收起停住时被状态栏底缘横腰截断出半截文字。
            float t = clampFloat(1f + clamped / (toolbarH * 0.42f), 0f, 1f);
            baseBind.toolbar.setAlpha(t * t);
        } else {
            baseBind.toolbar.setAlpha(1f);
        }
    }

    private void resetFloatingCollapse() {
        cancelFloatingCollapseAnimator();
        floatingCollapsePx = 0f;
        if (baseBind == null) {
            return;
        }
        baseBind.appBar.setTranslationY(0f);
        baseBind.appBar.setClipBounds(null);
        baseBind.toolbar.setAlpha(1f);
        if (!railModeShown && isSoftEdgeFloatingMode() && baseBind.toolbar.getHeight() > 0) {
            baseBind.topScrollEdge.setVisualAnchorHeightPx(
                    statusBarHeightPx + baseBind.toolbar.getHeight());
            syncTabGlassPosition();
        }
        syncScrollEdgeActivation(getCurrentTabRecyclerView());
    }

    private void snapFloatingCollapseIfNeeded(@NonNull RecyclerView recyclerView) {
        if (!isFloatingCollapseActive()) {
            return;
        }
        int toolbarH = baseBind.toolbar.getHeight();
        if (toolbarH <= 0) {
            return;
        }
        if (floatingCollapsePx >= -0.5f || floatingCollapsePx <= -toolbarH + 0.5f) {
            return;
        }
        // 仅在列表滚出超过整段 toolbarH 时自动吸附，避免靠顶时把 TabLayout 往上拉出空白缝隙
        int maxCollapse = computeScrolledFromTop(recyclerView, toolbarH);
        if (maxCollapse < toolbarH) {
            return;
        }
        float target = floatingCollapsePx <= (-toolbarH * 0.5f) ? -toolbarH : 0f;
        animateFloatingCollapseTo(target);
    }

    private void syncFloatingCollapseForCurrentTab(boolean animate) {
        RecyclerView rv = getCurrentTabRecyclerView();
        if (!isFloatingCollapseActive()) {
            syncScrollEdgeActivation(rv);
            return;
        }
        int toolbarH = baseBind.toolbar.getHeight();
        if (toolbarH <= 0) {
            syncScrollEdgeActivation(rv);
            return;
        }
        int maxCollapse = rv != null ? computeScrolledFromTop(rv, toolbarH) : 0;
        if (floatingCollapsePx < -maxCollapse) {
            float target = -maxCollapse;
            if (animate) {
                animateFloatingCollapseTo(target);
            } else {
                cancelFloatingCollapseAnimator();
                applyFloatingCollapse(target);
            }
        }
        syncScrollEdgeActivation(rv);
    }

    private void animateFloatingCollapseTo(float targetPx) {
        cancelFloatingCollapseAnimator();
        if (Math.abs(floatingCollapsePx - targetPx) < 0.5f) {
            applyFloatingCollapse(targetPx);
            return;
        }
        floatingCollapseAnimator = ValueAnimator.ofFloat(floatingCollapsePx, targetPx);
        floatingCollapseAnimator.setDuration(FLOATING_SNAP_DURATION_MS);
        floatingCollapseAnimator.setInterpolator(new DecelerateInterpolator());
        floatingCollapseAnimator.addUpdateListener(animator -> {
            applyFloatingCollapse((Float) animator.getAnimatedValue());
            syncScrollEdgeActivation(getCurrentTabRecyclerView());
        });
        floatingCollapseAnimator.start();
    }

    private void cancelFloatingCollapseAnimator() {
        if (floatingCollapseAnimator != null) {
            floatingCollapseAnimator.cancel();
            floatingCollapseAnimator = null;
        }
    }

    private void installChildFeedScrollObserver() {
        if (childFeedLifecycleCallbacks != null) {
            return;
        }
        childFeedLifecycleCallbacks = new FragmentManager.FragmentLifecycleCallbacks() {
            @Override
            public void onFragmentViewCreated(
                    @NonNull FragmentManager fm,
                    @NonNull Fragment f,
                    @NonNull View v,
                    @Nullable Bundle savedInstanceState) {
                attachFeedScrollListener(v);
            }

            @Override
            public void onFragmentViewDestroyed(@NonNull FragmentManager fm, @NonNull Fragment f) {
                View v = f.getView();
                if (v != null) {
                    detachFeedScrollListener(v);
                }
            }
        };
        getChildFragmentManager().registerFragmentLifecycleCallbacks(
                childFeedLifecycleCallbacks, false);
        for (Fragment child : getChildFragmentManager().getFragments()) {
            View v = child.getView();
            if (v != null) {
                attachFeedScrollListener(v);
            }
        }
    }

    private void uninstallChildFeedScrollObserver() {
        if (childFeedLifecycleCallbacks != null) {
            getChildFragmentManager().unregisterFragmentLifecycleCallbacks(
                    childFeedLifecycleCallbacks);
            childFeedLifecycleCallbacks = null;
        }
        for (RecyclerView rv : observedRecyclerViews) {
            if (rv != null) {
                rv.removeOnScrollListener(feedScrollListener);
            }
        }
        observedRecyclerViews.clear();
    }

    private void attachFeedScrollListener(@NonNull View fragmentRoot) {
        RecyclerView rv = fragmentRoot.findViewById(ceui.pixiv.feeds.R.id.feed_list_view);
        if (rv != null && observedRecyclerViews.add(rv)) {
            rv.addOnScrollListener(feedScrollListener);
        }
        if (rv != null && rv == getCurrentTabRecyclerView()) {
            syncScrollEdgeActivation(rv);
        }
    }

    private void detachFeedScrollListener(@NonNull View fragmentRoot) {
        RecyclerView rv = fragmentRoot.findViewById(ceui.pixiv.feeds.R.id.feed_list_view);
        if (rv != null) {
            rv.removeOnScrollListener(feedScrollListener);
            observedRecyclerViews.remove(rv);
        }
    }

    @Nullable
    private RecyclerView getCurrentTabRecyclerView() {
        if (baseBind == null || mFragments == null) {
            return null;
        }
        int pos = baseBind.viewPager.getCurrentItem();
        if (pos < 0 || pos >= mFragments.length) {
            return null;
        }
        Fragment current = mFragments[pos];
        if (current == null) {
            return null;
        }
        View root = current.getView();
        return root != null ? root.findViewById(ceui.pixiv.feeds.R.id.feed_list_view) : null;
    }

    private static int computeScrolledFromTop(@NonNull RecyclerView rv, int maxNeededPx) {
        if (maxNeededPx <= 0 || !rv.canScrollVertically(-1)) {
            return 0;
        }
        RecyclerView.LayoutManager lm = rv.getLayoutManager();
        if (lm == null) {
            return 0;
        }
        View child0 = lm.findViewByPosition(0);
        if (child0 == null) {
            return maxNeededPx;
        }
        ViewGroup.LayoutParams lp = child0.getLayoutParams();
        int topMargin = lp instanceof ViewGroup.MarginLayoutParams
                ? ((ViewGroup.MarginLayoutParams) lp).topMargin
                : 0;
        int firstStart = lm.getDecoratedTop(child0) - topMargin;
        // 取 lm.getPaddingTop() 而非 rv.getPaddingTop()：JustifiedLayoutManager 会覆写 getPaddingTop()
        // 多让一格顶部间距，以 LayoutManager 口径算滚出距离在四种列表布局下都精确到像素。
        int scrolledFromTop = Math.max(0, lm.getPaddingTop() - firstStart);
        return Math.min(maxNeededPx, scrolledFromTop);
    }

    private static float clampFloat(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    /**
     * 液态玻璃悬浮态挂在 AppBarLayout 上的穿透 Behavior：
     * 1. 非 null 确保 CoordinatorLayout.LayoutParams.mBehaviorResolved = true，阻止 AppBarLayout
     *    实现 AttachedBehavior 时在首次测量又装回默认 AppBarLayout.Behavior；
     * 2. 不消费任何嵌套滚动，确保列表从第 1 像素起 1:1 跟随手指滚动，顶栏收起由 RecyclerView.OnScrollListener 同步驱动。
     */
    private static final class FloatingPassthroughBehavior
            extends CoordinatorLayout.Behavior<AppBarLayout> {
    }

    /**
     * 经典实底模式下 AppBarLayout 顶部带有 statusBarHeightPx 外边距，将传给父类测量的可用高度
     * 扣除该状态栏高度，使 ViewPager 底缘精确对齐屏幕底部而非向下多出 statusBarHeightPx。
     */
    private static final class StatusBarAwareScrollingBehavior extends AppBarLayout.ScrollingViewBehavior {
        private final int statusBarHeightPx;

        StatusBarAwareScrollingBehavior(int statusBarHeightPx) {
            this.statusBarHeightPx = Math.max(0, statusBarHeightPx);
        }

        @Override
        public boolean onMeasureChild(
                @NonNull CoordinatorLayout parent,
                @NonNull View child,
                int parentWidthMeasureSpec,
                int widthUsed,
                int parentHeightMeasureSpec,
                int heightUsed) {
            int mode = View.MeasureSpec.getMode(parentHeightMeasureSpec);
            int size = View.MeasureSpec.getSize(parentHeightMeasureSpec);
            int adjustedSpec = mode == View.MeasureSpec.UNSPECIFIED
                    ? parentHeightMeasureSpec
                    : View.MeasureSpec.makeMeasureSpec(Math.max(0, size - statusBarHeightPx), mode);
            return super.onMeasureChild(
                    parent, child, parentWidthMeasureSpec, widthUsed, adjustedSpec, heightUsed);
        }
    }

    private void applyTopInsetToFeeds(int topInsetPx) {
        currentTopInsetPx = Math.max(0, topInsetPx);
        if (mFragments == null) {
            return;
        }
        for (Fragment fragment : mFragments) {
            if (fragment instanceof FeedFragment) {
                ((FeedFragment) fragment).setTopContentInset(currentTopInsetPx);
            }
        }
    }

    private static void setTopMargin(View view, int topMarginPx) {
        ViewGroup.LayoutParams lp = view.getLayoutParams();
        if (lp instanceof ViewGroup.MarginLayoutParams) {
            ViewGroup.MarginLayoutParams mlp = (ViewGroup.MarginLayoutParams) lp;
            if (mlp.topMargin != topMarginPx) {
                mlp.topMargin = topMarginPx;
                view.setLayoutParams(mlp);
            }
        }
    }

    /**
     * 顶栏（Toolbar）是否跟随列表滚动收起。
     * 悬浮液态玻璃态由 {@link #feedScrollListener} 同步列表滚动驱动 translationY，Toolbar 自身 scrollFlags 置 0；
     * 经典实底态继续走 AppBarLayout 原生 SCROLL_FLAG_SCROLL | SCROLL_FLAG_ENTER_ALWAYS。
     */
    private void applyTopBarScrollSetting() {
        final boolean hide = Shaft.sSettings.isTopBarHideOnScroll();
        final boolean softEdge = isSoftEdgeFloatingMode();
        if (softEdge) {
            if (!hide || railModeShown) {
                resetFloatingCollapse();
            } else {
                syncFloatingCollapseForCurrentTab(false);
            }
        }
        ViewGroup.LayoutParams params = baseBind.toolbar.getLayoutParams();
        if (!(params instanceof AppBarLayout.LayoutParams)) {
            return;
        }
        AppBarLayout.LayoutParams lp = (AppBarLayout.LayoutParams) params;
        final int flags = (!softEdge && hide)
                ? AppBarLayout.LayoutParams.SCROLL_FLAG_SCROLL
                        | AppBarLayout.LayoutParams.SCROLL_FLAG_ENTER_ALWAYS
                : 0;
        if (lp.getScrollFlags() == flags) {
            return;
        }
        if (!softEdge && !hide) {
            baseBind.appBar.setExpanded(true, false);
        }
        lp.setScrollFlags(flags);
        baseBind.toolbar.setLayoutParams(lp);
    }

    @Override
    public void lazyData() {
        final boolean hotTagsFirst = Shaft.sSettings.isRecommendHotTagsFirst();
        final int[] TITLE_RES = new int[]{R.string.recommend_illust, R.string.hot_tag};
        mFragments = new Fragment[]{
                RecmdIllustFeedFragment.newInstance(RecmdIllustFeedFragment.TYPE_ILLUST),
                HotTagsFeedFragment.newInstance(Params.TYPE_ILLUST)
        };
        if (hotTagsFirst) {
            int title = TITLE_RES[0];
            TITLE_RES[0] = TITLE_RES[1];
            TITLE_RES[1] = title;
            Fragment fragment = mFragments[0];
            mFragments[0] = mFragments[1];
            mFragments[1] = fragment;
        }
        applyTopInsetToFeeds(currentTopInsetPx);
        baseBind.viewPager.setAdapter(new FragmentPagerAdapter(getChildFragmentManager(),
                FragmentPagerAdapter.BEHAVIOR_RESUME_ONLY_CURRENT_FRAGMENT) {
            @NonNull
            @Override
            public Fragment getItem(int i) {
                return mFragments[i];
            }

            @Override
            public long getItemId(int position) {
                return hotTagsFirst && position < 2 ? 1 - position : position;
            }

            @NonNull
            @Override
            public Object instantiateItem(@NonNull ViewGroup container, int position) {
                Fragment fragment = (Fragment) super.instantiateItem(container, position);
                mFragments[position] = fragment;
                if (fragment instanceof FeedFragment) {
                    ((FeedFragment) fragment).setTopContentInset(currentTopInsetPx);
                }
                return fragment;
            }

            @Override
            public int getCount() {
                return TITLE_RES.length;
            }

            @NonNull
            @Override
            public CharSequence getPageTitle(int position) {
                return getString(TITLE_RES[position]);
            }
        });
        baseBind.tabLayout.setupWithViewPager(baseBind.viewPager);
        MyOnTabSelectedListener listener = new MyOnTabSelectedListener(mFragments);
        baseBind.tabLayout.addOnTabSelectedListener(listener);

        baseBind.wideTabs.setSegments(TITLE_RES);
        baseBind.wideTabs.setCurrentState(baseBind.viewPager.getCurrentItem());
        baseBind.wideTabs.setListener(new OnCheckChangeListener() {
            @Override
            public void onSelect(int index, View view) {
                baseBind.viewPager.setCurrentItem(index);
            }

            @Override
            public void onReselect(int index, View view) {
                if (mFragments[index] instanceof FeedFragment) {
                    ((FeedFragment) mFragments[index]).scrollToTop();
                }
            }
        });
        baseBind.viewPager.addOnPageChangeListener(new ViewPager.SimpleOnPageChangeListener() {
            private boolean pagerUserDragging = false;

            @Override
            public void onPageScrolled(int position, float positionOffset, int positionOffsetPixels) {
                if (pagerUserDragging && liquidGlassTabBar != null) {
                    liquidGlassTabBar.syncPageScroll(position, positionOffset);
                }
            }

            @Override
            public void onPageSelected(int position) {
                baseBind.wideTabs.setCurrentState(position);
                if (liquidGlassTabBar != null) {
                    liquidGlassTabBar.syncSelection(true);
                }
                syncFloatingCollapseForCurrentTab(true);
            }

            @Override
            public void onPageScrollStateChanged(int state) {
                if (state == ViewPager.SCROLL_STATE_DRAGGING) {
                    pagerUserDragging = true;
                } else if (state == ViewPager.SCROLL_STATE_IDLE) {
                    pagerUserDragging = false;
                    if (liquidGlassTabBar != null) {
                        liquidGlassTabBar.syncSelection(false);
                    }
                }
            }
        });
        if (liquidGlassTabBar != null) {
            liquidGlassTabBar.syncSelection(false);
        }
    }

    public void forceRefresh() {
        try {
            Fragment fragment = mFragments[baseBind.viewPager.getCurrentItem()];
            if (fragment instanceof FeedFragment) {
                ((FeedFragment) fragment).forceRefresh();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
