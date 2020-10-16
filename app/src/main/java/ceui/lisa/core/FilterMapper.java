package ceui.lisa.core;

import java.util.List;

import ceui.lisa.activities.Shaft;
import ceui.lisa.model.ListIllust;
import ceui.lisa.models.IllustsBean;
import ceui.lisa.utils.PixivOperate;

/**
 * 从列表中筛选掉（去掉)已收藏的作品
 */
public class FilterMapper extends Mapper<ListIllust> {

    @Override
    public ListIllust apply(ListIllust listIllust) {
        super.apply(listIllust);
        listIllust.setIllusts(PixivOperate.getListWithoutBooked(listIllust));
        return listIllust;
    }
}
