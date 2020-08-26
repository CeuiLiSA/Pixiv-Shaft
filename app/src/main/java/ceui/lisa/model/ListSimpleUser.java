package ceui.lisa.model;

import java.util.List;

import ceui.lisa.interfaces.ListShow;
import ceui.lisa.models.UserBean;

public class ListSimpleUser implements ListShow<UserBean> {

    private String next_url;
    private List<UserBean> users;

    public String getNext_url() {
        return next_url;
    }

    public void setNext_url(String pNext_url) {
        next_url = pNext_url;
    }

    public List<UserBean> getUsers() {
        return users;
    }

    public void setUsers(List<UserBean> pUsers) {
        users = pUsers;
    }

    @Override
    public List<UserBean> getList() {
        return users;
    }

    @Override
    public String getNextUrl() {
        return next_url;
    }
}
