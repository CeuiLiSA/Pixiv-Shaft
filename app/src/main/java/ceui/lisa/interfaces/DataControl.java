package ceui.lisa.interfaces;

public abstract class DataControl<T> {

    public abstract T first();

    public abstract T next();

    public boolean hasNext(){
        return false;
    }

    public boolean enableRefresh(){
        return true;
    }
}
