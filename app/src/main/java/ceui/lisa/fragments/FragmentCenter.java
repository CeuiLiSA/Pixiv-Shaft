package ceui.lisa.fragments;

import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.core.view.GravityCompat;
import androidx.core.widget.NestedScrollView;
import androidx.core.widget.TextViewCompat;
import androidx.fragment.app.FragmentTransaction;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

import ceui.lisa.BuildConfig;
import ceui.lisa.R;
import ceui.lisa.activities.MainActivity;
import ceui.lisa.databinding.LayoutDiscoverMoreSectionsBinding;
import ceui.lisa.utils.SystemBarMetrics;
import ceui.lisa.activities.TemplateActivity;
import ceui.lisa.activities.VActivity;
import ceui.lisa.adapters.DiscoverTagAdapter;
import ceui.lisa.adapters.RAdapter;
import ceui.lisa.adapters.SkeletonRailAdapter;
import ceui.lisa.core.Container;
import ceui.lisa.core.PageData;
import ceui.lisa.databinding.FragmentNewCenterBinding;
import ceui.pixiv.api.model.Illust;
import ceui.lisa.utils.DensityUtil;
import ceui.lisa.utils.Dev;
import ceui.lisa.utils.Params;
import ceui.pixiv.witstudio.theme.V3Palette;
import ceui.lisa.view.HorizontalSpaceDecoration;
import ceui.pixiv.ui.discovery.DiscoverViewModel;
import ceui.pixiv.ui.prime.PrimeTagIndexItem;
import ceui.pixiv.ui.navigation.BottomSafeInsets;
import ceui.pixiv.ui.navigation.HomeShellHost;
import ceui.pixiv.ui.navigation.TemplateRoute;

/**
 * 「发现」tab —— V3 内容货架版。侧边栏「发现」分组的内容直接铺进这里:
 *   热度标签(本地策展)→ 最新 → 特辑(pixivision)→ 本月收藏 → 当前最热 → 更多分类 → Shaft 榜单。
 * 每条货架横向缩略图,数据在 {@link DiscoverViewModel};「查看全部」跳原来的整页,零新后端。
 * 本月收藏 / 当前最热走自建 shaft-api-v2,Lite 渠道整段不展示。
 *
 * 「更多分类」最后一枚 chip 是「热门搜索」
 * （{@link ceui.pixiv.ui.prime.CorpusTagsFragment} → {@link ceui.pixiv.ui.prime.CorpusTagDetailFragment}）：
 * 和上面的「热度标签」是同一种两级货架,但那份目录是一年前策展、随 APK 发布的 202 个标签,
 * 这份来自 pixshaft-api 的作品库 —— 搜索缓存每回填一页就往里沉淀 30 个作品,所以它**每天都在
 * 自己变厚**。同属自建后端,Lite 不展示。
 */
public class FragmentCenter extends BaseLazyFragment<FragmentNewCenterBinding> {

    private ceui.pixiv.ui.pivision.PivisionRailFeedFragment pivisionFragment = null;
    private DiscoverViewModel discoverVM = null;
    private LayoutDiscoverMoreSectionsBinding bottomBind = null;

    /** Lite 渠道不展示自建后端的两条货架(本月收藏 / 当前最热),和侧边栏门控一致。 */
    private boolean includeServerShelves() {
        return !BuildConfig.IS_LITE;
    }

    @Override
    public void initLayout() {
        mLayoutID = R.layout.fragment_new_center;
    }

