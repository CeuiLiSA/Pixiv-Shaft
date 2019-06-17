package ceui.lisa.download;

import android.text.TextUtils;

import java.io.File;
import java.io.IOException;

import ceui.lisa.response.IllustsBean;
import ceui.lisa.utils.Common;

public class FileCreator {

    //只包含1P图片的下载路径
    public static final String FILE_PATH_SINGLE = "/storage/emulated/0/Shaft/SingleImages";


    //包含多P文件的下载路径
    public static final String FILE_PATH_META = "/storage/emulated/0/Shaft/MetaImages/";


    public static final String FILE_GIF_PATH = "/storage/emulated/0/Shaft/gif/";

    public static final String FILE_GIF_CHILD_PATH = "/storage/emulated/0/Shaft/gifUnzip/";

    public static final String FILE_GIF_RESULT_PATH = "/storage/emulated/0/Shaft/gifGenerate/";

    public static final String WEB_DOWNLOAD_PATH = "/storage/emulated/0/Shaft/Web";


    public static File createGifFile(IllustsBean illustsBean){
        if(illustsBean == null){
            return null;
        }

        return new File(FILE_GIF_PATH,
                deleteSpecialWords(illustsBean.getTitle() + "_" + illustsBean.getId() + ".zip"));
    }


    public static File createGifParentFile(IllustsBean illustsBean){
        if(illustsBean == null){
            return null;
        }

        return new File(FILE_GIF_CHILD_PATH + deleteSpecialWords(illustsBean.getTitle() + "_" + illustsBean.getId()));
    }


    public static File createIllustFile(IllustsBean illustsBean){
        if(illustsBean == null){
            return null;
        }

        return new File(FILE_PATH_SINGLE,
                    deleteSpecialWords(illustsBean.getTitle() + "_" + illustsBean.getId() + ".png"));
    }

    public static File createIllustFile(IllustsBean illustsBean, int index){
        if(illustsBean == null){
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

        return new File(FILE_PATH_SINGLE,
                deleteSpecialWords(illustsBean.getTitle() + "_" + illustsBean.getId() + "_" + "p" + (index + 1) + ".png"));
    }

    private static String deleteSpecialWords(String before){
        if(!TextUtils.isEmpty(before)){
            String temp1 = before.replace("-", "_");
            String temp2 = temp1.replace("/", "_");
            String temp3 = temp2.replace(",", "_");
            return temp3;
        }else {
            return "untitle_" + System.currentTimeMillis() + ".png";
        }
    }


    public static File createWebFile(String name){
        File parent = new File(WEB_DOWNLOAD_PATH);
        if(!parent.exists()){
            parent.mkdir();
        }
        return new File(parent, deleteSpecialWords(name));
    }
}
