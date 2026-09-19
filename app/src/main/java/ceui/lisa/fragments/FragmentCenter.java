package ceui.lisa.fragments;

import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.core.content.ContextCompat;
import androidx.core.view.GravityCompat;
import androidx.core.widget.TextViewCompat;
import androidx.fragment.app.FragmentTransaction;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

import ceui.lisa.BuildConfig;
import ceui.lisa.R;
import ceui.lisa.activities.MainActivity;
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

        // 底部安全区:首页底栏浮在内容之上且会跟随滚动收起,页面铺满整屏,末尾靠 padding 让位。
        BottomSafeInsets.applyTo(baseBind.discoverScroll);
        baseBind.socialSection.setEntryClickListeners(
                v -> openFragment(TemplateRoute.CHAT),
                v -> openFragment(TemplateRoute.PLAZA));
        // Social entries are always available in supported channels; no per-entry switches.
        baseBind.socialSection.setVisibility(
                BuildConfig.IS_LITE && !BuildConfig.DEBUG ? View.GONE : View.VISIBLE);

        // ── 货架 RecyclerView 初始化(横向,固定高)。先挂骨架图占位,数据到达后 bindXxxRail 换真实
        //    adapter —— rail 高度全程不变,页面没有任何高度跳动。骨架卡宽与真实卡一致(标签 120 / 插画 180)。──
        setupRail(baseBind.tagRail, 6, 120);
        setupRail(baseBind.latestRail, 4, 180);
        setupRail(baseBind.siteRail, 4, 180);
        setupRail(baseBind.recentRail, 4, 180);

        // 本月收藏 / 当前最热 / Shaft 榜单 / 长篇小说 / 人气画师 / 每日推荐
        // 走自建 shaft-api-v2,Lite 渠道不展示 —— GONE。
        // pixiv 漫画(comic.pixiv.net)虽然不依赖自建后端,但同样是 app-api 之外的站外内容源,
        // 和 Web 首页 / FANBOX 一个口径:Lite 不出现。
        if (BuildConfig.IS_LITE) {
            baseBind.siteSection.setVisibility(View.GONE);
            baseBind.recentSection.setVisibility(View.GONE);
            baseBind.shaftRankSection.setVisibility(View.GONE);
            baseBind.catNovelLengthRank.setVisibility(View.GONE);
            baseBind.catTrendingArtists.setVisibility(View.GONE);
            baseBind.catDailyRecommendations.setVisibility(View.GONE);
            baseBind.catPixivComic.setVisibility(View.GONE);
            baseBind.catCorpusLibrary.setVisibility(View.GONE);
        }

        // ── 「查看全部」跳原来的整页 ──
        baseBind.tagMore.setOnClickListener(v -> openFragment(TemplateRoute.PRIME_TAGS));
        baseBind.latestMore.setOnClickListener(v -> openFragmentKeepStatusBar(TemplateRoute.NEW_WORKS));
        baseBind.siteMore.setOnClickListener(v -> openFragment(TemplateRoute.SITE_RECOMMEND));
        baseBind.recentMore.setOnClickListener(v -> openFragment(TemplateRoute.RECENT_RECOMMEND));

        // ── 重点模块:漫画 / 小说 —— 置顶大卡,和下面的小分类 chip 分开(用户反馈:别混在一起) ──
        baseBind.bigManga.setOnClickListener(v -> openFragment(TemplateRoute.RECOMMENDED_MANGA));
        baseBind.bigNovel.setOnClickListener(v -> openFragmentKeepStatusBar(TemplateRoute.RECOMMENDED_NOVELS));

        // ── Shaft 榜单 ──
        baseBind.catArtistRank.setOnClickListener(v -> openFragment(TemplateRoute.ARTIST_RANK));
        baseBind.catArtistAvgRank.setOnClickListener(v -> openFragment(TemplateRoute.ARTIST_AVG_RANK));
        baseBind.catViewRank.setOnClickListener(v -> openFragment(TemplateRoute.VIEW_RANK));
        baseBind.catBookmarkRank.setOnClickListener(v -> openFragment(TemplateRoute.BOOKMARK_RANK));
        baseBind.catAiRank.setOnClickListener(v -> openFragment(TemplateRoute.AI_RANK));
        baseBind.catYearRank.setOnClickListener(v -> openFragment(TemplateRoute.YEAR_RANK));
        baseBind.catTagRank.setOnClickListener(v -> openFragment(TemplateRoute.TAG_RANK));
        baseBind.catWallpaperRank.setOnClickListener(v -> openFragment(TemplateRoute.WALLPAPER_RANK));
        baseBind.catSeriesRank.setOnClickListener(v -> openFragment(TemplateRoute.SERIES_RANK));
        baseBind.catMonthRank.setOnClickListener(v -> openFragment(TemplateRoute.MONTH_RANK));
        baseBind.catSfwRank.setOnClickListener(v -> openFragment(TemplateRoute.SFW_RANK));
        baseBind.catUgoiraRank.setOnClickListener(v -> openFragment(TemplateRoute.UGOIRA_RANK));
        // ── 更多分类 ──
        baseBind.catNovelLengthRank.setOnClickListener(v -> openFragment(TemplateRoute.NOVEL_LENGTH_RANK));
        baseBind.catTrendingArtists.setOnClickListener(v -> openFragment(TemplateRoute.TRENDING_ARTISTS));
        baseBind.catDailyRecommendations.setOnClickListener(v -> openFragment(TemplateRoute.DAILY_RECOMMENDATIONS));
        baseBind.catPixivComic.setOnClickListener(v -> openFragment(TemplateRoute.PIXIV_COMIC));
        baseBind.catWalk.setOnClickListener(v -> openFragment(TemplateRoute.WALKTHROUGH));
        baseBind.catFollowNovel.setOnClickListener(v -> openFragment(TemplateRoute.FOLLOWING_NOVELS));
        baseBind.catDiscovery.setOnClickListener(v -> openFragment(TemplateRoute.DISCOVERY));
        baseBind.catWebDiscovery.setOnClickListener(v -> openFragment(TemplateRoute.WEB_DISCOVERY));
        baseBind.catNiceFriend.setOnClickListener(v -> openFragment(TemplateRoute.NICE_FRIEND_ILLUSTS));
        // 热门搜索:标签目录由搜索缓存逐日沉淀,谁搜出来的都算数,看的人不占额度。
        baseBind.catCorpusLibrary.setOnClickListener(v -> openFragment(TemplateRoute.CORPUS_LIBRARY));

        // Web 首页 / pixiv FANBOX:仅在发现页展示,由首页统一处理跳转与登录分流,Lite 不展示。
        if (BuildConfig.IS_LITE) {
            baseBind.catWeb.setVisibility(View.GONE);
            baseBind.catWebDiscovery.setVisibility(View.GONE);
            baseBind.catFanbox.setVisibility(View.GONE);
        } else {
            baseBind.catWeb.setOnClickListener(v -> openMainEntry(R.id.nav_web_home));
            baseBind.catFanbox.setOnClickListener(v -> openMainEntry(R.id.nav_fanbox));
        }

        // 更多分类 / Shaft 榜单 chip 提升存在感:主题色 tint 胶囊 + accent 文字 + 前导图标,跟随主题色、日夜双模,
        // 从原来"淡灰几乎隐形"变成清晰可点(pillSecondary=20% 主题色底 + 30% 描边,不刺眼)。
        V3Palette palette = V3Palette.from(mContext);
        // 漫画 / 小说 大卡:主题色 tint 卡底(seriesStripBg,~35% 主题色)+ 实心图标底(seriesIconBg),
        // 比 chip 的 pillSecondary(20%)明显更抢眼,跟随主题色、日夜双模。
        styleBigModule(baseBind.bigManga, baseBind.bigMangaIconWrap, palette);
        styleBigModule(baseBind.bigNovel, baseBind.bigNovelIconWrap, palette);
        styleCatChip(baseBind.catArtistRank, palette, R.drawable.ic_setcat_person);
        styleCatChip(baseBind.catArtistAvgRank, palette, R.drawable.ic_baseline_star_24);
        styleCatChip(baseBind.catViewRank, palette, R.drawable.ic_baseline_remove_red_eye_24);
        styleCatChip(baseBind.catBookmarkRank, palette, R.drawable.ic_like_heart_fill);
        styleCatChip(baseBind.catAiRank, palette, R.drawable.baseline_auto_awesome_24);
        styleCatChip(baseBind.catYearRank, palette, R.drawable.ic_date_range_black_24dp);
        styleCatChip(baseBind.catTagRank, palette, R.drawable.ic_loyalty_black_24dp);
        styleCatChip(baseBind.catWallpaperRank, palette, R.drawable.ic_setcat_photo);
        styleCatChip(baseBind.catSeriesRank, palette, R.drawable.ic_baseline_list_24);
        styleCatChip(baseBind.catMonthRank, palette, R.drawable.ic_fiber_new_black_24dp);
        styleCatChip(baseBind.catNovelLengthRank, palette, R.drawable.ic_baseline_menu_book_24);
        styleCatChip(baseBind.catSfwRank, palette, R.drawable.ic_check_circle_black_24dp);
        styleCatChip(baseBind.catTrendingArtists, palette, R.drawable.outline_whatshot_24);
        styleCatChip(baseBind.catDailyRecommendations, palette, R.drawable.ic_date_range_black_24dp);
        styleCatChip(baseBind.catUgoiraRank, palette, R.drawable.ic_baseline_play_arrow_24);
        styleCatChip(baseBind.catWalk, palette, R.drawable.ic_collections_black_24dp);
        styleCatChip(baseBind.catPixivComic, palette, R.drawable.ic_baseline_palette_24);
        styleCatChip(baseBind.catFollowNovel, palette, R.drawable.ic_baseline_bookmark_24);
        styleCatChip(baseBind.catDiscovery, palette, R.drawable.ic_baseline_explore_24);
        styleCatChip(baseBind.catWebDiscovery, palette, R.drawable.ic_setcat_globe);
        styleCatChip(baseBind.catWeb, palette, R.drawable.ic_setcat_globe);
        styleCatChip(baseBind.catFanbox, palette, R.drawable.ic_setcat_heart);
        styleCatChip(baseBind.catNiceFriend, palette, R.drawable.ic_baseline_how_to_reg_24);
        styleCatChip(baseBind.catCorpusLibrary, palette, R.drawable.ic_setcat_search);

        discoverVM = new ViewModelProvider(this).get(DiscoverViewModel.class);
        discoverVM.getPrimeTags().observe(getViewLifecycleOwner(), this::bindTagRail);
        discoverVM.getLatest().observe(getViewLifecycleOwner(),
                list -> bindIllustRail(baseBind.latestSection, baseBind.latestRail, list));
        discoverVM.getSiteRecommend().observe(getViewLifecycleOwner(),
                list -> bindIllustRail(baseBind.siteSection, baseBind.siteRail, list));
        discoverVM.getRecentHot().observe(getViewLifecycleOwner(),
                list -> bindIllustRail(baseBind.recentSection, baseBind.recentRail, list));
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

    @Override
    public void lazyData() {
        FragmentTransaction transaction = getChildFragmentManager().beginTransaction();
        pivisionFragment = new ceui.pixiv.ui.pivision.PivisionRailFeedFragment();
        transaction.add(R.id.fragment_pivision, pivisionFragment, "PivisionRailFeedFragment");
        transaction.commitNowAllowingStateLoss();

        if (discoverVM != null) {
            discoverVM.start(includeServerShelves());
        }
    }


    public void forceRefresh() {
        if (discoverVM != null) {
            discoverVM.reload(includeServerShelves());
        }
        if (pivisionFragment != null) {
            pivisionFragment.forceRefresh();
        }
    }
}
