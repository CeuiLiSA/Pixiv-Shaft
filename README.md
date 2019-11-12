# Shaft

<a href="https://play.google.com/store/apps/details?id=ceui.lisa">
<img alt="Get it on Google Play" src="https://play.google.com/intl/en_us/badges/images/generic/en_badge_web_generic.png" 
width="330" height="128" />
</a>

```java

public class FragmentRecmdUser extends NetListFragment<FragmentBaseListBinding,
        ListUserResponse, UserPreviewsBean, RecyUserPreviewBinding> {

    @Override
    public NetControl<ListUserResponse> present() {
        return new NetControl<ListUserResponse>() {
            @Override
            public Observable<ListUserResponse> initApi() {
                return Retro.getAppApi().getRecmdUser(sUserModel.getResponse().getAccess_token());
            }

            @Override
            public Observable<ListUserResponse> initNextApi() {
                return Retro.getAppApi().getNextUser(sUserModel.getResponse().getAccess_token(), nextUrl);
            }
        };
    }

    @Override
    public BaseAdapter<UserPreviewsBean, RecyUserPreviewBinding> adapter() {
        return new UAdapter(allItems, mContext);
    }
}
```

