package ceui.lisa.download;

import android.text.TextUtils;

import java.io.File;

import ceui.lisa.activities.Shaft;
import ceui.lisa.model.IllustsBean;


public class FileCreator {

    public static File createGifFile(IllustsBean illustsBean) {
        if (illustsBean == null) {
            return null;
        }

        return new File(Shaft.sSettings.getGifZipPath(),
                deleteSpecialWords(illustsBean.getTitle() + "_" + illustsBean.getId() + ".zip"));
    }


    public static File createGifParentFile(IllustsBean illustsBean) {
        if (illustsBean == null) {
            return null;
        }

        return new File(Shaft.sSettings.getGifUnzipPath() + deleteSpecialWords(illustsBean.getTitle() + "_" + illustsBean.getId()));
    }


    public static File createIllustFile(IllustsBean illustsBean) {
        if (illustsBean == null) {
            return null;
        }

        return new File(Shaft.sSettings.getIllustPath(),
                deleteSpecialWords(illustsBean.getTitle() + "_" + illustsBean.getId() + ".png"));
    }

    public static File createIllustFile(IllustsBean illustsBean, int index) {
        if (illustsBean == null) {
            return null;
        }

//        File parentFile = new File(FILE_PATH_META + illustsBean.getTitle() + "_" + illustsBean.getId());
//        if(!parentFile.exists()){
//            try {
//                if(parentFile.createNewFile()) {
//                    Common.showToast("父文件夹创建成功");
//
//
//
//
//                    File childFile = new File(parentFile.getPath(),
//                            deleteSpecialWords(illustsBean.getTitle() + "_" + illustsBean.getId() + ".png"))
//                }else {
//                    Common.showToast("父文件夹创建失败");
//                }
//            } catch (IOException e) {
//                e.printStackTrace();
//            }
//        }else {
//
//        }

        return new File(Shaft.sSettings.getIllustPath(),
                deleteSpecialWords(illustsBean.getTitle() + "_" + illustsBean.getId() + "_" + "p" + (index + 1) + ".png"));
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
}
