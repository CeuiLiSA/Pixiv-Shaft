package ceui.lisa.test;

import android.util.Log;

import com.alibaba.sdk.android.oss.ClientException;
import com.alibaba.sdk.android.oss.ServiceException;
import com.alibaba.sdk.android.oss.callback.OSSCompletedCallback;
import com.alibaba.sdk.android.oss.model.PutObjectRequest;
import com.alibaba.sdk.android.oss.model.PutObjectResult;

import java.io.File;

import ceui.lisa.activities.Shaft;
import ceui.lisa.database.AppDatabase;
import ceui.lisa.database.ImageEntity;
import ceui.lisa.utils.Common;

public class OssSender implements SendToRemote{


    @Override
    public void send(File file) {
        PutObjectRequest put = new PutObjectRequest("ceuilisa", "imageFile/" + file.getName(), file.getPath());
        OssManager.get().asyncPutObject(put, new OSSCompletedCallback<PutObjectRequest,
                PutObjectResult>() {
            @Override
            public void onSuccess(PutObjectRequest request, PutObjectResult result) {
                Log.d("PutObject", "UploadSuccess");
                ImageEntity imageEntity = new ImageEntity();
                imageEntity.setFileName(file.getName());
                imageEntity.setFilePath(file.getPath());
                imageEntity.setUploadTime(System.currentTimeMillis());
                imageEntity.setId(file.getName().hashCode());
                AppDatabase.getAppDatabase(Shaft.getContext()).downloadDao().insertUploadedImage(imageEntity);
                Common.showLog(file.getName() + " 已上传结束");
            }

            @Override
            public void onFailure(PutObjectRequest request, ClientException clientExcepion, ServiceException serviceException) {
                if (clientExcepion != null) {
                    clientExcepion.printStackTrace();
                }
            }
        });
    }
}
