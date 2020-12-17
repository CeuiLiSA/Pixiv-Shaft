package ceui.lisa.models;

import java.io.Serializable;

public class MetaPagesBean implements Serializable {
    /**
     * image_urls : {"square_medium":"https://i.pximg.net/c/360x360_10_webp/img-master/img/2018/12/15/00/00/10/72114149_p0_square1200.jpg","medium":"https://i.pximg.net/c/540x540_70/img-master/img/2018/12/15/00/00/10/72114149_p0_master1200.jpg","large":"https://i.pximg.net/c/600x1200_90_webp/img-master/img/2018/12/15/00/00/10/72114149_p0_master1200.jpg","original":"https://i.pximg.net/img-original/img/2018/12/15/00/00/10/72114149_p0.png"}
     */

    private ImageUrlsBean image_urls;

    public ImageUrlsBean getImage_urls() {
        return image_urls;
    }

    public void setImage_urls(ImageUrlsBean image_urls) {
        this.image_urls = image_urls;
    }

}
