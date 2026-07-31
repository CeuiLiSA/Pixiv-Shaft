package ceui.lisa.fragments;

import android.content.Intent;
import android.view.View;
import android.view.ViewGroup;

import androidx.core.view.GravityCompat;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;
import androidx.lifecycle.ViewModelProvider;

import ceui.lisa.R;
import ceui.lisa.activities.MainActivity;
import ceui.lisa.activities.Shaft;
import ceui.lisa.activities.TemplateActivity;
import ceui.lisa.databinding.FragmentNewRightBinding;
import ceui.lisa.utils.Common;
import ceui.lisa.utils.Dev;
import ceui.lisa.utils.Local;
import ceui.lisa.utils.Params;
import ceui.lisa.utils.QMUIMenuPopup;
import ceui.lisa.utils.V3Palette;
import ceui.lisa.view.OnCheckChangeListener;
import ceui.pixiv.ui.dynamic.DynamicPageViewModel;
import ceui.pixiv.ui.dynamic.FollowingIllustFeedFragment;
import ceui.pixiv.ui.dynamic.FollowingNovelFeedFragment;
import ceui.pixiv.ui.user.RecmdUserRailFeedFragment;

/**
 * 「动态」tab 的外壳:固定 header(抽屉 + 标题 + 搜索) + 推荐用户货架 + 内容 sheet(类型菜单 /
 * 布局切换 / 全部·公开·私人 筛选条)。三块内容都是各自独立的子 fragment,本类只负责摆放与转发,
 * 自己不碰网络、不持有列表数据——与 FragmentLeft / FragmentPv 同一套「宿主留 legacy、
 * 列表走 feeds」的分工。
 *
 * <ul>
 *   <li>插画/漫画:{@link FollowingIllustFeedFragment}(feeds,替代 legacy NetListFragment +
 *       RightRepo + IAdapter/TimelineAdapter);</li>
 *   <li>小说:{@link FollowingNovelFeedFragment}(feeds,替代 legacy FragmentNewNovels +
 *       NewNovelRepo;同一个类也供 TemplateActivity「关注者的小说」独立页用,只是带 toolbar);</li>
 *   <li>货架:{@link RecmdUserRailFeedFragment}(feeds)。</li>
 * </ul>
 *
 * 两条列表现在是同一套契约(setRestrict 变了才重拉 / forceRefresh / scrollToTop),本类对它们
 * 一视同仁。
 *
 * 页面状态(筛选范围 / 插画还是小说)放 {@link DynamicPageViewModel},跟着列表数据一起跨视图重建
 * 存活;子 fragment 一律按 tag 复用,不重复 add(旋转后 FragmentManager 会先把它们恢复回来)。
 */
public class FragmentRight extends BaseLazyFragment<FragmentNewRightBinding> {

    private static final String TAG_RAIL = "RecmdUserRailFeedFragment";
    private static final String TAG_ILLUST = "FollowingIllustFeedFragment";
    private static final String TAG_NOVEL = "FollowingNovelFeedFragment";

    /** 筛选条三个位置对应的 restrict 值(顺序与 GlareLayout 的 左/中/右 一致)。 */
    private static final String[] RESTRICT_BY_INDEX = {
            Params.TYPE_ALL, Params.TYPE_PUBLIC, Params.TYPE_PRIVATE
    };

    private DynamicPageViewModel pageModel;
    private RecmdUserRailFeedFragment railFragment;
    private FollowingIllustFeedFragment illustFragment;
    private FollowingNovelFeedFragment novelFragment;

    @Override
    public void initLayout() {
        mLayoutID = R.layout.fragment_new_right;
    }

    @Override
    public void initView() {
        super.initView();
        pageModel = new ViewModelProvider(this).get(DynamicPageViewModel.class);
        tintContentSheet();

        if (Dev.hideMainActivityStatus) {
            ViewGroup.LayoutParams headParams = baseBind.head.getLayoutParams();
            headParams.height = Shaft.statusHeight;
            baseBind.head.setLayoutParams(headParams);
        }

        baseBind.drawerButton.setOnClickListener(v -> {
            if (mActivity instanceof MainActivity) {
                ((MainActivity) mActivity).getDrawer().openDrawer(GravityCompat.START, true);
            }
        });
        baseBind.searchBar.setOnClickListener(v -> {
            Intent intent = new Intent(mContext, TemplateActivity.class);
            intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "搜索");
            startActivity(intent);
        });
        baseBind.seeMore.setOnClickListener(v -> openRecmdUserPage());

        // 筛选条:视图重建后先按页面状态回填选中态(控件自身默认是「全部」),再挂监听,
        // 免得回填动作被当成用户操作触发一次重拉
        baseBind.glareLayout.setCurrentState(indexOfRestrict(pageModel.getRestrict()));
        baseBind.glareLayout.setListener(new OnCheckChangeListener() {
            @Override
            public void onSelect(int index, View view) {
                if (index >= 0 && index < RESTRICT_BY_INDEX.length) {
                    pageModel.setRestrict(RESTRICT_BY_INDEX[index]);
                }
                // 只推给当前这条列表:另一条切回来时 applyMode 会补推(值没变就是 no-op),
                // 不为看不见的列表白发请求(对齐 legacy 的懒同步)
                pushRestrictToActiveList();
            }

            @Override
            public void onReselect(int index, View view) {
                refreshActiveList();
            }
        });

