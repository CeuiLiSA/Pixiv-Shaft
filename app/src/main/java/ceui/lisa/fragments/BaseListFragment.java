package ceui.lisa.fragments;

import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.support.v7.widget.Toolbar;
import android.view.View;
import android.widget.ImageView;
import android.widget.ProgressBar;

import com.scwang.smartrefresh.header.DeliveryHeader;
import com.scwang.smartrefresh.layout.api.RefreshLayout;

import java.util.ArrayList;
import java.util.List;

import ceui.lisa.R;
import ceui.lisa.interfs.Callback;
import ceui.lisa.interfs.ListShow;
import ceui.lisa.response.IllustsBean;
import ceui.lisa.utils.Common;
import ceui.lisa.utils.ListObserver;
import ceui.lisa.utils.Local;
import io.reactivex.Observable;
import io.reactivex.Observer;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.Disposable;
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
 * @param <ListItem>     列表数据元素
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
    protected String nextUrl = "";

    @Override
    void initLayout() {
        mLayoutID = R.layout.activity_simple_list;
    }

    @Override
    View initView(View v) {
        mToolbar = v.findViewById(R.id.toolbar);
        if(showToolbar()){
            mToolbar.setNavigationOnClickListener(view -> getActivity().finish());
            mToolbar.setTitle(getToolbarTitle());
        }else {
            if(mToolbar != null) {
                mToolbar.setVisibility(View.GONE);
            }
        }
        mProgressBar = v.findViewById(R.id.progress);
        mRecyclerView = v.findViewById(R.id.recyclerView);
        mRefreshLayout = v.findViewById(R.id.refreshLayout);
        initRecyclerView();
        mRefreshLayout.setRefreshHeader(new DeliveryHeader(mContext));
        mRefreshLayout.setOnRefreshListener(layout -> getFirstData());
        mRefreshLayout.setOnLoadMoreListener(layout -> getNextData());
        mRefreshLayout.setEnableLoadMore(hasNext());
        return v;
    }

    String getToolbarTitle(){
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

    void initRecyclerView(){

    }

    /**
     * the callback after getting the first page of datan
     *
     */
    abstract void initAdapter();

    /**
     * 获取第一波数据
     */
    public void getFirstData() {
        mApi = initApi();
        if (mApi != null) {
            mProgressBar.setVisibility(View.VISIBLE);
            mApi.subscribeOn(Schedulers.newThread())
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(new ListObserver<Response>() {

                        @Override
                        public void success(Response response) {
                            allItems.clear();
                            allItems.addAll(response.getList());
                            nextUrl = response.getNextUrl();
                            initAdapter();
                            mRefreshLayout.finishRefresh(true);
                            mProgressBar.setVisibility(View.INVISIBLE);
                            mRecyclerView.setAdapter(mAdapter);
                        }

                        @Override
                        public void dataError() {
                            mRefreshLayout.finishRefresh(false);
                            mProgressBar.setVisibility(View.INVISIBLE);
                        }

                        @Override
                        public void onComplete() {

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
            if(nextUrl.length() == 0){
                Common.showToast("next url 为空");
                mRefreshLayout.finishLoadMore(false);
            }else {
                mApi.subscribeOn(Schedulers.newThread())
                        .observeOn(AndroidSchedulers.mainThread())
                        .subscribe(new ListObserver<Response>() {
                            @Override
                            public void success(Response response) {
                                int lastSize = allItems.size();
                                allItems.addAll(response.getList());
                                nextUrl = response.getNextUrl();
                                mRefreshLayout.finishLoadMore(true);
                                if (mAdapter != null) {
                                    mAdapter.notifyItemRangeChanged(lastSize, response.getList().size());
                                }
                            }

                            @Override
                            public void dataError() {
                                mRefreshLayout.finishLoadMore(false);
                            }

                            @Override
                            public void onComplete() {

                            }
                        });
            }
        }
    }
}
