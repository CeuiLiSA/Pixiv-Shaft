package ceui.lisa.ui.model;

import java.util.List;

import ceui.lisa.activities.Shaft;
import ceui.lisa.http.NullCtrl;
import ceui.lisa.http.Retro;
import ceui.lisa.interfaces.ListShow;
import ceui.lisa.model.ListUser;
import ceui.lisa.models.UserPreviewsBean;
import ceui.lisa.ui.IModel;
import ceui.lisa.ui.IPresent;
import io.reactivex.Observable;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.schedulers.Schedulers;

public abstract class ListModel<T> implements IModel<T> {

    @Override
    public void fetchFirst(IPresent<T> present) {
        firstApi(present)
                .subscribeOn(Schedulers.newThread())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(present.processFirst());
    }

    @Override
    public void fetchNext(IPresent<T> present) {
        nextApi(present)
                .subscribeOn(Schedulers.newThread())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(present.processNext());
    }
}
