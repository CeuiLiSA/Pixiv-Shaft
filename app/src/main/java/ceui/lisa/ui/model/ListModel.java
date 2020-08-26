package ceui.lisa.ui.model;

import ceui.lisa.ui.IModel;
import ceui.lisa.ui.IPresent;
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
