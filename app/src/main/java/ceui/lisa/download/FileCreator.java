package ceui.lisa.download;

import android.text.TextUtils;

import com.google.gson.reflect.TypeToken;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import ceui.lisa.activities.Shaft;
import ceui.lisa.file.FileName;
import ceui.lisa.model.CustomFileNameCell;
import ceui.lisa.models.IllustsBean;
import ceui.lisa.utils.Common;

import static ceui.lisa.utils.Settings.FILE_PATH_SINGLE;
import static ceui.lisa.utils.Settings.FILE_PATH_SINGLE_R18;


public class FileCreator {

    private static final String DASH = "_";

    public static boolean isExist(IllustsBean illust, int index) {
        String fileName = illust.isGif() ? new FileName().gifName(illust) : customFileName(illust, index);
        File file = new File(FILE_PATH_SINGLE, fileName);
        File fileR18 = new File(FILE_PATH_SINGLE_R18, fileName);
        return file.exists() || fileR18.exists();
    }

    public static String deleteSpecialWords(String before) {
        if (!TextUtils.isEmpty(before)) {
            if(before.startsWith(".")){
                before = before.replaceFirst("\\.","\u2024");
            }
            String temp1 = before.replace("-", DASH);
            String temp2 = temp1.replace("/", DASH);
            String temp3 = temp2.replace(",", DASH);
            String temp4 = temp3.replace(":", DASH);
            String temp5 = temp4.replace("*", DASH);
            return temp5;
        } else {
            return "untitle_" + System.currentTimeMillis() + ".png";
        }
    }

    public static final int ILLUST_TITLE = 1;
    public static final int ILLUST_ID = 2;
    public static final int P_SIZE = 3;
    public static final int USER_ID = 4;
    public static final int USER_NAME = 5;
    public static final int ILLUST_SIZE = 6;

    public static String customFileName(IllustsBean illustsBean, int index) {
        List<CustomFileNameCell> result;
        if (TextUtils.isEmpty(Shaft.sSettings.getFileNameJson())) {
            result = defaultFileCells();
        } else {
            result = new ArrayList<>(Shaft.sGson.fromJson(Shaft.sSettings.getFileNameJson(),
                    new TypeToken<List<CustomFileNameCell>>() {}.getType()));
        }
        String fileUrl;
        if (illustsBean.getPage_count() == 1) {
            fileUrl = illustsBean.getMeta_single_page().getOriginal_image_url();
        } else {
            fileUrl = illustsBean.getMeta_pages().get(index).getImage_urls().getOriginal();
        }
        return deleteSpecialWords(illustToFileName(illustsBean, result, index) +
                "." + getMimeTypeFromUrl(fileUrl));
    }

    public static String getMimeTypeFromUrl(String url) {
        String result = "png";
        if (url.contains(".")) {
            result = url.substring(url.lastIndexOf(".") + 1);
        }
        Common.showLog("getMimeType fileUrl: " + url + ", fileType: " + result);
        return result;
    }

    public static String customFileNameForPreview(IllustsBean illustsBean,
                                                  List<CustomFileNameCell> cells, int index) {
        String fileUrl;
        if (illustsBean.getPage_count() == 1) {
            fileUrl = illustsBean.getMeta_single_page().getOriginal_image_url();
        } else {
            fileUrl = illustsBean.getMeta_pages().get(index).getImage_urls().getOriginal();
        }
        return deleteSpecialWords(illustToFileName(illustsBean, cells, index) +
                "." + getMimeTypeFromUrl(fileUrl));
    }

    private static String illustToFileName(IllustsBean illustsBean,
                                           List<CustomFileNameCell> result, int index) {
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
                        if (Shaft.sSettings.isHasP0()) {
                            if (!TextUtils.isEmpty(fileName)) {
                                fileName = fileName + "_p" + index;
                            } else {
                                fileName = "p" + index;
                            }
                        } else {
                            if (illustsBean.getPage_count() != 1) {
                                if (!TextUtils.isEmpty(fileName)) {
                                    fileName = fileName + "_p" + (index + 1);
                                } else {
                                    fileName = "p" + (index + 1);
                                }
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
        return fileName;
    }

    public static List<CustomFileNameCell> defaultFileCells() {
        List<CustomFileNameCell> cells = new ArrayList<>();
        cells.add(new CustomFileNameCell("作品标题", "作品标题，可选项", 1, true));
        cells.add(new CustomFileNameCell("作品ID", "不选的话可能两个文件名重复，导致下载失败，必选项", 2, true));
        cells.add(new CustomFileNameCell("作品P数", "显示当前图片是作品的第几P，必选项", 3, true));
        cells.add(new CustomFileNameCell("画师ID", "画师ID，可选项", 4, false));
        cells.add(new CustomFileNameCell("画师昵称", "画师昵称，可选项", 5, false));
        cells.add(new CustomFileNameCell("作品尺寸", "显示当前图片的尺寸信息，可选项", 6, false));
        return cells;
    }
}
