package ceui.lisa.ui.model;

import ceui.lisa.http.Retro;
import ceui.lisa.model.ListUser;
import ceui.lisa.ui.IPresent;
import io.reactivex.Observable;

public class UserListModel extends ListModel<ListUser> {

    @Override
    public Observable<ListUser> firstApi(IPresent<ListUser> present) {
        return Retro.getAppApi().getRecmdUser(present.getToken());
    }

    @Override
    public Observable<ListUser> nextApi(IPresent<ListUser> present) {
        return Retro.getAppApi().getNextUser(present.getToken(), present.getNextUrl());
    }
}
