package ceui.lisa.core;

import java.util.List;

import ceui.lisa.activities.Shaft;
import ceui.lisa.interfaces.ListShow;
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
        if (Shaft.sSettings.isDeleteStarIllust()) {
            //筛选作品，只留下未收藏的作品
            List<IllustsBean> tempList = PixivOperate.getListWithoutBooked(listIllust);
            listIllust.setIllusts(tempList);
        }
        return listIllust;
    }
}
