package ceui.lisa.models;

import java.io.Serializable;


public class MetaSinglePageBean extends ImageUrlsBean implements Serializable {
    /**
     * original_image_url : https://i.pximg.net/img-original/img/2019/03/30/16/33/50/73949833_p0.jpg
     */

    private String original_image_url;

    public String getOriginal_image_url() {
        return original_image_url;
    }

    public void setOriginal_image_url(String original_image_url) {
        this.original_image_url = original_image_url;
    }
}