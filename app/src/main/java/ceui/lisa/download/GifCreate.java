package ceui.lisa.download;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.hardware.camera2.CameraManager;

import com.lchad.gifflen.Gifflen;
import com.squareup.gifencoder.GifEncoder;
import com.squareup.gifencoder.ImageOptions;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import ceui.lisa.http.ErrorCtrl;
import ceui.lisa.response.IllustsBean;
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
            File realGifFile = new File(FileCreator.FILE_GIF_RESULT_PATH, illustsBean.getId() + ".gif");
            if (realGifFile.exists()) {
                Common.showToast("gif已存在");
            } else {
                Common.showToast("开始生成gif图");
                final File[] listfile = parentFile.listFiles();
                Observable.create(new ObservableOnSubscribe<String>() {
                    @Override
                    public void subscribe(ObservableEmitter<String> emitter) throws Exception {
                        Gifflen mGiffle = new Gifflen.Builder()
                                .delay(75) //每相邻两帧之间播放的时间间隔.
                                .listener(new Gifflen.OnEncodeFinishListener() {  //创建完毕的回调
                                    @Override
                                    public void onEncodeFinish(String path) {
                                        Common.showToast("已保存gif到" + path);
                                        emitter.onNext(path);
                                    }
                                })
                                .build();
                        File resultParent = new File(FileCreator.FILE_GIF_RESULT_PATH);
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


                        if(illustsBean.getWidth() < illustsBean.getHeight()){
                            mGiffle.encode(450 * illustsBean.getWidth() / illustsBean.getHeight(),
                                    450, realGifFile.getPath(), allFiles);
                        }else {
                            mGiffle.encode(450, 450 * illustsBean.getHeight() / illustsBean.getWidth(),
                                    realGifFile.getPath(), allFiles);
                        }

                    }
                }).subscribeOn(Schedulers.newThread())
                        .observeOn(AndroidSchedulers.mainThread())
                        .subscribe(new ErrorCtrl<String>() {
                            @Override
                            public void onNext(String s) {

                            }
                        });
            }
        } else {
            Common.showToast("请先播放后保存");
        }
    }




//    public static void createGifWithOutCpp(IllustsBean illustsBean) {
//
//        File parentFile = FileCreator.createGifParentFile(illustsBean);
//        if (parentFile.exists()) {
//            File realGifFile = new File(FileCreator.FILE_GIF_RESULT_PATH, illustsBean.getId() + ".gif");
//            if (realGifFile.exists()) {
//                Common.showToast("gif已存在");
//            } else {
//
//                final File[] listfile = parentFile.listFiles();
//                Observable.create(new ObservableOnSubscribe<String>() {
//                    @Override
//                    public void subscribe(ObservableEmitter<String> emitter) throws Exception {
//
//                        List<File> allFiles = Arrays.asList(listfile);
//                        Collections.sort(allFiles, new Comparator<File>() {
//                            @Override
//                            public int compare(File o1, File o2) {
//                                if (Integer.valueOf(o1.getName().substring(0, o1.getName().length() - 4)) >
//                                        Integer.valueOf(o2.getName().substring(0, o2.getName().length() - 4))) {
//                                    return 1;
//                                } else {
//                                    return -1;
//                                }
//                            }
//                        });
//                        realGifFile.createNewFile();
//
//                        OutputStream outputStream = new FileOutputStream(realGifFile);
//                        ImageOptions options = new ImageOptions();
//                        GifEncoder encoder = new GifEncoder(outputStream, illustsBean.getWidth(),
//                                illustsBean.getHeight(), 0);
//
//                        for (int i = 0; i < allFiles.size(); i++) {
//                            File file = allFiles.get(i);
//
//
//                            Bitmap bitmap = BitmapFactory.decodeResource();
//                            int x = bitmap.getWidth();
//                            int y = bitmap.getHeight();
//                            int[] intArray = new int[x * y];
//                            bitmap.getPixels(intArray, 0, x, 0, 0, x, y);
//
//
//                            encoder.addImage(file., options);
//                        }
//                        encoder.finishEncoding();
//
//                        outputStream.close();
//                    }
//                }).subscribeOn(Schedulers.newThread())
//                        .observeOn(AndroidSchedulers.mainThread())
//                        .subscribe(new ErrorCtrl<String>() {
//                            @Override
//                            public void onNext(String s) {
//
//                            }
//                        });
//            }
//        } else {
//            Common.showToast("请先播放后保存");
//        }
//    }


    public static int[][] txtString(FileReader file){
        BufferedReader br = new BufferedReader(file);//读取文件
        try {
            String line = br.readLine();//读取一行数据
            int lines = Integer.parseInt(line);//将数据转化为int类型
            System.out.println(lines);

            String []sp = null;
            String [][]c = new String[lines][lines];
            int [][]cc = new int[lines][lines];
            int count=0;
            while((line=br.readLine())!=null) {//按行读取
                sp = line.split(" ");//按空格进行分割
                for(int i=0;i<sp.length;i++){
                    c[count][i] = sp[i];
                }
                count++;
            }
            for(int i=0;i<lines;i++){
                for(int j=0;j<lines;j++){
                    cc[i][j] = Integer.parseInt(c[i][j]);
                    System.out.print(cc[i][j]);
                }
                System.out.println();
            }
            return cc;
        } catch (IOException e) {
            e.printStackTrace();
        }
        return new int[0][];
    }

}
