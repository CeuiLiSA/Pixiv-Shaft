package ceui.lisa.interfaces;

import android.content.Context;

import com.scwang.smartrefresh.header.MaterialHeader;
import com.scwang.smartrefresh.layout.api.RefreshFooter;
import com.scwang.smartrefresh.layout.api.RefreshHeader;
import com.scwang.smartrefresh.layout.footer.ClassicsFooter;

import java.util.List;

import ceui.lisa.http.NullCtrl;
import io.reactivex.Observable;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.schedulers.Schedulers;

public abstract class NetControl<Response> {

    private Observable<Response> mApi;

    public abstract Observable<Response> initApi();

    public abstract Observable<Response> initNextApi();

    public void getFirstData(NullCtrl<Response> nullCtrl) {
        mApi = initApi();
        if (mApi != null) {
            mApi.subscribeOn(Schedulers.newThread())
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(nullCtrl);
        }
    }

    public void getNextData(NullCtrl<Response> nullCtrl) {
        mApi = initNextApi();
        if (mApi != null) {
            mApi.subscribeOn(Schedulers.newThread())
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(nullCtrl);
        }
    }

    public boolean hasNext(){
        return true;
    }

    public boolean enableRefresh(){
        return true;
    }

    public RefreshHeader getHeader(Context context){
        return new MaterialHeader(context);
    }

    public RefreshFooter getFooter(Context context){
        return new ClassicsFooter(context);
    }
}
