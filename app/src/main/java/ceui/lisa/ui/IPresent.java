package ceui.lisa.ui;

import io.reactivex.Observer;

public interface IPresent<T> {

    void first();

    void next();

    Observer<T> processFirst();

    Observer<T> processNext();

    void attach(IView<T> v);

    void detach();

    String getNextUrl();

    String getToken();
}
