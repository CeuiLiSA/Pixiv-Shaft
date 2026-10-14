package ceui.lisa.adapters;

import android.os.Parcelable;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;
import androidx.lifecycle.Lifecycle;
import androidx.viewpager.widget.PagerAdapter;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * 与 androidx 的 {@link androidx.fragment.app.FragmentPagerAdapter}（fragment-1.5.4）逐行一致，
 * 只改一处：新页不直接提到 {@link Lifecycle.State#STARTED}，而是停在 {@link Lifecycle.State#CREATED}
 * —— 停在 {@code onCreateView} 之前，没有 View、不 inflate。
 *
 * <p>动机：冷启动时 ViewPager 的 {@code setOffscreenPageLimit(length - 1)} 会把 4 个 tab 页
 * 全部 instantiate，每页都同步跑完 {@code onCreateView}。实测发现页 246ms + 动态页 46ms +
 * R18 页 60ms 全压在首帧里，而用户只看得到当前那一页。压到 CREATED 之后，首帧只建当前 tab，
 * 其余由 {@link #releaseOneDeferredPage()} 在首帧之后逐帧放行（参考 patch-10-10-4 对详情页
 * 相邻页的处理）。
 *
 * <p>与 androidx 的差异只有那一行 {@code STARTED → CREATED}（外加记录待放行页的 {@link #deferredPages}），
 * 升级 {@code androidx.fragment} 时对照同步即可。
 *
 * <p>放行用 {@code commitNowAllowingStateLoss} 把 maxLifecycle 提回 STARTED，不重建 Fragment 实例，
 * 也不影响「曾经是当前页」的页 —— 那些页已被 {@link #setPrimaryItem} 提到 STARTED/RESUMED，
 * 不在 {@link #deferredPages} 里，横滑手感与改动前一致。
 */
public abstract class LazyFragmentPagerAdapter extends PagerAdapter {

    private final FragmentManager mFragmentManager;
    private FragmentTransaction mCurTransaction = null;
    private Fragment mCurrentPrimaryItem = null;

    /** 还停在 CREATED、等首帧之后逐帧放行的页。 */
    private final Deque<Fragment> deferredPages = new ArrayDeque<>();

    public LazyFragmentPagerAdapter(@NonNull FragmentManager fm) {
        mFragmentManager = fm;
    }

    @NonNull
    public abstract Fragment getItem(int position);

    @Override
    public void startUpdate(@NonNull ViewGroup container) {
        if (container.getId() == View.NO_ID) {
            throw new IllegalStateException(
                    "ViewPager with adapter " + this + " requires a view id");
        }
    }

    @NonNull
    @Override
    public Object instantiateItem(@NonNull ViewGroup container, int position) {
        if (mCurTransaction == null) {
            mCurTransaction = mFragmentManager.beginTransaction();
        }

        final long itemId = getItemId(position);
        final String name = makeFragmentName(container.getId(), itemId);
        Fragment fragment = mFragmentManager.findFragmentByTag(name);
        if (fragment != null) {
            mCurTransaction.attach(fragment);
        } else {
            fragment = getItem(position);
            mCurTransaction.add(container.getId(), fragment, name);
        }

        if (fragment != mCurrentPrimaryItem) {
            fragment.setMenuVisibility(false);
            // 与 androidx 唯一的分歧：STARTED → CREATED，把 view 的创建推迟到放行时。
            mCurTransaction.setMaxLifecycle(fragment, Lifecycle.State.CREATED);
            deferredPages.add(fragment);
        }

        return fragment;
    }

    @Override
    public void destroyItem(@NonNull ViewGroup container, int position, @NonNull Object object) {
        final Fragment fragment = (Fragment) object;

        if (mCurTransaction == null) {
            mCurTransaction = mFragmentManager.beginTransaction();
        }
        mCurTransaction.detach(fragment);
        deferredPages.remove(fragment);
        if (fragment == mCurrentPrimaryItem) {
            mCurrentPrimaryItem = null;
        }
    }

    @Override
    public void setPrimaryItem(@NonNull ViewGroup container, int position, @NonNull Object object) {
        final Fragment fragment = (Fragment) object;
        if (fragment != mCurrentPrimaryItem) {
            if (mCurrentPrimaryItem != null) {
                mCurrentPrimaryItem.setMenuVisibility(false);
                if (mCurTransaction == null) {
                    mCurTransaction = mFragmentManager.beginTransaction();
                }
                mCurTransaction.setMaxLifecycle(mCurrentPrimaryItem, Lifecycle.State.STARTED);
            }
            fragment.setMenuVisibility(true);
            if (mCurTransaction == null) {
                mCurTransaction = mFragmentManager.beginTransaction();
            }
            mCurTransaction.setMaxLifecycle(fragment, Lifecycle.State.RESUMED);
            // 它已经是当前页，view 这一帧就要建，不必再等放行。
            deferredPages.remove(fragment);

            mCurrentPrimaryItem = fragment;
        }
    }

    @Override
    public void finishUpdate(@NonNull ViewGroup container) {
        if (mCurTransaction != null) {
            mCurTransaction.commitNowAllowingStateLoss();
            mCurTransaction = null;
        }
    }

    @Override
    public boolean isViewFromObject(@NonNull View view, @NonNull Object object) {
        return ((Fragment) object).getView() == view;
    }

    @Nullable
    @Override
    public Parcelable saveState() {
        return null;
    }

    @Override
    public void restoreState(@Nullable Parcelable state, @Nullable ClassLoader loader) {
    }

    public long getItemId(int position) {
        return position;
    }

    /**
     * 放行一个还停在 {@code CREATED} 的页：把它的 maxLifecycle 提回 {@code STARTED}，
     * 这一帧同步跑完该页的 {@code onCreateView}。首帧之后逐帧调用。
     *
     * @return true = 放行了某一页；false = 已经放完（没有停在 CREATED 的页了）
     */
    public boolean releaseOneDeferredPage() {
        Fragment target = null;
        for (Fragment f : deferredPages) {
            if (f instanceof ceui.lisa.fragments.FragmentLeft) {
                target = f;
                break;
            }
        }
        if (target != null) {
            deferredPages.remove(target);
        } else {
            target = deferredPages.poll();
        }
        if (target == null) {
            return false;
        }
        final FragmentTransaction transaction = mFragmentManager.beginTransaction();
        transaction.setMaxLifecycle(target, Lifecycle.State.STARTED);
        transaction.commitNowAllowingStateLoss();
        return true;
    }

    private static String makeFragmentName(int viewId, long id) {
        return "android:switcher:" + viewId + ":" + id;
    }
}