    @Override
    protected void initView() {
        if (Dev.hideMainActivityStatus) {
            ViewGroup.LayoutParams headParams = baseBind.head.getLayoutParams();
            headParams.height = SystemBarMetrics.statusBarHeight(mContext);
            baseBind.head.setLayoutParams(headParams);
        }

        baseBind.drawerButton.setOnClickListener(v -> {
            if (mActivity instanceof MainActivity) {
                ((MainActivity) mActivity).getDrawer().openDrawer(GravityCompat.START, true);
            }
        });
        baseBind.searchBar.setOnClickListener(v -> openFragment(TemplateRoute.SEARCH));
        // 宽窗口下侧边导航栏自带菜单入口，标题行不再放抽屉按钮（#1087）
        HomeShellHost.observe(
                this, railShown -> HomeShellHost.adaptHeaderRow(baseBind.drawerButton, railShown));

        // 底部安全区:首页底栏浮在内容之上且会跟随滚动收起,页面铺满整屏,末尾靠 padding 让位。
        BottomSafeInsets.applyTo(baseBind.discoverScroll);

        // ── 骨架首屏货架 RecyclerView 初始化(标签 120 / 插画 180)。次级货架在 ViewStub 中按需加载 ──
        setupRail(baseBind.tagRail, 6, 120);
        setupRail(baseBind.latestRail, 4, 180);

        // ── 「查看全部」跳原来的整页 ──
        baseBind.tagMore.setOnClickListener(v -> openFragment(TemplateRoute.PRIME_TAGS));
        baseBind.latestMore.setOnClickListener(v -> openFragmentKeepStatusBar(TemplateRoute.NEW_WORKS));

        // ── 重点模块:漫画 / 小说 —— 置顶大卡 ──
        baseBind.bigManga.setOnClickListener(v -> openFragment(TemplateRoute.RECOMMENDED_MANGA));
        baseBind.bigNovel.setOnClickListener(v -> openFragmentKeepStatusBar(TemplateRoute.RECOMMENDED_NOVELS));

        V3Palette palette = V3Palette.from(mContext);
        styleBigModule(baseBind.bigManga, baseBind.bigMangaIconWrap, palette);
        styleBigModule(baseBind.bigNovel, baseBind.bigNovelIconWrap, palette);

        // 用户向下滚动时按需预先展开下半部分货架与榜单
        baseBind.discoverScroll.setOnScrollChangeListener(
                (NestedScrollView.OnScrollChangeListener) (v, scrollX, scrollY, oldScrollX, oldScrollY) -> {
                    if (scrollY > 50) {
                        ensureBottomSections();
                    }
                });

        discoverVM = new ViewModelProvider(this).get(DiscoverViewModel.class);
        discoverVM.getPrimeTags().observe(getViewLifecycleOwner(), this::bindTagRail);
        discoverVM.getLatest().observe(getViewLifecycleOwner(),
                list -> bindIllustRail(baseBind.latestSection, baseBind.latestRail, list));
        discoverVM.getSiteRecommend().observe(getViewLifecycleOwner(), list -> {
            if (bottomBind != null) {
                bindIllustRail(bottomBind.siteSection, bottomBind.siteRail, list);
            }
        });
        discoverVM.getRecentHot().observe(getViewLifecycleOwner(), list -> {
            if (bottomBind != null) {
                bindIllustRail(bottomBind.recentSection, bottomBind.recentRail, list);
            }
        });
    }

    private void ensureBottomSections() {
        if (bottomBind != null || baseBind == null || getContext() == null) {
            return;
        }
        if (baseBind.moreSectionsStub.isInflated()) {
            bottomBind = LayoutDiscoverMoreSectionsBinding.bind(baseBind.moreSectionsStub.getRoot());
            initBottomSections(bottomBind);
        } else if (baseBind.moreSectionsStub.getViewStub() != null) {
            View inflated = baseBind.moreSectionsStub.getViewStub().inflate();
            bottomBind = LayoutDiscoverMoreSectionsBinding.bind(inflated);
            initBottomSections(bottomBind);
        } else {
            android.view.ViewStub stub = baseBind.getRoot().findViewById(R.id.moreSectionsStub);
            if (stub != null) {
                View inflated = stub.inflate();
                bottomBind = LayoutDiscoverMoreSectionsBinding.bind(inflated);
                initBottomSections(bottomBind);
            }
        }
    }

