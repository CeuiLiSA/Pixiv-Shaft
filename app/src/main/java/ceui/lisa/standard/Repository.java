package ceui.lisa.standard;

import ceui.lisa.interfaces.ListShow;
import io.reactivex.Observable;

public abstract class Repository<Bean> {

    public abstract Observable<ListShow<Bean>> initApi();
}
