package ceui.lisa.fragments;

import android.text.TextUtils;
import android.view.View;
import android.widget.ImageView;
import android.widget.ProgressBar;

import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.RecyclerView;

import com.scwang.smartrefresh.header.DeliveryHeader;
import com.scwang.smartrefresh.layout.api.RefreshLayout;
import com.scwang.smartrefresh.layout.footer.FalsifyFooter;

import java.util.ArrayList;
import java.util.List;

import ceui.lisa.R;
import ceui.lisa.activities.Shaft;
import ceui.lisa.interfaces.ListShow;
import ceui.lisa.utils.Common;
import ceui.lisa.utils.ListObserver;
import io.reactivex.Observable;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.schedulers.Schedulers;

/**
 * The {@code String} class represents character strings. All
 * string literals in Java programs, such as {@code "abc"}, are
 * implemented as instances of this class.
 * <p>
 * Strings are constant; their values cannot be changed after they
 * are created. String buffers support mutable strings.
 * Because String objects are immutable they can be shared. For example:
 * <blockquote><pre>
 *     String str = "abc";
 * </pre></blockquote><p>
 * is equivalent to:
 * <blockquote><pre>
 *     char data[] = {'a', 'b', 'c'};
 *     String str = new String(data);
 * </pre></blockquote><p>
 * Here are some more examples of how strings can be used:
 * <blockquote><pre>
 *     System.out.println("abc");
 *     String cde = "cde";
 *     System.out.println("abc" + cde);
 *     String c = "abc".substring(2,3);
 *     String d = cde.substring(1, 2);
 * </pre></blockquote>
 * <p>
 * The class {@code String} includes methods for examining
 * individual characters of the sequence, for comparing strings, for
 * searching strings, for extracting substrings, and for creating a
 * copy of a string with all characters translated to uppercase or to
 * lowercase. Case mapping is based on the Unicode Standard version
 * specified by the {@link Character Character} class.
 * <p>
 * The Java language provides special support for the string
 * concatenation operator (&nbsp;+&nbsp;), and for conversion of
 * other objects to strings. String concatenation is implemented
 * through the {@code StringBuilder}(or {@code StringBuffer})
 * class and its {@code append} method.
 * String conversions are implemented through the method
 * {@code toString}, defined by {@code Object} and
 * inherited by all classes in Java. For additional information on
 * string concatenation and conversion, see Gosling, Joy, and Steele,
 * <i>The Java Language Specification</i>.
 *
 * <p> Unless otherwise noted, passing a <tt>null</tt> argument to a constructor
 * or method in this class will cause a {@link NullPointerException} to be
 * thrown.
 *
 * <p>A {@code String} represents a string in the UTF-16 format
 * in which <em>supplementary characters</em> are represented by <em>surrogate
 * pairs</em> (see the section <a href="Character.html#unicode">Unicode
 * Character Representations</a> in the {@code Character} class for
 * more information).
 * Index values refer to {@code char} code units, so a supplementary
 * character uses two positions in a {@code String}.
 * <p>The {@code String} class provides methods for dealing with
 * Unicode code points (i.e., characters), in addition to those for
 * dealing with Unicode code units (i.e., {@code char} values).
 *
 * @param <Response> json解析累
 * @param <Adapter>  列表适配器
 * @param <ListItem> 列表数据元素
 */