    private void initBottomSections(@NonNull LayoutDiscoverMoreSectionsBinding bind) {
        setupRail(bind.siteRail, 4, 180);
        setupRail(bind.recentRail, 4, 180);

        bind.socialSection.setEntryClickListeners(
                v -> openFragment(TemplateRoute.CHAT),
                v -> openFragment(TemplateRoute.PLAZA));
        bind.socialSection.setVisibility(
                BuildConfig.IS_LITE && !BuildConfig.DEBUG ? View.GONE : View.VISIBLE);

        if (BuildConfig.IS_LITE) {
            bind.siteSection.setVisibility(View.GONE);
            bind.recentSection.setVisibility(View.GONE);
            bind.shaftRankSection.setVisibility(View.GONE);
            bind.catNovelLengthRank.setVisibility(View.GONE);
            bind.catTrendingArtists.setVisibility(View.GONE);
            bind.catDailyRecommendations.setVisibility(View.GONE);
            bind.catPixivComic.setVisibility(View.GONE);
            bind.catCorpusLibrary.setVisibility(View.GONE);
        }

        bind.siteMore.setOnClickListener(v -> openFragment(TemplateRoute.SITE_RECOMMEND));
        bind.recentMore.setOnClickListener(v -> openFragment(TemplateRoute.RECENT_RECOMMEND));

        bind.catArtistRank.setOnClickListener(v -> openFragment(TemplateRoute.ARTIST_RANK));
        bind.catArtistAvgRank.setOnClickListener(v -> openFragment(TemplateRoute.ARTIST_AVG_RANK));
        bind.catViewRank.setOnClickListener(v -> openFragment(TemplateRoute.VIEW_RANK));
        bind.catBookmarkRank.setOnClickListener(v -> openFragment(TemplateRoute.BOOKMARK_RANK));
        bind.catAiRank.setOnClickListener(v -> openFragment(TemplateRoute.AI_RANK));
        bind.catYearRank.setOnClickListener(v -> openFragment(TemplateRoute.YEAR_RANK));
        bind.catTagRank.setOnClickListener(v -> openFragment(TemplateRoute.TAG_RANK));
        bind.catWallpaperRank.setOnClickListener(v -> openFragment(TemplateRoute.WALLPAPER_RANK));
        bind.catSeriesRank.setOnClickListener(v -> openFragment(TemplateRoute.SERIES_RANK));
        bind.catMonthRank.setOnClickListener(v -> openFragment(TemplateRoute.MONTH_RANK));
        bind.catSfwRank.setOnClickListener(v -> openFragment(TemplateRoute.SFW_RANK));
        bind.catUgoiraRank.setOnClickListener(v -> openFragment(TemplateRoute.UGOIRA_RANK));

        bind.catNovelLengthRank.setOnClickListener(v -> openFragment(TemplateRoute.NOVEL_LENGTH_RANK));
        bind.catTrendingArtists.setOnClickListener(v -> openFragment(TemplateRoute.TRENDING_ARTISTS));
        bind.catDailyRecommendations.setOnClickListener(v -> openFragment(TemplateRoute.DAILY_RECOMMENDATIONS));
        bind.catPixivComic.setOnClickListener(v -> openFragment(TemplateRoute.PIXIV_COMIC));
        bind.catWalk.setOnClickListener(v -> openFragment(TemplateRoute.WALKTHROUGH));
        bind.catFollowNovel.setOnClickListener(v -> openFragment(TemplateRoute.FOLLOWING_NOVELS));
        bind.catDiscovery.setOnClickListener(v -> openFragment(TemplateRoute.DISCOVERY));
        bind.catWebDiscovery.setOnClickListener(v -> openFragment(TemplateRoute.WEB_DISCOVERY));
        bind.catNiceFriend.setOnClickListener(v -> openFragment(TemplateRoute.NICE_FRIEND_ILLUSTS));
        bind.catCorpusLibrary.setOnClickListener(v -> openFragment(TemplateRoute.CORPUS_LIBRARY));

        if (BuildConfig.IS_LITE) {
            bind.catWeb.setVisibility(View.GONE);
            bind.catWebDiscovery.setVisibility(View.GONE);
            bind.catFanbox.setVisibility(View.GONE);
        } else {
            bind.catWeb.setOnClickListener(v -> openMainEntry(R.id.nav_web_home));
            bind.catFanbox.setOnClickListener(v -> openMainEntry(R.id.nav_fanbox));
        }

        V3Palette palette = V3Palette.from(mContext);
        styleCatChip(bind.catArtistRank, palette, R.drawable.ic_setcat_person);
        styleCatChip(bind.catArtistAvgRank, palette, R.drawable.ic_baseline_star_24);
        styleCatChip(bind.catViewRank, palette, R.drawable.ic_baseline_remove_red_eye_24);
        styleCatChip(bind.catBookmarkRank, palette, R.drawable.ic_like_heart_fill);
        styleCatChip(bind.catAiRank, palette, R.drawable.baseline_auto_awesome_24);
        styleCatChip(bind.catYearRank, palette, R.drawable.ic_date_range_black_24dp);
        styleCatChip(bind.catTagRank, palette, R.drawable.ic_loyalty_black_24dp);
        styleCatChip(bind.catWallpaperRank, palette, R.drawable.ic_setcat_photo);
        styleCatChip(bind.catSeriesRank, palette, R.drawable.ic_baseline_list_24);
        styleCatChip(bind.catMonthRank, palette, R.drawable.ic_fiber_new_black_24dp);
        styleCatChip(bind.catNovelLengthRank, palette, R.drawable.ic_baseline_menu_book_24);
        styleCatChip(bind.catSfwRank, palette, R.drawable.ic_check_circle_black_24dp);
        styleCatChip(bind.catTrendingArtists, palette, R.drawable.outline_whatshot_24);
        styleCatChip(bind.catDailyRecommendations, palette, R.drawable.ic_date_range_black_24dp);
        styleCatChip(bind.catUgoiraRank, palette, R.drawable.ic_baseline_play_arrow_24);
        styleCatChip(bind.catWalk, palette, R.drawable.ic_collections_black_24dp);
        styleCatChip(bind.catPixivComic, palette, R.drawable.ic_baseline_palette_24);
        styleCatChip(bind.catFollowNovel, palette, R.drawable.ic_baseline_bookmark_24);
        styleCatChip(bind.catDiscovery, palette, R.drawable.ic_baseline_explore_24);
        styleCatChip(bind.catWebDiscovery, palette, R.drawable.ic_setcat_globe);
        styleCatChip(bind.catWeb, palette, R.drawable.ic_setcat_globe);
        styleCatChip(bind.catFanbox, palette, R.drawable.ic_setcat_heart);
        styleCatChip(bind.catNiceFriend, palette, R.drawable.ic_baseline_how_to_reg_24);
        styleCatChip(bind.catCorpusLibrary, palette, R.drawable.ic_setcat_search);

        if (discoverVM != null) {
            List<Illust> siteList = discoverVM.getSiteRecommend().getValue();
            if (siteList != null && !siteList.isEmpty()) {
                bindIllustRail(bind.siteSection, bind.siteRail, siteList);
            }
            List<Illust> recentList = discoverVM.getRecentHot().getValue();
            if (recentList != null && !recentList.isEmpty()) {
                bindIllustRail(bind.recentSection, bind.recentRail, recentList);
            }
        }
    }

