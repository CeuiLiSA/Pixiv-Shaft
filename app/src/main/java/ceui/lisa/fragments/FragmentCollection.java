package ceui.lisa.fragments;

import android.content.Intent;
import android.os.Bundle;
import android.view.MenuItem;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.Toolbar;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentPagerAdapter;
import androidx.viewpager.widget.ViewPager;

import com.ToxicBakery.viewpager.transforms.DrawerTransformer;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import ceui.lisa.R;
import ceui.lisa.activities.Shaft;
import ceui.lisa.activities.TemplateActivity;
import ceui.lisa.databinding.ViewpagerWithTablayoutBinding;
import ceui.lisa.utils.MyOnTabSelectedListener;
import ceui.lisa.utils.Params;
import ceui.pixiv.ui.collection.LikeNovelFeedFragment;
import ceui.pixiv.ui.user.FollowUserFeedFragment;

import ceui.pixiv.session.SessionManager;
import ceui.pixiv.ui.bulk.BulkActions;
import ceui.pixiv.ui.collection.LikeIllustFeedFragment;

import ceui.pixiv.witstudio.dialog.WitDialog;
import ceui.pixiv.ui.navigation.TemplateRoute;

public class FragmentCollection extends BaseFragment<ViewpagerWithTablayoutBinding> {

    private Fragment[] allPages;
    private String[] CHINESE_TITLES;

    private int type; //0插画收藏，1小说收藏，2关注, 3追更列表
    private final static Set<Integer> filterType = new HashSet<>(Arrays.asList(0,1));

