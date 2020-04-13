package ceui.lisa.fragments;

import ceui.lisa.activities.Shaft;
import ceui.lisa.adapters.BaseAdapter;
import ceui.lisa.adapters.IAdapter;
import ceui.lisa.databinding.RecyIllustStaggerBinding;
import ceui.lisa.http.Retro;
import ceui.lisa.model.ListIllust;
import ceui.lisa.models.IllustsBean;
import ceui.lisa.ui.IModel;
import ceui.lisa.ui.IPresent;
import ceui.lisa.ui.model.IllustListModel;
import ceui.lisa.ui.model.ListModel;
import ceui.lisa.ui.presenter.IllustListPresenter;
import ceui.lisa.ui.presenter.ListPresenter;
import io.reactivex.Observable;

public class FragmentTestIllust extends FragmentTest<ListIllust, IllustsBean,
        RecyIllustStaggerBinding> {

    @Override
    public IPresent<ListIllust> present() {
        return new IllustListPresenter();
    }

    @Override
    public BaseAdapter<IllustsBean, RecyIllustStaggerBinding> adapter() {
        return new IAdapter(allItems, mContext);
    }
}
