package ceui.lisa.models;

import java.util.List;

public class ProfilePresetsBean {

    private ImageUrlsBean default_profile_image_urls;
    private List<AddressesBean> addresses;
    private List<CountriesBean> countries;
    private List<JobsBean> jobs;

    public ImageUrlsBean getDefault_profile_image_urls() {
        return default_profile_image_urls;
    }

    public void setDefault_profile_image_urls(ImageUrlsBean default_profile_image_urls) {
        this.default_profile_image_urls = default_profile_image_urls;
    }

    public List<AddressesBean> getAddresses() {
        return addresses;
    }

    public void setAddresses(List<AddressesBean> addresses) {
        this.addresses = addresses;
    }

    public List<CountriesBean> getCountries() {
        return countries;
    }

    public void setCountries(List<CountriesBean> countries) {
        this.countries = countries;
    }

    public List<JobsBean> getJobs() {
        return jobs;
    }

    public void setJobs(List<JobsBean> jobs) {
        this.jobs = jobs;
    }


}
