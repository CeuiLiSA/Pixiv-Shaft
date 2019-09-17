package ceui.lisa.http;

public abstract class NullCtrl<T> extends ErrorCtrl<T> {

    @Override
    public void onNext(T t) {
        if(t != null){
            success(t);
        } else {
            nullSuccess();
        }
    }

    public abstract void success(T t);

    public void nullSuccess(){

    }
}
