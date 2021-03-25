package ceui.lisa.core;

import ceui.lisa.http.NullCtrl;
import ceui.lisa.interfaces.ListShow;
import io.reactivex.Observable;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.functions.Function;
import io.reactivex.schedulers.Schedulers;

public abstract class RemoteRepo<Response extends ListShow<?>> extends BaseRepo {

    private Observable<? extends Response> mApi;
    private Function<? super Response, Response> mFunction;
    protected String nextUrl = "";

    public RemoteRepo() {
        mFunction = mapper();
    }

    public abstract Observable<? extends Response> initApi();

    public abstract Observable<? extends Response> initNextApi();

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
}