        baseBind.dynamicTypeSwitcher.setOnClickListener(this::showTypeSwitcherMenu);
        baseBind.dynamicTitleLayout.setOnClickListener(v -> scrollActiveListToTop());
        baseBind.timelineToggle.setOnClickListener(v -> toggleTimelineMode());
        renderTimelineToggle();
        // 视图每次重建都要按页面状态摆一次:首次进来是默认的「插画」,旋转/深色切换后
        // 是用户上次选的那个模式。这里只碰视图——子 fragment 要等 lazyData(见下)。
        renderModeChrome();
    }

    @Override
    public void lazyData() {
        ensureChildFragments();
        applyMode();
    }

    /**
     * 建/找回三个子 fragment。旋转后 FragmentManager 已经把它们恢复进各自容器里了,
     * 必须按 tag 认领而不是无脑 add——legacy 就是无脑 add,旋转一次货架就多出一排。
     */
    private void ensureChildFragments() {
        FragmentManager fm = getChildFragmentManager();
        railFragment = (RecmdUserRailFeedFragment) fm.findFragmentByTag(TAG_RAIL);
        illustFragment = (FollowingIllustFeedFragment) fm.findFragmentByTag(TAG_ILLUST);
        novelFragment = (FollowingNovelFeedFragment) fm.findFragmentByTag(TAG_NOVEL);

        FragmentTransaction transaction = fm.beginTransaction();
        boolean any = false;
        if (railFragment == null) {
            railFragment = new RecmdUserRailFeedFragment();
            transaction.add(R.id.user_recmd_fragment, railFragment, TAG_RAIL);
            any = true;
        }
        if (illustFragment == null) {
            illustFragment = new FollowingIllustFeedFragment();
            transaction.add(R.id.illust_list_container, illustFragment, TAG_ILLUST);
            any = true;
        }
        if (any) {
            transaction.commitNowAllowingStateLoss();
        }
    }

    /**
     * 小说列表按需建(用户切到小说模式才建,没进过就不存在,也就不发请求)。
     * 建的时候就把当前 restrict 交给它:它的 VM 会在 onCreate 里播种,赶在 feedViewModel
     * 首屏之前——否则会先按默认「全部」拉一次再被 setRestrict 重拉。
     */
    private void ensureNovelFragment() {
        if (novelFragment != null) {
            return;
        }
        novelFragment = FollowingNovelFeedFragment.newInstance(false, pageModel.getRestrict());
        getChildFragmentManager().beginTransaction()
                .add(R.id.novel_list_container, novelFragment, TAG_NOVEL)
                .commitNowAllowingStateLoss();
    }

    private void showTypeSwitcherMenu(View anchor) {
        CharSequence[] titles = new CharSequence[]{
                mContext.getString(R.string.dynamic_type_illust_manga),
                mContext.getString(R.string.string_171)
        };
        QMUIMenuPopup.show(mContext, anchor, titles, (index, text) -> switchMode(index == 0));
    }

    private void switchMode(boolean wantIllust) {
        if (pageModel.isIllustMode() == wantIllust) {
            return;
        }
        pageModel.setIllustMode(wantIllust);
        applyMode();
    }

    /**
     * 纯视图部分:两条列表容器的显隐 + 类型文案 + 布局按钮显隐。不碰子 fragment,
     * 所以视图一建好就能调(此时 {@link #ensureChildFragments()} 可能还没跑)。
     */
    private void renderModeChrome() {
        boolean illust = pageModel.isIllustMode();
        baseBind.illustListContainer.setVisibility(illust ? View.VISIBLE : View.GONE);
        baseBind.novelListContainer.setVisibility(illust ? View.GONE : View.VISIBLE);
        // 布局切换只对插画列表有意义(小说列表只有一种排布)
        baseBind.timelineToggle.setVisibility(illust ? View.VISIBLE : View.GONE);
        baseBind.dynamicTypeSwitcher.setText(
                illust ? R.string.dynamic_type_illust_manga : R.string.string_171);
    }

    /**
     * 摆视图 + 保证该露面的那条列表存在,并把筛选范围补推给它——它可能在后台错过了一次切换。
     * 依赖子 fragment 已经认领过({@link #ensureChildFragments()}),否则 ensureNovelFragment
     * 会在 FragmentManager 已恢复出一个的情况下再建一个。
     */
    private void applyMode() {
        renderModeChrome();
        if (!pageModel.isIllustMode()) {
            ensureNovelFragment();
        }
        pushRestrictToActiveList();
    }

    /** 两条列表的 setRestrict 都是「变了才重拉」,所以补推是安全的幂等操作。 */
    private void pushRestrictToActiveList() {
        String restrict = pageModel.getRestrict();
        if (pageModel.isIllustMode()) {
            if (illustFragment != null) {
                illustFragment.setRestrict(restrict);
            }
        } else if (novelFragment != null) {
            novelFragment.setRestrict(restrict);
        }
    }

    /**
     * 内容 sheet 的底色改跟主题走。
     *
     * 布局里 content_item 写的是静态 v3_menu_bg，夜间是写死的 #1A1A2E（藏青）——主题色换成
     * 绿/粉时，这张 sheet 就成了页面上一条突兀的蓝带。改用 V3Palette.cardFill（隐约带主题色的
     * 不透明悬浮底，日夜双模，与设置卡/悬浮胶囊同一个值）。
     *
     * QMUIRoundLinearLayout 的背景是 QMUIRoundDrawable（继承 GradientDrawable），只 setColor
     * 不换 drawable，20dp 上圆角和 behavior 都不受影响。切主题/日夜会重建 Fragment → 这里重算。
     * 列表那半边由 FollowingIllustFeedFragment.feedRootBackgroundColor 取同一个值，两边同源。
     */
    private void tintContentSheet() {
        android.graphics.drawable.Drawable bg = baseBind.contentItem.getBackground();
        if (bg instanceof android.graphics.drawable.GradientDrawable) {
            ((android.graphics.drawable.GradientDrawable) bg)
                    .setColor(V3Palette.from(mContext).getCardFill());
        }
    }

    private void refreshActiveList() {
        if (pageModel.isIllustMode()) {
            if (illustFragment != null) {
                illustFragment.forceRefresh();
            }
        } else if (novelFragment != null) {
            novelFragment.forceRefresh();
        }
    }

    private void scrollActiveListToTop() {
        if (pageModel.isIllustMode()) {
            if (illustFragment != null) {
                illustFragment.scrollToTop();
            }
        } else if (novelFragment != null) {
            novelFragment.scrollToTop();
        }
    }

    /** 布局模式切换:写设置项(设置页「关注动态布局模式」同一个开关) → 让插画列表按新排布重装。 */
    private void toggleTimelineMode() {
        Shaft.sSettings.setUseStaggeredLayout(!Shaft.sSettings.isUseStaggeredLayout());
        Local.setSettings(Shaft.sSettings);
        renderTimelineToggle();
        if (illustFragment != null) {
            illustFragment.applyLayoutMode();
        }
    }

    private void renderTimelineToggle() {
        boolean timeline = !Shaft.sSettings.isUseStaggeredLayout();
        int color = timeline
                ? Common.resolveThemeAttribute(mContext, androidx.appcompat.R.attr.colorPrimary)
                : mContext.getResources().getColor(R.color.glare_unselected_text);
        baseBind.timelineToggle.setColorFilter(color);
    }

    private static int indexOfRestrict(String restrict) {
        for (int i = 0; i < RESTRICT_BY_INDEX.length; i++) {
            if (RESTRICT_BY_INDEX[i].equals(restrict)) {
                return i;
            }
        }
        return 0;
    }

    private void openRecmdUserPage() {
        Intent intent = new Intent(mContext, TemplateActivity.class);
        intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "推荐用户");
        String handoffKey = null;
        kotlin.Pair<java.util.List<ceui.loxia.UserPreview>, String> snapshot =
                railFragment != null && railFragment.getView() != null
                        ? railFragment.currentSnapshot() : null;
        if (snapshot != null && !snapshot.getFirst().isEmpty()) {
            // Hand off via in-memory map rather than Intent extras: the
            // full UserPreview graph easily exceeds the ~1MB binder
            // transaction limit and crashes on Android 15 (#820). We
            // still want the detail page to show the same batch the
            // user was just looking at, so stash a snapshot under a
            // unique key and pass only the key.
            handoffKey = RecmdUserHandoff.put(new RecmdUserSnapshot(
                    snapshot.getFirst(),
                    snapshot.getSecond()
            ));
            intent.putExtra(Params.USER_MODEL, handoffKey);
        }
        // 即便我们的 intent 自身只装了 2 个 String，部分机型/系统版本会在
        // startActivity 的 binder 事务里附带调用方 Activity 的状态快照，
        // 仍可能触发 TransactionTooLargeException。兜底捕获，避免崩溃。
        try {
            startActivity(intent);
        } catch (RuntimeException e) {
            Common.showLog("FragmentRight seeMore startActivity failed: " + e.getMessage());
            if (handoffKey != null) {
                RecmdUserHandoff.discard(handoffKey);
            }
            Common.showToast("打开页面失败，请稍后重试");
        }
    }

    /** 底栏「动态」再点一次:回顶 + 重拉当前这条列表(MainActivity 调)。 */
    public void forceRefresh() {
        if (pageModel == null) {
            // 视图还没建过（pager 里的孤儿实例，见 FeedFragment.forceRefresh 注释）
            return;
        }
        refreshActiveList();
    }
}
