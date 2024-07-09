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

    private Observable<? extends Response> mApi;
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

    public abstract Observable<? extends Response> initNextApi();

    /**
     * Init Api and POST request to get response containing information of illustrations
     * @param nullCtrl (In doubt)In case of null
     * */
    public void getFirstData(NullCtrl<Response> nullCtrl) {
        mApi = initApi();
        if (mApi != null) {
            mApi.subscribeOn(Schedulers.newThread())
                    .map(mFunction)
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(nullCtrl);
        }
    }

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
