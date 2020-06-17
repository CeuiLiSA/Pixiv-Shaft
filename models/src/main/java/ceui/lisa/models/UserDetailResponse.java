package ceui.lisa.models;

import android.text.TextUtils;

import java.io.Serializable;
import java.util.ArrayList;
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

    public static class ProfileBean implements Serializable {
        /**
         * webpage : http://blog.naver.com/wan_ke
         * gender :
         * birth : 1997-10-23
         * birth_day : 10-23
         * birth_year : 1997
         * region : Korea, Republic of
         * address_id : 48
         * country_code : KR
         * job : 艺术家
         * job_id : 14
         * total_follow_users : 296
         * total_mypixiv_users : 6
         * total_illusts : 40
         * total_manga : 0
         * total_novels : 0
         * total_illust_bookmarks_public : 37
         * total_illust_series : 0
         * total_novel_series : 0
         * background_image_url : null
         * twitter_account : Classic_W_
         * twitter_url : https://twitter.com/Classic_W_
         * pawoo_url : null
         * is_premium : true
         * is_using_custom_profile_image : true
         */

        private String webpage;
        private String gender;
        private String birth;
        private String birth_day;
        private int birth_year;
        private String region;
        private int address_id;
        private String country_code;
        private String job;
        private int job_id;
        private int total_follow_users;
        private int total_mypixiv_users;
        private int total_illusts;
        private int total_manga;
        private int total_novels;
        private int total_illust_bookmarks_public;
        private int total_illust_series;
        private int total_novel_series;
        private String background_image_url;
        private String twitter_account;
        private String twitter_url;
        private String pawoo_url;
        private boolean is_premium;
        private boolean is_using_custom_profile_image;

        public String getWebpage() {
            return webpage;
        }

        public void setWebpage(String webpage) {
            this.webpage = webpage;
        }

        public String getGender() {
            return gender;
        }

        public void setGender(String gender) {
            this.gender = gender;
        }

        public String getBirth() {
            return birth;
        }

        public void setBirth(String birth) {
            this.birth = birth;
        }

        public String getBirth_day() {
            return birth_day;
        }

        public void setBirth_day(String birth_day) {
            this.birth_day = birth_day;
        }

        public int getBirth_year() {
            return birth_year;
        }

        public void setBirth_year(int birth_year) {
            this.birth_year = birth_year;
        }

        public String getRegion() {
            return region;
        }

        public void setRegion(String region) {
            this.region = region;
        }

        public int getAddress_id() {
            return address_id;
        }

        public void setAddress_id(int address_id) {
            this.address_id = address_id;
        }

        public String getCountry_code() {
            return country_code;
        }

        public void setCountry_code(String country_code) {
            this.country_code = country_code;
        }

        public String getJob() {
            return job;
        }

        public void setJob(String job) {
            this.job = job;
        }

        public int getJob_id() {
            return job_id;
        }

        public void setJob_id(int job_id) {
            this.job_id = job_id;
        }

        public int getTotal_follow_users() {
            return total_follow_users;
        }

        public void setTotal_follow_users(int total_follow_users) {
            this.total_follow_users = total_follow_users;
        }

        public int getTotal_mypixiv_users() {
            return total_mypixiv_users;
        }

        public void setTotal_mypixiv_users(int total_mypixiv_users) {
            this.total_mypixiv_users = total_mypixiv_users;
        }

        public int getTotal_illusts() {
            return total_illusts;
        }

        public void setTotal_illusts(int total_illusts) {
            this.total_illusts = total_illusts;
        }

        public int getTotal_manga() {
            return total_manga;
        }

        public void setTotal_manga(int total_manga) {
            this.total_manga = total_manga;
        }

        public int getTotal_novels() {
            return total_novels;
        }

        public void setTotal_novels(int total_novels) {
            this.total_novels = total_novels;
        }

        public int getTotal_illust_bookmarks_public() {
            return total_illust_bookmarks_public;
        }

        public void setTotal_illust_bookmarks_public(int total_illust_bookmarks_public) {
            this.total_illust_bookmarks_public = total_illust_bookmarks_public;
        }

        public int getTotal_illust_series() {
            return total_illust_series;
        }

        public void setTotal_illust_series(int total_illust_series) {
            this.total_illust_series = total_illust_series;
        }

        public int getTotal_novel_series() {
            return total_novel_series;
        }

        public void setTotal_novel_series(int total_novel_series) {
            this.total_novel_series = total_novel_series;
        }

        public String getBackground_image_url() {
            return background_image_url;
        }

        public void setBackground_image_url(String background_image_url) {
            this.background_image_url = background_image_url;
        }

        public String getTwitter_account() {
            return twitter_account;
        }

        public void setTwitter_account(String twitter_account) {
            this.twitter_account = twitter_account;
        }

        public String getTwitter_url() {
            return twitter_url;
        }

        public void setTwitter_url(String twitter_url) {
            this.twitter_url = twitter_url;
        }

        public String getPawoo_url() {
            return pawoo_url;
        }

        public void setPawoo_url(String pawoo_url) {
            this.pawoo_url = pawoo_url;
        }

        public boolean isIs_premium() {
            return is_premium;
        }

        public void setIs_premium(boolean is_premium) {
            this.is_premium = is_premium;
        }

        public boolean isIs_using_custom_profile_image() {
            return is_using_custom_profile_image;
        }

        public void setIs_using_custom_profile_image(boolean is_using_custom_profile_image) {
            this.is_using_custom_profile_image = is_using_custom_profile_image;
        }
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
