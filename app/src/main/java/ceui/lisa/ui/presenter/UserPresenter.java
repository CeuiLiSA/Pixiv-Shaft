package ceui.lisa.ui.presenter;

import ceui.lisa.model.ListUser;
import ceui.lisa.ui.IModel;
import ceui.lisa.ui.model.UserListModel;

public class UserPresenter extends ListPresenter<ListUser> {

    @Override
    public IModel<ListUser> model() {
        return new UserListModel();
    }
}
