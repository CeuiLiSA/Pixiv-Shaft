package ceui.lisa.models;

import android.text.TextUtils;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class UserDetailResponse extends UserHolder implements Serializable, UserContainer {

    private ProfileBean profile;
    private ProfilePublicityBean profile_publicity;
    private WorkspaceBean workspace;
    // user/detail v2 新增:被服务端禁用的外链标识列表(如某些社交链接被关停),空数组表示无
    private List<String> disabled_links;

    public ProfileBean getProfile() {
        return profile;
    }

    public void setProfile(ProfileBean profile) {
        this.profile = profile;
    }

    public ProfilePublicityBean getProfile_publicity() {
        return profile_publicity;
    }

    public void setProfile_publicity(ProfilePublicityBean profile_publicity) {
        this.profile_publicity = profile_publicity;
    }

    public WorkspaceBean getWorkspace() {
        return workspace;
    }

    public void setWorkspace(WorkspaceBean workspace) {
        this.workspace = workspace;
    }

    public List<String> getDisabled_links() {
        return disabled_links;
    }

    public void setDisabled_links(List<String> disabled_links) {
        this.disabled_links = disabled_links;
    }

    @Override
    public int getUserId() {
        return getUser() == null ? 0 : (int) getUser().getId();
    }
}
