package ceui.lisa.download;

import com.lchad.gifflen.Gifflen;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import ceui.lisa.activities.Shaft;
import ceui.lisa.http.ErrorCtrl;
import ceui.lisa.models.IllustsBean;
import ceui.lisa.utils.Common;
import io.reactivex.Observable;
import io.reactivex.ObservableEmitter;
import io.reactivex.ObservableOnSubscribe;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.schedulers.Schedulers;

public class GifCreate {

    public static void createGif(IllustsBean illustsBean) {

        File parentFile = FileCreator.createGifParentFile(illustsBean);
        if (parentFile.exists()) {
            File realGifFile = new File(Shaft.sSettings.getGifResultPath(), illustsBean.getId() + ".gif");
            if (realGifFile.exists() && realGifFile.length() > 1024) {
                Common.showToast("gif已存在");
            } else {
                //Common.showToast("暂不支持保存");
                Common.showToast("开始生成gif图");
                final File[] listfile = parentFile.listFiles();
                try {
                    Observable.create(new ObservableOnSubscribe<String>() {
                        @Override
                        public void subscribe(ObservableEmitter<String> emitter) throws Exception {

                            Gifflen mGiffle = new Gifflen.Builder()
                                    .delay(85) //每相邻两帧之间播放的时间间隔.
                                    .listener(new Gifflen.OnEncodeFinishListener() {  //创建完毕的回调
                                        @Override
                                        public void onEncodeFinish(String path) {
                                            new ImageSaver() {
                                                @Override
                                                File whichFile() {
                                                    return realGifFile;
                                                }
                                            }.execute();
                                            Common.showToast("已保存gif到" + path);
                                            emitter.onNext(path);
                                        }
                                    })
                                    .build();

                            File resultParent = new File(Shaft.sSettings.getGifResultPath());
                            if (!resultParent.exists()) {
                                resultParent.mkdir();
                            }

                            realGifFile.createNewFile();

                            List<File> allFiles = Arrays.asList(listfile);
                            Collections.sort(allFiles, new Comparator<File>() {
                                @Override
                                public int compare(File o1, File o2) {
                                    if (Integer.valueOf(o1.getName().substring(0, o1.getName().length() - 4)) >
                                            Integer.valueOf(o2.getName().substring(0, o2.getName().length() - 4))) {
                                        return 1;
                                    } else {
                                        return -1;
                                    }
                                }
                            });


                            if (illustsBean.getWidth() > 450 && illustsBean.getHeight() > 450) {

                                if (illustsBean.getWidth() < illustsBean.getHeight()) {
                                    mGiffle.encode(450 * illustsBean.getWidth() / illustsBean.getHeight(),
                                            450, realGifFile.getPath(), allFiles);
                                } else {
                                    mGiffle.encode(450, 450 * illustsBean.getHeight() / illustsBean.getWidth(),
                                            realGifFile.getPath(), allFiles);
                                }
                            } else {
                                mGiffle.encode(illustsBean.getWidth(), illustsBean.getHeight(), realGifFile.getPath(), allFiles);
                            }
                        }
                    }).subscribeOn(Schedulers.newThread())
                            .observeOn(AndroidSchedulers.mainThread())
                            .subscribe(new ErrorCtrl<String>() {
                                @Override
                                public void onNext(String s) {

                                }
                            });
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        } else {
            Common.showToast("请先播放后保存");
        }
    }
}
