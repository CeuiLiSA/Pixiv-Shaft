package ceui.lisa.ui.presenter;

import ceui.lisa.model.ListIllust;
import ceui.lisa.ui.IModel;
import ceui.lisa.ui.model.IllustListModel;

public class IllustListPresenter extends ListPresenter<ListIllust> {

    @Override
    public IModel<ListIllust> model() {
        return new IllustListModel();
    }
}
