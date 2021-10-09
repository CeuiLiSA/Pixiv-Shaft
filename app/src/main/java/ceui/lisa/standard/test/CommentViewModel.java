package ceui.lisa.standard.test;

import ceui.lisa.models.ReplyCommentBean;
import ceui.lisa.standard.ListViewModel;
import ceui.lisa.standard.Repository;

public class CommentViewModel extends ListViewModel<ReplyCommentBean> {

    @Override
    public Repository<ReplyCommentBean> initRepository() {
        return null;
    }
}
