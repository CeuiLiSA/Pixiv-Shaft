package ceui.lisa.models;

import android.text.TextUtils;
import android.util.Log;

import java.io.Serializable;

public class ImageUrlsBean implements Serializable {
    /**
     * square_medium : https://i.pximg.net/c/360x360_70/img-master/img/2019/04/03/21/13/11/74027091_p0_square1200.jpg
     * medium : https://i.pximg.net/c/540x540_70/img-master/img/2019/04/03/21/13/11/74027091_p0_master1200.jpg
     * large : https://i.pximg.net/c/600x1200_90/img-master/img/2019/04/03/21/13/11/74027091_p0_master1200.jpg
     * original : https://i.pximg.net/img-original/img/2019/04/03/21/13/11/74027091_p0.png
     */

    private String square_medium;
    private String medium;
    private String large;
    private String original;

    public String getSquare_medium() {
        return square_medium;
    }

    public void setSquare_medium(String square_medium) {
        this.square_medium = square_medium;
    }

    public String getMedium() {
        return medium;
    }

    public void setMedium(String medium) {
        this.medium = medium;
    }

    public String getLarge() {
        return large;
    }

    public void setLarge(String large) {
        this.large = large;
    }

    public String getOriginal() {
        return original;
    }

    public void setOriginal(String original) {
        this.original = original;
    }

    public String getMaxImage() {
        if (!TextUtils.isEmpty(original)) {
            return getOriginal();
        } else if (!TextUtils.isEmpty(large)) {
            return getLarge();
        } else if (!TextUtils.isEmpty(medium)) {
            return getMedium();
        } else if (!TextUtils.isEmpty(square_medium)) {
            return getSquare_medium();
        } else {
            return "";
        }
    }

    @Override
    public String toString() {
        return "ImageUrlsBean{" +
                "square_medium='" + square_medium + '\'' +
                ", medium='" + medium + '\'' +
                ", large='" + large + '\'' +
                ", original='" + original + '\'' +
                '}';
    }
}