    private void setupRail(RecyclerView rv, int skeletonCount, int itemWidthDp) {
        // 只留卡间 12dp 右间距;首卡左缘由 XML paddingStart=20 统一控制(对齐标题与其他货架)。
        rv.addItemDecoration(new HorizontalSpaceDecoration(DensityUtil.dp2px(12.0f)));
        rv.setLayoutManager(new LinearLayoutManager(mContext, LinearLayoutManager.HORIZONTAL, false));
        rv.setHasFixedSize(true);
        rv.setNestedScrollingEnabled(false);
        // 先挂骨架图(固定高 rail),数据到达后换真实 adapter,页面高度全程不变。
        rv.setAdapter(new SkeletonRailAdapter(skeletonCount, DensityUtil.dp2px(itemWidthDp)));
    }

    /** 插画货架:空则收起整段;非空绑 RAdapter(复用排行榜横向卡),点击进详情大图 pager。 */
    private void bindIllustRail(View section, RecyclerView rv, List<Illust> data) {
        if (data == null || data.isEmpty()) {
            section.setVisibility(View.GONE);
            return;
        }
        section.setVisibility(View.VISIBLE);
        RAdapter adapter = new RAdapter(data, mContext);
        adapter.setOnItemClickListener((v, position, viewType) -> {
            PageData pageData = new PageData(data);
            Container.get().addPageToMap(pageData);
            Intent intent = new Intent(mContext, VActivity.class);
            intent.putExtra(Params.POSITION, position);
            intent.putExtra(Params.PAGE_UUID, pageData.getUUID());
            startActivity(intent);
        });
        crossfadeSwap(rv, adapter);
    }

    /**
     * 骨架图 → 真实内容的柔和过渡:先把 rail 淡出(140ms),换 adapter,再淡入(240ms),
     * 而不是 setAdapter 瞬间硬切。配合各 adapter 里 Glide 的 crossFade,图片也是渐显不是突现。
     */
    private void crossfadeSwap(RecyclerView rv, RecyclerView.Adapter<?> adapter) {
        rv.animate().cancel();
        rv.animate().alpha(0f).setDuration(140).withEndAction(() -> {
            rv.setAdapter(adapter);
            rv.setAlpha(0f);
            rv.animate().alpha(1f).setDuration(240).start();
        }).start();
    }