public abstract class BaseListFragment<Response extends ListShow<ListItem>,
        Adapter extends RecyclerView.Adapter<RecyclerView.ViewHolder>,
        ListItem> extends BaseFragment {

    public static final int PAGE_SIZE = 20;
    protected Observable<Response> mApi;
    protected Adapter mAdapter;
    protected RecyclerView mRecyclerView;
    protected RefreshLayout mRefreshLayout;
    protected List<ListItem> allItems = new ArrayList<>();
    protected ProgressBar mProgressBar;
    protected Toolbar mToolbar;
    protected Response mResponse;
    protected String nextUrl = "";
    protected ImageView noData;

    public BaseListFragment() {
        Common.showLog(className + "new instance !!");
    }

    @Override
    void initLayout() {
        mLayoutID = R.layout.fragment_illust_list;
    }

    @Override
    View initView(View v) {
        mToolbar = v.findViewById(R.id.toolbar);
        if (showToolbar()) {
            mToolbar.setNavigationOnClickListener(view -> getActivity().finish());
            mToolbar.setTitle(getToolbarTitle());
        } else {
            if (mToolbar != null) {
                mToolbar.setVisibility(View.GONE);
            }
        }
        mProgressBar = v.findViewById(R.id.progress);
        mRecyclerView = v.findViewById(R.id.recyclerView);
        mRefreshLayout = v.findViewById(R.id.refreshLayout);
        noData = v.findViewById(R.id.no_data);
        noData.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                getFirstData();
            }
        });
        initRecyclerView();
        mRefreshLayout.setRefreshHeader(new DeliveryHeader(mContext));
        mRefreshLayout.setOnRefreshListener(layout -> getFirstData());
        mRefreshLayout.setEnableLoadMore(false);
        if (hasNext()) {
            mRefreshLayout.setOnLoadMoreListener(layout -> getNextData());
        }
        return v;
    }

    String getToolbarTitle() {
        return " ";
    }

    @Override
    void initData() {
        getFirstData();
    }


    boolean hasNext() {
        return true;
    }

    boolean showToolbar() {
        return true;
    }

    /**
     *
     */
    abstract Observable<Response> initApi();

    abstract Observable<Response> initNextApi();

    void initRecyclerView() {

    }

    /**
     * the callback after getting the first page of datan
     */
    abstract void initAdapter();

    /**
     * 获取第一波数据
     */
    public void getFirstData() {
        mApi = initApi();
        if (mApi != null) {
            mProgressBar.setVisibility(View.VISIBLE);
            noData.setVisibility(View.INVISIBLE);
            mApi.subscribeOn(Schedulers.newThread())
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(new ListObserver<Response>() {
                        @Override
                        public void success(Response response) {
                            mResponse = response;
                            allItems.clear();
                            allItems.addAll(response.getList());
                            nextUrl = response.getNextUrl();
                            if (!TextUtils.isEmpty(nextUrl)) {
                                if (className.contains("FragmentRelatedIllust")) {
                                    mRefreshLayout.setEnableLoadMore(Shaft.sSettings.isRelatedIllustNoLimit());
                                } else {
                                    mRefreshLayout.setEnableLoadMore(true);
                                }
                            } else {
                                mRefreshLayout.setEnableLoadMore(false);
                            }
                            initAdapter();
                            mRefreshLayout.finishRefresh(true);
                            mProgressBar.setVisibility(View.INVISIBLE);
                            mRecyclerView.setVisibility(View.VISIBLE);
                            noData.setVisibility(View.GONE);
                            mRecyclerView.setAdapter(mAdapter);
                        }

                        @Override
                        public void dataError() {
                            mRefreshLayout.finishRefresh(false);
                            mProgressBar.setVisibility(View.INVISIBLE);
                            mRecyclerView.setVisibility(View.INVISIBLE);
                            noData.setVisibility(View.VISIBLE);
                            noData.setImageResource(R.mipmap.no_data);
                        }

                        @Override
                        public void netError() {
                            mRecyclerView.setVisibility(View.INVISIBLE);
                            noData.setVisibility(View.VISIBLE);
                            noData.setImageResource(R.mipmap.load_error);
                        }
                    });
        }
    }

    /**
     * 获取后续数据
     */
    public void getNextData() {
        mApi = initNextApi();
        if (mApi != null) {
            if (TextUtils.isEmpty(nextUrl)) {
                Common.showLog("next url 为空");
                mRefreshLayout.setEnableLoadMore(false);
                mRefreshLayout.finishLoadMore(false);
                mRefreshLayout.setRefreshFooter(new FalsifyFooter(mContext));
            } else {
                mApi.subscribeOn(Schedulers.newThread())
                        .observeOn(AndroidSchedulers.mainThread())
                        .subscribe(new ListObserver<Response>() {
                            @Override
                            public void success(Response response) {
                                int lastSize;
                                if (className.equals("FragmentPivision ")) {
                                    lastSize = allItems.size() + 1;
                                } else {
                                    lastSize = allItems.size();
                                }
                                allItems.addAll(response.getList());
                                nextUrl = response.getNextUrl();
                                if (!TextUtils.isEmpty(nextUrl)) {
                                    if (className.contains("FragmentRelatedIllust")) {
                                        mRefreshLayout.setEnableLoadMore(Shaft.sSettings.isRelatedIllustNoLimit());
                                    } else {
                                        mRefreshLayout.setEnableLoadMore(true);
                                    }
                                } else {
                                    mRefreshLayout.setEnableLoadMore(false);
                                }
                                mRefreshLayout.finishLoadMore(true);
                                if (mAdapter != null) {
                                    mAdapter.notifyItemRangeInserted(lastSize, response.getList().size());
                                }
                            }

                            @Override
                            public void dataError() {
                                mRefreshLayout.finishLoadMore(true);
                                if (!TextUtils.isEmpty(nextUrl)) {
                                    mRefreshLayout.setEnableLoadMore(false);
                                }
                            }

                            @Override
                            public void netError() {

                            }

                        });
            }
        } else {
            mRefreshLayout.setEnableLoadMore(false);
            mRefreshLayout.finishLoadMore(false);
            mRefreshLayout.setRefreshFooter(new FalsifyFooter(mContext));
        }
    }
}
