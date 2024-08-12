package ceui.lisa.core;

import ceui.lisa.fragments.NetListFragment;
import ceui.lisa.http.NullCtrl;
import ceui.lisa.interfaces.ListShow;
import io.reactivex.Observable;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.functions.Function;
import io.reactivex.schedulers.Schedulers;

/**
 * The class stores response got from remote repo (pixiv) in the form of {@link ListShow}
 * */
public abstract class RemoteRepo<Response extends ListShow<?>> extends BaseRepo {

    /**
     * In the context of Android and RxJava, ? extends Response refers to a generic type used with Observables. Here's a breakdown:
     * <p>
     * ?: This symbol represents a wildcard. It indicates that the specific type of object the Observable emits is unknown, but it's guaranteed to be a subtype of Response.
     * extends Response: This part specifies that the type can be either the Response class itself or any class that inherits from Response. In other words, the Observable can emit objects of any type as long as that type is a subclass of Response.
     * */
    private Observable<? extends Response> mApi;
    /**
     * In the context of RxJava and Android, you'll likely not encounter ? super Response very often. It's a less common generic type compared to ? extends Response. Here's why:
     * <p>
     * ?: Similar to ? extends Response, this represents a wildcard but with a reversed relationship.
     * super Response: This specifies that the type can be either the Response class itself or any class that is a superclass of Response. In simpler terms, the Observable can emit objects of any type as long as that type is an ancestor (parent class) in the inheritance hierarchy leading up to Response.
     * */
    private final Function<? super Response, Response> mFunction;
    protected String nextUrl = "";

    public RemoteRepo() {
        mFunction = mapper();
    }

    /**
     * An interface overrided in different class to init different Api depending on the response type
     * <p>
     * For expample:
     * <p>
     * The mRemoteRepo in {@link NetListFragment} of homepage is {@link ceui.lisa.model.RecmdIllust}
     * <p>
     * While mRemoteRepo in {@link NetListFragment} of rank page is {@link ceui.lisa.repo.RankIllustRepo}
     * */
    public abstract Observable<? extends Response> initApi();

//    /**
//     * Early development,it only returns JSON Array now
//     * */
//    public abstract Observable<? extends Response> initLofterApi();

    public abstract Observable<? extends Response> initNextApi();

    /**
     * Init Api and POST request to get response containing information of illustrations
     * @param nullCtrl (In doubt)In case of null
     * */
    public void getFirstData(NullCtrl<Response> nullCtrl) {
        mApi = initApi();//mApi contains the response data
        if (mApi != null) {
            mApi.subscribeOn(Schedulers.newThread())
                    .map(mFunction)
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(nullCtrl);
        }
    }

//    public void getLofterFirstData(NullCtrl<Response> nullCtrl) {
//        mApi = initLofterApi();//mApi contains the response data
//        if (mApi != null) {
//            mApi.subscribeOn(Schedulers.newThread())
//                    .map(mFunction)
//                    .observeOn(AndroidSchedulers.mainThread())
//                    .subscribe(nullCtrl);
//        }
//    }

    public void getNextData(NullCtrl<Response> nullCtrl) {
        mApi = initNextApi();
        if (mApi != null) {
            mApi.subscribeOn(Schedulers.newThread())
                    .map(mFunction)
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(nullCtrl);
        }
    }

    public Function<? super Response, Response> mapper() {
        return new Mapper<>();
    }

    public String getNextUrl() {
        return nextUrl;
    }

    public void setNextUrl(String nextUrl) {
        this.nextUrl = nextUrl;
    }

    public boolean hasEffectiveUserFollowStatus() {
        return true;
    }
}
