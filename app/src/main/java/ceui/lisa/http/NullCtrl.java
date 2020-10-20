package ceui.lisa.http;


public abstract class NullCtrl<T> extends ErrorCtrl<T> {

    public abstract void success(T t);

    public void nullSuccess() {

    }

    @Override
    public void next(T t) {
        must(true);
        if (t != null) {
            success(t);
        } else {
            nullSuccess();
        }
    }

    @Override
    public void error(Throwable e) {
        super.error(e);
        must(false);
    }

    public void must(boolean isSuccess) {

    }
}
