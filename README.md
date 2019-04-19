# Shaft



### 所有的列表均继承自BaseListFragment
```java

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
        initRecyclerView();
        mRefreshLayout = v.findViewById(R.id.refreshLayout);
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
```



```java
    public static class IllustPip<Target>{

        private List<Target> beans = new ArrayList<>();

        List<Target> getBeans() {
            return beans;
        }

        void setBeans(List<Target> beans) {
            this.beans = beans;
        }
    }
```

```java
    public static <T> void getLocalIllust(Callback<List<T>> callback) {
        IllustPip<T> pip = new IllustPip<>();
        Observable.create((ObservableOnSubscribe<String>) emitter -> {
            emitter.onNext("开始读取本地文件");
            Common.showLog("Observable thread is : " + Thread.currentThread().getName());
            FileInputStream fis = Shaft.getContext().openFileInput("RecommendIllust");//获得输入流
            ObjectInputStream ois = new ObjectInputStream(fis);
            pip.setBeans((List<T>) ois.readObject());
            fis.close();
            ois.close();
            emitter.onNext("本地文件读取完成");
            emitter.onComplete();
        }).subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(new Observer<String>() {
                    @Override
                    public void onSubscribe(Disposable d) {

                    }

                    @Override
                    public void onNext(String s) {
                        Common.showLog(s);
                    }

                    @Override
                    public void onError(Throwable e) {
                        e.printStackTrace();
                        Common.showToast(e.toString());
                    }

                    @Override
                    public void onComplete() {
                        callback.doSomething(pip.getBeans());
                    }
                });
    }
```

### 这样一来将会大大减少代码量，子类只需要这样
```java

public class FragmentIllustList extends BaseListFragment<ListIllustResponse, IllustStagAdapter, IllustsBean> {

    @Override
    Observable<ListIllustResponse> initApi() {
        return Retro.getAppApi().getRank(mUserModel.getResponse().getAccess_token(), "day_male");
    }

    @Override
    Observable<ListIllustResponse> initNextApi() {
        return Retro.getAppApi().getNextIllust(mUserModel.getResponse().getAccess_token(), nextUrl);
    }

    @Override
    void initAdapter() {
        mAdapter = new IllustStagAdapter(allItems, mContext);
        mAdapter.setOnItemClickListener(new OnItemClickListener() {
            @Override
            public void onItemClick(View v, int position, int viewType) {
                Shaft.allIllusts.clear();
                Shaft.allIllusts.addAll(allItems);
                Intent intent = new Intent(mContext, ViewPagerActivity.class);
                intent.putExtra("position", position);
                startActivity(intent);
            }
        });
        mRecyclerView.addItemDecoration(new SpacesItemDecoration(DensityUtil.dp2px(4.0f)));
        StaggeredGridLayoutManager layoutManager =
                new StaggeredGridLayoutManager(2, StaggeredGridLayoutManager.VERTICAL);
        mRecyclerView.setLayoutManager(layoutManager);
    }
}
```


######有亮眼的设计的同学，请联系fatemercis@qq.com或留下链接/截图，十分感谢
![截图](https://raw.githubusercontent.com/CeuiLiSA/Shaft/master/snap/Screenshot_1554187583.png)
