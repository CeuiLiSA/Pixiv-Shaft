package ceui.lisa.download;

import android.text.TextUtils;

import com.google.gson.reflect.TypeToken;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import ceui.lisa.activities.Shaft;
import ceui.lisa.model.CustomFileNameCell;
import ceui.lisa.models.IllustsBean;
import ceui.lisa.utils.Common;
import ceui.lisa.utils.Settings;


public class FileCreator {

    public static File createGifZipFile(IllustsBean illustsBean) {
        if (illustsBean == null) {
            return null;
        }

        return new File(Shaft.sSettings.getGifZipPath(), deleteSpecialWords(
                illustsBean.getTitle() + "_" + illustsBean.getId() + ".zip")
        );
    }

    public static File createGifFile(IllustsBean illustsBean) {
        if (illustsBean == null) {
            return null;
        }

        return new File(Shaft.sSettings.getGifResultPath(), deleteSpecialWords(
                illustsBean.getTitle() + "_" + illustsBean.getId() + ".gif")
        );
    }


    public static File createGifParentFile(IllustsBean illustsBean) {
        if (illustsBean == null) {
            return null;
        }

        return new File(Shaft.sSettings.getGifUnzipPath() + deleteSpecialWords(
                illustsBean.getTitle() + "_" + illustsBean.getId())
        );
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


    private static final String DASH = "_";
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

        return new File(Shaft.sSettings.getIllustPath(), customFileName(illustsBean, index));
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

    public static final int ILLUST_TITLE = 1;
    public static final int ILLUST_ID = 2;
    public static final int P_SIZE = 3;
    public static final int USER_ID = 4;
    public static final int USER_NAME = 5;
    public static final int ILLUST_SIZE = 6;

    public static String customFileName(IllustsBean illustsBean, int index) {
        List<CustomFileNameCell> result = new ArrayList<>();
        if (TextUtils.isEmpty(Shaft.sSettings.getFileNameJson())) {
            result.add(new CustomFileNameCell("作品标题", "作品标题，可选项", 1, true));
            result.add(new CustomFileNameCell("作品ID", "不选的话可能两个文件名重复，导致下载失败，必选项", 2, true));
            result.add(new CustomFileNameCell("作品P数", "显示当前图片是作品的第几P，如果只有1P则隐藏，必选项", 3, true));
            result.add(new CustomFileNameCell("画师ID", "画师ID，可选项", 4, false));
            result.add(new CustomFileNameCell("画师昵称", "画师昵称，可选项", 5, false));
            result.add(new CustomFileNameCell("作品尺寸", "显示当前图片的尺寸信息，可选项", 6, false));
        } else {
            result.addAll(Shaft.sGson.fromJson(Shaft.sSettings.getFileNameJson(),
                    new TypeToken<List<CustomFileNameCell>>() {
                    }.getType()));
        }

        String fileName = "";

        for (int i = 0; i < result.size(); i++) {
            CustomFileNameCell cell = result.get(i);
            if (cell.isChecked()) {
                switch (cell.getCode()) {
                    case ILLUST_ID:
                        if (!TextUtils.isEmpty(fileName)) {
                            fileName = fileName + "_" + illustsBean.getId();
                        } else {
                            fileName = String.valueOf(illustsBean.getId());
                        }
                        break;
                    case ILLUST_TITLE:
                        if (!TextUtils.isEmpty(fileName)) {
                            fileName = fileName + "_" + illustsBean.getTitle();
                        } else {
                            fileName = illustsBean.getTitle();
                        }
                        break;
                    case P_SIZE:
                        if (illustsBean.getPage_count() != 1) {
                            if (!TextUtils.isEmpty(fileName)) {
                                fileName = fileName + "_p" + (index + 1);
                            } else {
                                fileName = "p" + (index + 1);
                            }
                        }
                        break;
                    case USER_ID:
                        if (!TextUtils.isEmpty(fileName)) {
                            fileName = fileName + "_" + illustsBean.getUser().getId();
                        } else {
                            fileName = String.valueOf(illustsBean.getUser().getId());
                        }
                        break;
                    case USER_NAME:
                        if (!TextUtils.isEmpty(fileName)) {
                            fileName = fileName + "_" + illustsBean.getUser().getName();
                        } else {
                            fileName = illustsBean.getUser().getName();
                        }
                        break;
                    case ILLUST_SIZE:
                        if (!TextUtils.isEmpty(fileName)) {
                            fileName = fileName + "_" + illustsBean.getWidth() + "px*" + illustsBean.getHeight() + "px";
                        } else {
                            fileName = illustsBean.getWidth() + "px*" + illustsBean.getHeight() + "px";
                        }
                        break;
                    default:
                        break;
                }
            }
        }

        return deleteSpecialWords(fileName + "." + Shaft.sSettings.getFileLastType());
    }

    public static String customFileNameForPreview(IllustsBean illustsBean,
                                                  List<CustomFileNameCell> cells, int index) {
        List<CustomFileNameCell> result;
        if (cells != null && cells.size() != 0) {
            result = new ArrayList<>(cells);
        } else {
            result = new ArrayList<>();
        }

        String fileName = "";

        for (int i = 0; i < result.size(); i++) {
            CustomFileNameCell cell = result.get(i);
            if (cell.isChecked()) {
                switch (cell.getCode()) {
                    case ILLUST_ID:
                        if (!TextUtils.isEmpty(fileName)) {
                            fileName = fileName + "_" + illustsBean.getId();
                        } else {
                            fileName = String.valueOf(illustsBean.getId());
                        }
                        break;
                    case ILLUST_TITLE:
                        if (!TextUtils.isEmpty(fileName)) {
                            fileName = fileName + "_" + illustsBean.getTitle();
                        } else {
                            fileName = illustsBean.getTitle();
                        }
                        break;
                    case P_SIZE:
                        if (illustsBean.getPage_count() != 1) {
                            if (!TextUtils.isEmpty(fileName)) {
                                fileName = fileName + "_p" + (index + 1);
                            } else {
                                fileName = "p" + (index + 1);
                            }
                        }
                        break;
                    case USER_ID:
                        if (!TextUtils.isEmpty(fileName)) {
                            fileName = fileName + "_" + illustsBean.getUser().getId();
                        } else {
                            fileName = String.valueOf(illustsBean.getUser().getId());
                        }
                        break;
                    case USER_NAME:
                        if (!TextUtils.isEmpty(fileName)) {
                            fileName = fileName + "_" + illustsBean.getUser().getName();
                        } else {
                            fileName = illustsBean.getUser().getName();
                        }
                        break;
                    case ILLUST_SIZE:
                        if (!TextUtils.isEmpty(fileName)) {
                            fileName = fileName + "_" + illustsBean.getWidth() + "px*" + illustsBean.getHeight() + "px";
                        } else {
                            fileName = illustsBean.getWidth() + "px*" + illustsBean.getHeight() + "px";
                        }
                        break;
                    default:
                        break;
                }
            }
        }

        return deleteSpecialWords(fileName + "." + Shaft.sSettings.getFileLastType());
    }
}
