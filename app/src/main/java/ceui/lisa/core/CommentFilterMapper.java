package ceui.lisa.core;

import ceui.lisa.activities.Shaft;
import ceui.lisa.helper.CommentFilter;
import ceui.lisa.model.ListComment;

public class CommentFilterMapper extends Mapper<ListComment> {

    @Override
    public ListComment apply(ListComment listComment) {

        if (Shaft.sSettings.isFilterComment()) {
            listComment.getComments().removeIf(CommentFilter::judge);
        }

        return listComment;
    }
}
