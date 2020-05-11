package ceui.lisa.ui.model;

import ceui.lisa.activities.Shaft;
import ceui.lisa.fragments.FragmentLikeIllust;
import ceui.lisa.http.Retro;
import ceui.lisa.model.ListIllust;
import ceui.lisa.ui.IPresent;
import io.reactivex.Observable;

public class IllustListModel extends ListModel<ListIllust> {

    @Override
    public Observable<ListIllust> firstApi(IPresent<ListIllust> present) {
        return Retro.getAppApi().getUserLikeIllust(present.getToken(),
                Shaft.sUserModel.getUserId(), FragmentLikeIllust.TYPE_PUBLUC);
    }

    @Override
    public Observable<ListIllust> nextApi(IPresent<ListIllust> present) {
        return Retro.getAppApi().getNextIllust(present.getToken(), present.getNextUrl());
    }
}
