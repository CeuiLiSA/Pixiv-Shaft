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

    @Override
    public int getUserId() {
        return getUser() == null ? 0 : getUser().getId();
    }
}
