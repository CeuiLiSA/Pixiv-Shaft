package ceui.lisa.standard.test;

import ceui.lisa.models.CommentsBean;
import ceui.lisa.standard.ListViewModel;
import ceui.lisa.standard.Repository;

public class CommentViewModel extends ListViewModel<CommentsBean> {

    @Override
    public Repository<CommentsBean> initRepository() {
        return null;
    }
}
