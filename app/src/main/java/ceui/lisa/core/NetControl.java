package ceui.lisa.core;

import android.content.Context;
import android.text.TextUtils;

import com.scwang.smartrefresh.header.MaterialHeader;
import com.scwang.smartrefresh.layout.api.RefreshFooter;
import com.scwang.smartrefresh.layout.api.RefreshHeader;
import com.scwang.smartrefresh.layout.footer.ClassicsFooter;

import java.util.List;

import ceui.lisa.http.NullCtrl;
import ceui.lisa.interfaces.BaseCtrl;
import ceui.lisa.interfaces.ListShow;
import ceui.lisa.models.IllustsBean;
import ceui.lisa.utils.Common;
import ceui.lisa.utils.PixivOperate;
import io.reactivex.Observable;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.functions.Function;
import io.reactivex.schedulers.Schedulers;

public abstract class NetControl<Response extends ListShow<?>> extends BaseCtrl {

    private Observable<Response> mApi;

    public abstract Observable<Response> initApi();

    public abstract Observable<Response> initNextApi();

    public void getFirstData(NullCtrl<Response> nullCtrl) {
        mApi = initApi();
        if (mApi != null) {
            mApi.subscribeOn(Schedulers.newThread())
                    .observeOn(AndroidSchedulers.mainThread())
                    .map(new Function<Response, Response>() {
                        @Override
                        public Response apply(Response response) {
                            for (Object o : response.getList()) {
                                if (o instanceof IllustsBean) {
                                    TagFilter.judge(((IllustsBean) o));
                                }
                            }
                            return response;
                        }
                    })
                    .subscribe(nullCtrl);
        }
    }

    public void getNextData(NullCtrl<Response> nullCtrl) {
        mApi = initNextApi();
        if (mApi != null) {
            mApi.subscribeOn(Schedulers.newThread())
                    .observeOn(AndroidSchedulers.mainThread())
                    .map(new Function<Response, Response>() {
                        @Override
                        public Response apply(Response response) {
                            for (Object o : response.getList()) {
                                if (o instanceof IllustsBean) {
                                    TagFilter.judge(((IllustsBean) o));
                                }
                            }
                            return response;
                        }
                    })
                    .subscribe(nullCtrl);
        }
    }
}
