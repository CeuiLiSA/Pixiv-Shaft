package ceui.lisa.models;

import android.text.TextUtils;

public class ProfileImageUrlsBean extends ImageUrlsBean {

    private String px_16x16;
    private String px_50x50;
    private String px_170x170;

    public String getPx_16x16() {
        return px_16x16;
    }

    public void setPx_16x16(String px_16x16) {
        this.px_16x16 = px_16x16;
    }

    public String getPx_50x50() {
        return px_50x50;
    }

    public void setPx_50x50(String px_50x50) {
        this.px_50x50 = px_50x50;
    }

    public String getPx_170x170() {
        return px_170x170;
    }

    public void setPx_170x170(String px_170x170) {
        this.px_170x170 = px_170x170;
    }

    @Override
    public String getMaxImage() {
        String url = super.getMaxImage();
        if (!TextUtils.isEmpty(url)) {
            return url;
        } else if(!TextUtils.isEmpty(px_170x170)){
            return px_170x170;
        } else if(!TextUtils.isEmpty(px_50x50)){
            return px_50x50;
        } else if(!TextUtils.isEmpty(px_16x16)){
            return px_16x16;
        } else {
            return "";
        }
    }
}
