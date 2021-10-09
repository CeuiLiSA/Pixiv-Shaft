package ceui.lisa.standard.test;

import ceui.lisa.interfaces.ListShow;
import ceui.lisa.models.ReplyCommentBean;
import ceui.lisa.standard.Repository;
import io.reactivex.Observable;

public class CommentRepository extends Repository<ReplyCommentBean> {

    @Override
    public Observable<ListShow<ReplyCommentBean>> initApi() {
        return null;
    }
}
