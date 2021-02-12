package ceui.lisa.models;

import java.io.Serializable;

public class ProfilePublicityBean implements Serializable {
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
