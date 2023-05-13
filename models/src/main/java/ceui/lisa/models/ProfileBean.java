package ceui.lisa.models;


import android.text.TextUtils;

import java.io.Serializable;
import java.util.Calendar;


public class ProfileBean implements Serializable {
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

    public String getContent() {
        String result = "";
        if ("male".equals(gender)) {
            result += "男性";
        } else if ("female".equals(gender)) {
            result += "女性";
        } else {
            result += "未知性别";
        }

        if (birth_year > 0) {
            Calendar calendar = Calendar.getInstance();
            int year = calendar.get(Calendar.YEAR);
            int age = year - birth_year;

            if (!TextUtils.isEmpty(result)) {
                result = result + "/" + age + "岁";
            } else {
                result += age + "岁";
            }
        }

        if (!TextUtils.isEmpty(birth_day)) {
            if (!TextUtils.isEmpty(result)) {
                result = result + "/" + birth_day + "生日";
            } else {
                result += birth_day + "生日";
            }
        }

        if (!TextUtils.isEmpty(job)) {
            if (!TextUtils.isEmpty(result)) {
                result = result + "/" + job;
            } else {
                result += job;
            }
        }


        return result;
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
