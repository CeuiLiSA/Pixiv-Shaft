package ceui.lisa.download;

import android.text.TextUtils;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import ceui.lisa.activities.Shaft;
import ceui.lisa.models.IllustsBean;
import ceui.lisa.utils.Common;
import ceui.lisa.utils.Settings;


public class FileCreator {

    public static File createGifZipFile(IllustsBean illustsBean) {
        if (illustsBean == null) {
            return null;
        }

        return new File(Shaft.sSettings.getGifZipPath(), deleteSpecialWords(
                illustsBean.getTitle() + "_" + illustsBean.getId() + ".zip"));
    }

    public static File createGifFile(IllustsBean illustsBean) {
        if (illustsBean == null) {
            return null;
        }

        return new File(Shaft.sSettings.getGifResultPath(), deleteSpecialWords(
                illustsBean.getTitle() + "_" + illustsBean.getId() + ".gif"));
    }


    public static File createGifParentFile(IllustsBean illustsBean) {
        if (illustsBean == null) {
            return null;
        }

        return new File(Shaft.sSettings.getGifUnzipPath() + deleteSpecialWords(
                illustsBean.getTitle() + "_" + illustsBean.getId()));
    }

    /**
     *
     * index 0 "title_123456789_p0.png"
     * index 1 "title_123456789_p0.jpg"
     * index 2 "123456789_title_p0.png"
     * index 3 "123456789_title_p0.jpg"
     *
     * @param illustsBean illustsBean
     * @return file
     */
    public static File createIllustFile(IllustsBean illustsBean) {
        return createIllustFile(illustsBean, 0);
    }

    /**
     *
     * index 0 "title_123456789_p0.png"
     * index 1 "title_123456789_p0.jpg"
     * index 2 "123456789_title_p0.png"
     * index 3 "123456789_title_p0.jpg"
     *
     */
    public static File createIllustFile(IllustsBean illustsBean, int index) {
        if (illustsBean == null) {
            return null;
        }

        Map<String, String> params = new HashMap<>();
        params.put("title", illustsBean.getTitle());
        params.put("id", String.valueOf(illustsBean.getId()));
        params.put("p", "p" + (index + 1));
        params.put("author", illustsBean.getUser().getName());
        params.put("width", String.valueOf(illustsBean.getWidth()));
        params.put("height", String.valueOf(illustsBean.getHeight()));
        params.put("ts", String.valueOf(System.currentTimeMillis()));

        return new File(Shaft.sSettings.getIllustPath(), deleteSpecialWords(fileNameFormat(Shaft.sSettings.getFileNameType(), params)));
    }

    private static String deleteSpecialWords(String before) {
        if (!TextUtils.isEmpty(before)) {
            String temp1 = before.replace("-", "_");
            String temp2 = temp1.replace("/", "_");
            String temp3 = temp2.replace(",", "_");
            return temp3;
        } else {
            return "untitle_" + System.currentTimeMillis() + ".png";
        }
    }


    public static File createWebFile(String name) {
        File parent = new File(Shaft.sSettings.getIllustPath());
        if (!parent.exists()) {
            parent.mkdir();
        }
        return new File(parent, deleteSpecialWords(name));
    }

    public static File createLogFile(String name) {
        File parent = new File(Settings.getLogPath());
        if (!parent.exists()) {
            parent.mkdir();
        }
        return new File(parent, deleteSpecialWords(name));
    }

    private static String fileNameFormat(String format, Map<String, String > params) {
        if (format == null) {
            return null;
        }

        StringBuilder stringBuilder = new StringBuilder(format);
        String out = String.copyValueOf(format.toCharArray());
        Set<String> keys = params.keySet();
        for (String key : keys) {
            out = out.replace("<" + key + ">", params.get(key));
        }
        return out;
    }
}
