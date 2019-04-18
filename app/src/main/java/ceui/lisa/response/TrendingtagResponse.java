package ceui.lisa.response;

import java.util.List;

import ceui.lisa.interfs.ListShow;

public class TrendingtagResponse implements ListShow<TrendingtagResponse.TrendTagsBean> {
    private List<TrendTagsBean> trend_tags;

    public List<TrendTagsBean> getTrend_tags() {
        return this.trend_tags;
    }

    public void setTrend_tags(List<TrendTagsBean> paramList) {
        this.trend_tags = paramList;
    }

    @Override
    public List<TrendTagsBean> getList() {
        return trend_tags;
    }

    @Override
    public String getNextUrl() {
        return null;
    }

    public static class TrendTagsBean {
        private IllustsBean illust;
        private String tag;

        public IllustsBean getIllust() {
            return this.illust;
        }

        public void setIllust(IllustsBean paramIllustsBean) {
            this.illust = paramIllustsBean;
        }

        public String getTag() {
            return this.tag;
        }

        public void setTag(String paramString) {
            this.tag = paramString;
        }
    }
}
