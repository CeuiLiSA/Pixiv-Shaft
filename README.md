# Shaft



### 所有的列表均继承自BaseListFragment 这样一来将会大大减少代码量，子类只需要这样
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
    }
}
```





######有亮眼的设计的同学，请联系fatemercis@qq.com或留下链接/截图，十分感谢
![截图](https://raw.githubusercontent.com/CeuiLiSA/Shaft/master/snap/Screenshot_1554187583.png)
