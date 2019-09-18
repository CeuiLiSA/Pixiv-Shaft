package ceui.lisa.http;

public abstract class NullCtrl<T> extends ErrorCtrl<T> {

    @Override
    public void onNext(T t) {
        if(t != null){
            success(t);
        } else {
            nullSuccess();
        }
        must(true);
    }

    public abstract void success(T t);

    public void nullSuccess(){

    }

    @Override
    public void onError(Throwable e) {
        super.onError(e);
        must(false);
    }

    public void must(boolean isSuccess){

    }
}
