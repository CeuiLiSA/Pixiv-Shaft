package ceui.lisa.fragments;

import android.content.Intent;
import android.view.View;

import com.blankj.utilcode.util.PathUtils;

import ceui.lisa.R;
import ceui.lisa.activities.Shaft;
import ceui.lisa.base.BaseFragment;
import ceui.lisa.databinding.FragmentStorageBinding;
import ceui.lisa.utils.Common;
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import rxhttp.RxHttp;

public class FragmentStorage extends BaseFragment<FragmentStorageBinding> {


    @Override
    protected void initLayout() {
        mLayoutID = R.layout.fragment_storage;
    }

    private static final int WRITE_REQUEST_CODE = 43;

    @Override
    protected void initView() {
        baseBind.store.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {

                String destPath = mActivity.getCacheDir() + "/" + System.currentTimeMillis() + ".apk";
                RxHttp.get("http://update.9158.com/miaolive/Miaolive.apk")
                        .asDownload(destPath, AndroidSchedulers.mainThread(), progress -> {
                            //下载进度回调,0-100，仅在进度有更新时才会回调，最多回调101次，最后一次回调文件存储路径
                            int currentProgress = progress.getProgress(); //当前进度 0-100
                            long currentSize = progress.getCurrentSize(); //当前已下载的字节大小
                            long totalSize = progress.getTotalSize();     //要下载的总字节大小
                            Common.showLog(currentSize + " / " + totalSize + " " + currentProgress + "%");
                            baseBind.progress.setProgress(currentProgress);
                        }) //指定主线程回调
                        .subscribe(s -> {//s为String类型，这里为文件存储路径
                            Common.showLog(s);
                            //下载完成，处理相关逻辑
                        }, throwable -> {
                            //下载失败，处理相关逻辑
                            Common.showLog(throwable.toString());

                        });

                Common.showLog("mActivity.getExternalCacheDir() " + mActivity.getExternalCacheDir());
            }
        });
    }
}
