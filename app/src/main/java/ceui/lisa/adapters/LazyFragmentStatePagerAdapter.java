package ceui.lisa.adapters;

import android.os.Bundle;
import android.os.Parcelable;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;
import androidx.lifecycle.Lifecycle;
import androidx.viewpager.widget.PagerAdapter;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

/**
 * 与 androidx 的 {@code FragmentStatePagerAdapter} 逐行一致，**只改一处**：
 * {@link #instantiateItem} 里给新页设的生命周期从 {@code STARTED} 降为 {@code CREATED}。
 *
 * <p>为什么：{@code STARTED} 会立刻走 {@code onCreateView}，而 {@code VActivity} 的 ViewPager
 * （{@code offscreenPageLimit=1}）在**首帧的 onMeasure → populate** 里就会把 cur-1 / cur / cur+1
 * 三页一起建出来。实测经典详情页三页 shell inflate 193ms + updateIllust 62ms，整条 doFrame
 * 435ms（54 帧），表现为「进页要等半秒」。{@code CREATED} 让新页停在 onCreateView **之前**，
 * 首帧只建当前页。
 *
 * <p>但**不能一直停着** —— 相邻页要赶在用户横滑之前就绪，否则滑过去要现场 inflate。所以首帧
 * 绘制完成后，调用方逐帧调 {@link #releaseOneDeferredPage()}，把相邻页提到 STARTED 触发它们的
 * onCreateView；每帧只放行一页，免得把两页的成本压进同一帧。
 *
 * <p>{@link #setPrimaryItem} **保持原样**：它把「新 current」提到 RESUMED、把「旧 current」降回
 * STARTED。于是「曾经是当前页」的页 View 仍然保留（横滑手感不变），只有「从没被访问过」的页
 * 才停在 CREATED。也因此 {@code FragmentIllust} 那套 onPause/onStop 结算逻辑完全不受影响。
 */
public abstract class LazyFragmentStatePagerAdapter extends PagerAdapter {

    private static final String TAG = "LazyFragmentStatePA";

    private final FragmentManager mFragmentManager;
    private FragmentTransaction mCurTransaction = null;
    private Fragment mCurrentPrimaryItem = null;
    private final ArrayList<Fragment> mFragments = new ArrayList<>();
    private final ArrayList<Fragment.SavedState> mSavedState = new ArrayList<>();
    private boolean mExecutingFinishUpdate;

    /** 首帧之前为 true：新建的页停在 CREATED，不走 onCreateView。 */
    private boolean mDeferNewPages = true;

    /** 已经放行过的页，避免重复提 lifecycle（重复提本身是 no-op，但会白开事务）。 */
    private final Set<Fragment> mReleased = new HashSet<>();

    public LazyFragmentStatePagerAdapter(@NonNull FragmentManager fm) {
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
        if (mFragments.size() > position) {
            Fragment f = mFragments.get(position);
            if (f != null) {
                return f;
            }
        }

        if (mCurTransaction == null) {
            mCurTransaction = mFragmentManager.beginTransaction();
        }

        Fragment fragment = getItem(position);
        if (mSavedState.size() > position) {
            Fragment.SavedState fss = mSavedState.get(position);
            if (fss != null) {
                fragment.setInitialSavedState(fss);
            }
        }
        while (mFragments.size() <= position) {
            mFragments.add(null);
        }

        fragment.setMenuVisibility(false);
        mFragments.set(position, fragment);
        mCurTransaction.add(container.getId(), fragment);
        // ⚠️ 与上游的唯一差异：上游这里是 Lifecycle.State.STARTED（会立刻 onCreateView）。
        mCurTransaction.setMaxLifecycle(fragment, mDeferNewPages
                ? Lifecycle.State.CREATED
                : Lifecycle.State.STARTED);

        return fragment;
    }

    @Override
    public void destroyItem(@NonNull ViewGroup container, int position, @NonNull Object object) {
        Fragment fragment = (Fragment) object;

        if (mCurTransaction == null) {
            mCurTransaction = mFragmentManager.beginTransaction();
        }
        while (mSavedState.size() <= position) {
            mSavedState.add(null);
        }
        mSavedState.set(position, fragment.isAdded()
                ? mFragmentManager.saveFragmentInstanceState(fragment)
                : null);
        mFragments.set(position, null);

        mCurTransaction.remove(fragment);
        if (fragment.equals(mCurrentPrimaryItem)) {
            mCurrentPrimaryItem = null;
        }
    }

    @Override
    public void setPrimaryItem(@NonNull ViewGroup container, int position, @NonNull Object object) {
        Fragment fragment = (Fragment) object;
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
            mCurrentPrimaryItem = fragment;
        }
    }

    @Override
    public void finishUpdate(@NonNull ViewGroup container) {
        if (mCurTransaction != null) {
            if (!mExecutingFinishUpdate) {
                try {
                    mExecutingFinishUpdate = true;
                    mCurTransaction.commitNowAllowingStateLoss();
                } finally {
                    mExecutingFinishUpdate = false;
                }
            }
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
        Bundle state = null;
        if (mSavedState.size() > 0) {
            state = new Bundle();
            Fragment.SavedState[] fss = new Fragment.SavedState[mSavedState.size()];
            mSavedState.toArray(fss);
            state.putParcelableArray("states", fss);
        }
        for (int i = 0; i < mFragments.size(); i++) {
            Fragment f = mFragments.get(i);
            if (f != null && f.isAdded()) {
                if (state == null) {
                    state = new Bundle();
                }
                String key = "f" + i;
                mFragmentManager.putFragment(state, key, f);
            }
        }
        return state;
    }

    @Override
    public void restoreState(@Nullable Parcelable state, @Nullable ClassLoader loader) {
        if (state != null) {
            Bundle bundle = (Bundle) state;
            bundle.setClassLoader(loader);
            Parcelable[] fss = bundle.getParcelableArray("states");
            mSavedState.clear();
            mFragments.clear();
            if (fss != null) {
                for (int i = 0; i < fss.length; i++) {
                    mSavedState.add((Fragment.SavedState) fss[i]);
                }
            }
            for (String key : bundle.keySet()) {
                if (key.startsWith("f")) {
                    int index = Integer.parseInt(key.substring(1));
                    Fragment f = mFragmentManager.getFragment(bundle, key);
                    if (f != null) {
                        while (mFragments.size() <= index) {
                            mFragments.add(null);
                        }
                        f.setMenuVisibility(false);
                        mFragments.set(index, f);
                    } else {
                        Log.w(TAG, "Bad fragment at key " + key);
                    }
                }
            }
        }
    }

    /**
     * 首帧绘制完成后由调用方**逐帧**调用：把一个还没放行的相邻页提到 STARTED，
     * 触发它的 onCreateView。
     *
     * @return true 表示这一帧放行了一页、调用方应继续排下一帧；false 表示没有待放行的页了
     *         （此后新建的页会直接以 STARTED 起步）。
     */
    public boolean releaseOneDeferredPage() {
        for (int i = 0; i < mFragments.size(); i++) {
            Fragment f = mFragments.get(i);
            if (f != null && f != mCurrentPrimaryItem && mReleased.add(f)) {
                FragmentTransaction ft = mFragmentManager.beginTransaction();
                ft.setMaxLifecycle(f, Lifecycle.State.STARTED);
                ft.commitNowAllowingStateLoss();
                return true;
            }
        }
        mDeferNewPages = false;
        return false;
    }
}
