package ceui.lisa.standard.test;

import ceui.lisa.interfaces.ListShow;
import ceui.lisa.models.CommentsBean;
import ceui.lisa.standard.Repository;
import io.reactivex.Observable;

public class CommentRepository extends Repository<CommentsBean> {

    @Override
    public Observable<ListShow<CommentsBean>> initApi() {
        return null;
    }
}
