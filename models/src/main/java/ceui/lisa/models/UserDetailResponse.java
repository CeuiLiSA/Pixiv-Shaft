package ceui.lisa.models;

import java.io.Serializable;
import java.util.List;

public class UserDetailResponse extends UserHolder implements Serializable, UserContainer {

    // 4.8.9 的序列化版本号；getUserId 改为 long 不应让系统恢复旧页面崩溃。
    private static final long serialVersionUID = -9038046041993882463L;

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
    public long getUserId() {
        return getUser() == null ? 0 : getUser().getId();
    }
}
