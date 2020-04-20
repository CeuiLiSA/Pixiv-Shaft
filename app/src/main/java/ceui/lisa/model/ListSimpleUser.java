package ceui.lisa.model;

import java.util.List;

import ceui.lisa.interfaces.ListShow;
import ceui.lisa.models.SpotlightArticlesBean;
import ceui.lisa.models.UserBean;

public class ListSimpleUser implements ListShow<UserBean> {

    private String next_url;
    private List<UserBean> users;

    @Override
    public List<UserBean> getList() {
        return users;
    }

    @Override
    public String getNextUrl() {
        return next_url;
    }
}
