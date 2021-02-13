package ceui.lisa.http;


public abstract class NullCtrl<T> extends ErrorCtrl<T> {

    public abstract void success(T t);

    @Override
    public void next(T t) {
        if (t != null) {
            success(t);
        }
    }
}