    public static FragmentCollection newInstance(int type) {
        Bundle args = new Bundle();
        args.putInt(Params.DATA_TYPE, type);
        FragmentCollection fragment = new FragmentCollection();
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    protected void initBundle(Bundle bundle) {
        type = bundle.getInt(Params.DATA_TYPE);
    }

    @Override
    public void initLayout() {
        mLayoutID = R.layout.viewpager_with_tablayout;
    }

    @Override
    public void initView() {
        if (type == 0) {
            allPages = new Fragment[]{
                    LikeIllustFeedFragment.newInstance(SessionManager.INSTANCE.getLoggedInUid(),
                            Params.TYPE_PUBLIC),
                    LikeIllustFeedFragment.newInstance(SessionManager.INSTANCE.getLoggedInUid(),
                            Params.TYPE_PRIVATE)
            };
            CHINESE_TITLES = new String[]{
                    Shaft.getContext().getString(R.string.public_like_illust),
                    Shaft.getContext().getString(R.string.private_like_illust)
            };
        } else if (type == 1) {
            allPages = new Fragment[]{
                    LikeNovelFeedFragment.newInstance(SessionManager.INSTANCE.getLoggedInUid(),
                            Params.TYPE_PUBLIC, false),
                    LikeNovelFeedFragment.newInstance(SessionManager.INSTANCE.getLoggedInUid(),
                            Params.TYPE_PRIVATE, false)
            };
            CHINESE_TITLES = new String[]{
                    Shaft.getContext().getString(R.string.public_like_novel),
                    Shaft.getContext().getString(R.string.private_like_novel)
            };
        } else if (type == 2) {
            allPages = new Fragment[]{
                    FollowUserFeedFragment.newInstance(SessionManager.INSTANCE.getLoggedInUid(),
                            Params.TYPE_PUBLIC, false),
                    FollowUserFeedFragment.newInstance(SessionManager.INSTANCE.getLoggedInUid(),
                            Params.TYPE_PRIVATE, false)
            };
            CHINESE_TITLES = new String[]{
                    Shaft.getContext().getString(R.string.public_like_user),
                    Shaft.getContext().getString(R.string.private_like_user)
            };
        } else if (type == 3) {
            allPages = new Fragment[]{
                    new ceui.pixiv.ui.watchlist.WatchlistMangaFeedFragment(),
                    new ceui.pixiv.ui.watchlist.WatchlistNovelFeedFragment()
            };
            CHINESE_TITLES = new String[]{
                    Shaft.getContext().getString(R.string.type_manga),
                    Shaft.getContext().getString(R.string.type_novel)
            };
        }

        if (type == 0) {
            baseBind.toolbarTitle.setText(R.string.string_319);
        } else if (type == 1) {
            baseBind.toolbarTitle.setText(R.string.string_320);
        } else if (type == 2) {
            baseBind.toolbarTitle.setText(R.string.string_321);
        } else if (type == 3) {
            baseBind.toolbarTitle.setText(R.string.watchlist);
        }
        baseBind.toolbar.setNavigationOnClickListener(v -> mActivity.finish());
        baseBind.toolbar.setOnMenuItemClickListener(new Toolbar.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(MenuItem item) {
                String restrict = baseBind.viewPager.getCurrentItem() == 0
                        ? Params.TYPE_PUBLIC
                        : Params.TYPE_PRIVATE;
                if (item.getItemId() == R.id.action_more) {
                    showMoreActionsDialog(restrict);
                    return true;
                }
                if (item.getItemId() == R.id.action_jump_page) {
                    showJumpPageDialog();
                    return true;
                }
                if (item.getItemId() == R.id.action_filter) {
                    Intent intent = new Intent(mContext, TemplateActivity.class);
                    intent.putExtra(TemplateActivity.EXTRA_KEYWORD, restrict);
                    intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, TemplateRoute.BOOKED_TAG_FILTER.key);
                    intent.putExtra(Params.DATA_TYPE, type);
                    startActivity(intent);
                    return true;
                }
                return false;
            }
        });
        baseBind.viewPager.setPageTransformer(true, new DrawerTransformer());
        // 四种 type(0=插画收藏 1=小说收藏 2=关注 3=追更列表)现在全是 feeds 版,一律靠 onResume
        // 懒加载 —— 必须 RESUME_ONLY_CURRENT,否则相邻 tab 会被 RESUME、偷偷发一次请求。
        // (type 3 曾因两个 tab 还是 legacy BaseLazyFragment 而留过 USER_VISIBLE_HINT 特例:
        //  RESUME_ONLY_CURRENT 下 FragmentPagerAdapter 走 setMaxLifecycle、从不调
        //  setUserVisibleHint,而 mUserVisibleHint 默认 true → BaseLazyFragment 的懒加载守卫
        //  恒真 → 开页即全量加载。迁 feeds 后特例已无必要。)
        final int pagerBehavior = FragmentPagerAdapter.BEHAVIOR_RESUME_ONLY_CURRENT_FRAGMENT;
        baseBind.viewPager.setAdapter(new FragmentPagerAdapter(getChildFragmentManager(), pagerBehavior) {
            @NonNull
            @Override
            public Fragment getItem(int i) {
                return allPages[i];
            }

            @Override
            public int getCount() {
                return CHINESE_TITLES.length;
            }

            @Nullable
            @Override
            public CharSequence getPageTitle(int position) {
                return CHINESE_TITLES[position];
            }
        });
        baseBind.tabLayout.setupWithViewPager(baseBind.viewPager);
        MyOnTabSelectedListener listener = new MyOnTabSelectedListener(allPages);
        baseBind.tabLayout.addOnTabSelectedListener(listener);
        inflateToolbarMenu();
        baseBind.viewPager.addOnPageChangeListener(new ViewPager.OnPageChangeListener() {
            @Override
            public void onPageScrolled(int i, float v, int i1) {

            }

            @Override
            public void onPageSelected(int i) {
                baseBind.toolbar.getMenu().clear();
                inflateToolbarMenu();
            }

            @Override
            public void onPageScrollStateChanged(int i) {

            }
        });
    }

    /**
     * 插画 / 小说收藏页（type 0/1）的 toolbar 挂「按标签筛选」+ ⋯ overflow，
     * ⋯ 弹 WitDialog 选具体动作；关注页（type 2）另有自己的跳页菜单。
     */
    private void inflateToolbarMenu() {
        // 插画和小说都挂 ⋯：两边都有本地收藏库可进（差别只是插画那一项还多一个「下载全部作品」）
        if (filterType.contains(type)) {
            baseBind.toolbar.inflateMenu(R.menu.illust_collection_actions);
        } else if (type == 2) {
            baseBind.toolbar.inflateMenu(R.menu.follow_user_jump);
        }
    }

    /**
     * "我的关注"页 toolbar 上的跳页入口：弹窗 + offset 跳转都在 FragmentFollowUser 里，
     * 容器只负责定位当前 viewpager 子页并把请求转过去。
     */
    private void showJumpPageDialog() {
        if (mActivity == null || mActivity.isFinishing()) return;
        int idx = baseBind.viewPager.getCurrentItem();
        if (idx < 0 || idx >= allPages.length) return;
        Fragment current = allPages[idx];
        if (current instanceof FollowUserFeedFragment) {
            // 取数改挂 fragment 自己的 viewLifecycleOwner,故是实例方法(内含 view==null 守卫)
            ((FollowUserFeedFragment) current).showJumpPageDialog();
        }
    }

    /**
     * ⋯ 点开后的二级菜单。集中收口同类操作，避免 toolbar 上挂一堆图标。
     *
     * 「按条件浏览收藏」排第一：它是本页唯一能做倒序 / 按标签 / 按作者 / 按年份筛的入口
     * （pixiv 的收藏接口只能从新到旧顺着翻，见 BookmarkMirrorEntity 的类注释），
     * 也是用户最常真正需要的那件事。带上当前 tab 的 restrict —— 公开收藏和悄悄收藏
     * 在本地是两个独立书架，进错了就是空的。
     */
    private void showMoreActionsDialog(String restrict) {
        if (mActivity == null || mActivity.isFinishing()) return;
        // 「下载全部作品」只有插画侧有（批量下载管线吃的是 illust）；小说侧只出收藏库这一项。
        String[] items = type == 0
                ? new String[]{
                        getString(R.string.bookmark_library_menu_entry),
                        getString(R.string.bulk_collection_menu_download_all)
                }
                : new String[]{
                        getString(R.string.bookmark_library_menu_entry)
                };
        new WitDialog.MenuDialogBuilder(mActivity)
                .addItems(items, (dialog, which) -> {
                    dialog.dismiss();
                    if (which == 0) {
                        Intent intent = new Intent(mContext, TemplateActivity.class);
                        intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, TemplateRoute.BOOKMARK_LIBRARY.key);
                        intent.putExtra(Params.STAR_TYPE, restrict);
                        intent.putExtra(
                                ceui.pixiv.ui.library.BookmarkLibraryUi.ARG_CONTENT_TYPE,
                                type == 1
                                        ? ceui.pixiv.db.mirror.MirrorContentType.NOVEL.getCode()
                                        : ceui.pixiv.db.mirror.MirrorContentType.ILLUST.getCode());
                        startActivity(intent);
                    } else if (which == 1) {
                        long uid = SessionManager.INSTANCE.getLoggedInUid();
                        String restrictLabel = getString(restrict.equals(Params.TYPE_PUBLIC)
                                ? R.string.public_like_illust
                                : R.string.private_like_illust);
                        String taskName = getString(R.string.bulk_collection_task_name, restrictLabel);
                        BulkActions.startBookmarkIllustBulkDownload(
                                requireActivity(), uid, restrict, taskName);
                    }
                })
                .show();
    }
}