    /** 热度标签货架:点卡开 PrimeTagDetail(和 PrimeTagsFragment 一致)。 */
    private void bindTagRail(List<PrimeTagIndexItem> data) {
        if (data == null || data.isEmpty()) {
            baseBind.tagSection.setVisibility(View.GONE);
            return;
        }
        baseBind.tagSection.setVisibility(View.VISIBLE);
        DiscoverTagAdapter adapter = new DiscoverTagAdapter(data, mContext);
        adapter.setOnItemClickListener((v, position, viewType) -> {
            PrimeTagIndexItem item = data.get(position);
            // 没有 tag 或 file_path 不合规(拿不到服务端 key)就点不开,静默忽略。
            if (item.getTag() == null || item.getTagKey() == null) {
                return;
            }
            String name = item.getTag().getTranslated_name() != null
                    ? item.getTag().getTranslated_name() : item.getTag().getName();
            Intent intent = new Intent(mContext, TemplateActivity.class);
            intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, TemplateRoute.PRIME_TAG_DETAIL.key);
            intent.putExtra("name", name);
            intent.putExtra("key", item.getTagKey());
            startActivity(intent);
        });
        crossfadeSwap(baseBind.tagRail, adapter);
    }

    /**
     * 把「更多分类 / Shaft 榜单」的淡灰 chip 提升成主题色 tint 胶囊:20% 主题色底 + 30% 描边 + accent 文字 + 前导图标,
     * 全程跟随主题色、日夜双模。图标统一压到 17dp 并用 accent tint,和文字同色。
     */
    private void styleCatChip(TextView chip, V3Palette palette, int iconRes) {
        chip.setBackground(palette.pillSecondary(DensityUtil.dp2px(14.0f), DensityUtil.dp2px(1.0f)));
        chip.setTextColor(palette.getTextAccent());
        int padH = DensityUtil.dp2px(16.0f);
        int padV = DensityUtil.dp2px(10.0f);
        chip.setPadding(padH, padV, padH, padV);
        chip.setCompoundDrawablePadding(DensityUtil.dp2px(7.0f));
        Drawable icon = ContextCompat.getDrawable(mContext, iconRes);
        if (icon != null) {
            int size = DensityUtil.dp2px(17.0f);
            icon.setBounds(0, 0, size, size);
            chip.setCompoundDrawablesRelative(icon, null, null, null);
            TextViewCompat.setCompoundDrawableTintList(chip,
                    ColorStateList.valueOf(palette.getTextAccent()));
        }
    }

    /**
     * 「漫画 / 小说」重点大卡:主题色 tint 的卡底 + 实心图标底,按主题色 tint、日夜双模。
     * 圆角 18(卡)/12(图标底),和 V3 其余圆角语言一致。
     */
    private void styleBigModule(View card, View iconWrap, V3Palette palette) {
        card.setBackground(palette.seriesStripBg(DensityUtil.dp2px(18.0f)));
        iconWrap.setBackground(palette.seriesIconBg(DensityUtil.dp2px(12.0f)));
    }

    private void openFragment(TemplateRoute route) {
        Intent intent = new Intent(mContext, TemplateActivity.class);
        intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, route.key);
        startActivity(intent);
    }

    /** 小说类页面进 TemplateActivity 时保留状态栏(和旧入口/侧边栏一致)。 */
    private void openFragmentKeepStatusBar(TemplateRoute route) {
        Intent intent = new Intent(mContext, TemplateActivity.class);
        intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, route.key);
        intent.putExtra("hideStatusBar", false);
        startActivity(intent);
    }

    private void openMainEntry(int actionId) {
        if (mActivity instanceof MainActivity) {
            ((MainActivity) mActivity).handleDrawerAction(actionId);
        }
    }

    private boolean innerContentReady = false;

    private void ensureInnerContent() {
        if (innerContentReady || getContext() == null) {
            return;
        }
        innerContentReady = true;
        setupInnerContent();
    }

    private void setupInnerContent() {
        FragmentTransaction transaction = getChildFragmentManager().beginTransaction();
        pivisionFragment = new ceui.pixiv.ui.pivision.PivisionRailFeedFragment();
        transaction.add(R.id.fragment_pivision, pivisionFragment, "PivisionRailFeedFragment");
        transaction.commitNowAllowingStateLoss();

        if (discoverVM != null) {
            discoverVM.start(includeServerShelves());
        }
    }

    @Override
    public void lazyData() {
        ensureInnerContent();
        ensureBottomSections();
    }

    @Override
    public void setMenuVisibility(boolean menuVisible) {
        super.setMenuVisibility(menuVisible);
        if (menuVisible) {
            ensureInnerContent();
            ensureBottomSections();
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        if (isMenuVisible()) {
            ensureInnerContent();
            ensureBottomSections();
        }
    }

    @Override
    public void onDestroyView() {
        innerContentReady = false;
        bottomBind = null;
        super.onDestroyView();
    }

    public void forceRefresh() {
        ensureInnerContent();
        ensureBottomSections();
        if (discoverVM != null) {
            discoverVM.reload(includeServerShelves());
        }
        if (pivisionFragment != null) {
            pivisionFragment.forceRefresh();
        }
    }
}
