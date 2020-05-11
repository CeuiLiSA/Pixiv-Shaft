package ceui.lisa.ui;

import ceui.lisa.http.NullCtrl;
import ceui.lisa.interfaces.ListShow;
import io.reactivex.Observable;
import io.reactivex.Observer;

public interface IPresent<T> {

    void first();

    void next();

    Observer<T> processFirst();

    Observer<T> processNext();

    void attach(IView<T> v);

    void dettach();

    String getNextUrl();

    String getToken();
}
