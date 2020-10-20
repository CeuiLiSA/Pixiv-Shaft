package ceui.lisa.models;

import android.text.TextUtils;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class UserDetailResponse implements Serializable, UserContainer {

    private UserBean user;
    private ProfileBean profile;
    private ProfilePublicityBean profile_publicity;
    private WorkspaceBean workspace;

    public UserBean getUser() {
        return user;
    }

    public void setUser(UserBean user) {
        this.user = user;
    }

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

    public List<String> getTag() {
        List<String> result = new ArrayList<>();
        if (workspace == null) {
            return result;
        }

        if (!TextUtils.isEmpty(workspace.chair)) {
            result.add(workspace.chair);
        }

        if (!TextUtils.isEmpty(workspace.comment)) {
            result.add(workspace.comment);
        }

        if (!TextUtils.isEmpty(workspace.desk)) {
            result.add(workspace.desk);
        }

        if (!TextUtils.isEmpty(workspace.desktop)) {
            result.add(workspace.desktop);
        }

        if (!TextUtils.isEmpty(workspace.monitor)) {
            result.add(workspace.monitor);
        }

        if (!TextUtils.isEmpty(workspace.mouse)) {
            result.add(workspace.mouse);
        }

        if (!TextUtils.isEmpty(workspace.music)) {
            result.add(workspace.music);
        }

        if (!TextUtils.isEmpty(workspace.pc)) {
            result.add(workspace.pc);
        }

        if (!TextUtils.isEmpty(workspace.printer)) {
            result.add(workspace.printer);
        }

        if (!TextUtils.isEmpty(workspace.scanner)) {
            result.add(workspace.scanner);
        }

        if (!TextUtils.isEmpty(workspace.tablet)) {
            result.add(workspace.tablet);
        }

        if (!TextUtils.isEmpty(workspace.tool)) {
            result.add(workspace.tool);
        }

        if (result.size() != 0) {
            Collections.sort(result, new Comparator<String>() {
                @Override
                public int compare(String s, String t1) {
                    if (s.length() < t1.length()) {
                        return -1;
                    } else if (s.length() > t1.length()) {
                        return 1;
                    } else {
                        return 0;
                    }
                }
            });
        }

        return result;
    }

    @Override
    public int getUserId() {
        return user == null ? 0 : user.getId();
    }

    public static class ProfilePublicityBean implements Serializable {
        /**
         * gender : public
         * region : public
         * birth_day : public
         * birth_year : public
         * job : public
         * pawoo : true
         */

        private String gender;
        private String region;
        private String birth_day;
        private String birth_year;
        private String job;
        private boolean pawoo;

        public String getGender() {
            return gender;
        }

        public void setGender(String gender) {
            this.gender = gender;
        }

        public String getRegion() {
            return region;
        }

        public void setRegion(String region) {
            this.region = region;
        }

        public String getBirth_day() {
            return birth_day;
        }

        public void setBirth_day(String birth_day) {
            this.birth_day = birth_day;
        }

        public String getBirth_year() {
            return birth_year;
        }

        public void setBirth_year(String birth_year) {
            this.birth_year = birth_year;
        }

        public String getJob() {
            return job;
        }

        public void setJob(String job) {
            this.job = job;
        }

        public boolean isPawoo() {
            return pawoo;
        }

        public void setPawoo(boolean pawoo) {
            this.pawoo = pawoo;
        }
    }

    public static class WorkspaceBean implements Serializable {
        /**
         * pc :
         * monitor :
         * tool :
         * scanner :
         * tablet :
         * mouse :
         * printer :
         * desktop :
         * music :
         * desk :
         * chair :
         * comment :
         * workspace_image_url : null
         */

        private String pc;
        private String monitor;
        private String tool;
        private String scanner;
        private String tablet;
        private String mouse;
        private String printer;
        private String desktop;
        private String music;
        private String desk;
        private String chair;
        private String comment;
        private String workspace_image_url;

        public String getPc() {
            return pc;
        }

        public void setPc(String pc) {
            this.pc = pc;
        }

        public String getMonitor() {
            return monitor;
        }

        public void setMonitor(String monitor) {
            this.monitor = monitor;
        }

        public String getTool() {
            return tool;
        }

        public void setTool(String tool) {
            this.tool = tool;
        }

        public String getScanner() {
            return scanner;
        }

        public void setScanner(String scanner) {
            this.scanner = scanner;
        }

        public String getTablet() {
            return tablet;
        }

        public void setTablet(String tablet) {
            this.tablet = tablet;
        }

        public String getMouse() {
            return mouse;
        }

        public void setMouse(String mouse) {
            this.mouse = mouse;
        }

        public String getPrinter() {
            return printer;
        }

        public void setPrinter(String printer) {
            this.printer = printer;
        }

        public String getDesktop() {
            return desktop;
        }

        public void setDesktop(String desktop) {
            this.desktop = desktop;
        }

        public String getMusic() {
            return music;
        }

        public void setMusic(String music) {
            this.music = music;
        }

        public String getDesk() {
            return desk;
        }

        public void setDesk(String desk) {
            this.desk = desk;
        }

        public String getChair() {
            return chair;
        }

        public void setChair(String chair) {
            this.chair = chair;
        }

        public String getComment() {
            return comment;
        }

        public void setComment(String comment) {
            this.comment = comment;
        }

        public String getWorkspace_image_url() {
            return workspace_image_url;
        }

        public void setWorkspace_image_url(String workspace_image_url) {
            this.workspace_image_url = workspace_image_url;
        }
    }
}
