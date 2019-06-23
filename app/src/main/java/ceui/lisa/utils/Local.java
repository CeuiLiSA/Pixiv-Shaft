package ceui.lisa.utils;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;

import com.google.gson.Gson;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.ArrayList;
import java.util.List;

import ceui.lisa.activities.PikaActivity;
import ceui.lisa.activities.Shaft;
import ceui.lisa.interfaces.Callback;
import ceui.lisa.model.IllustsBean;
import ceui.lisa.model.UserModel;
import io.reactivex.Observable;
import io.reactivex.ObservableEmitter;
import io.reactivex.ObservableOnSubscribe;
import io.reactivex.Observer;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.Disposable;
import io.reactivex.schedulers.Schedulers;

public class Local {

    public static final String LOCAL_DATA = "local_data";
    public static final String USER = "user";

    public static void saveUser(UserModel userModel){
        if(userModel != null){
            Common.showLog("333333");
            userModel.getResponse().getUser().setIs_login(true);
            String token = userModel.getResponse().getAccess_token();
            userModel.getResponse().setAccess_token("Bearer " + token);
            Gson gson = new Gson();
            String userString = gson.toJson(userModel, UserModel.class);
            SharedPreferences localData = Shaft.getContext().getSharedPreferences(LOCAL_DATA, Context.MODE_PRIVATE);
            SharedPreferences.Editor editor = localData.edit();
            editor.putString(USER, userString);
            editor.apply();
            Shaft.sUserModel = userModel;
            Common.showLog("444444");
        }
    }

    public static UserModel getUser(){
        SharedPreferences localData = Shaft.getContext().getSharedPreferences(LOCAL_DATA, Context.MODE_PRIVATE);
        String userString = localData.getString(USER,"");
        Common.showLog("UserModel " + userString);
        Gson gson = new Gson();
        UserModel userModel = gson.fromJson(userString, UserModel.class);
        return userModel;
    }


    /**
     * 主线程 同步写入本地文件
     *
     * @param t
     * @param <T>
     */
    public static <T> void saveIllustList(List<T> t){
        try {
            FileOutputStream outputStream = Shaft.getContext().openFileOutput("RecommendIllust", Activity.MODE_PRIVATE);
            ObjectOutputStream oos = new ObjectOutputStream(outputStream);
            oos.writeObject(t);//写入
            outputStream.close();//关闭输入流
            oos.close();
            Common.showLog("本地文件写入成功");
        }
        catch (Exception e){
            e.printStackTrace();
        }
    }


    /**
     * IO线程 异步写入本地文件
     *
     * @param t
     * @param index
     * @param <T>
     */
    public static <T> void saveIllustList(List<T> t, int index) {
        Observable<String> observable = Observable.create(new ObservableOnSubscribe<String>() {
            @Override
            public void subscribe(ObservableEmitter<String> emitter) throws Exception {
                emitter.onNext("开始写入本地文件");
                FileOutputStream outputStream = Shaft.getContext().openFileOutput("RecommendIllust", Activity.MODE_PRIVATE);
                ObjectOutputStream oos = new ObjectOutputStream(outputStream);
                oos.writeObject(t);//写入
                outputStream.close();//关闭输入流
                oos.close();
                emitter.onNext("本地文件写入完成");
                emitter.onComplete();
            }
        });
        observable.subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(new Observer<String>() {
                    @Override
                    public void onSubscribe(Disposable d) {

                    }

                    @Override
                    public void onNext(String s) {
                        Common.showLog(s);
                    }

                    @Override
                    public void onError(Throwable e) {
                        e.printStackTrace();
                        Common.showToast(e.toString());
                    }

                    @Override
                    public void onComplete() {

                    }
                });
    }



    /**
     * 主线程 同步读取本地文件
     *
     * @param <T>
     * @return
     */
    public static <T> List<T> getLocalIllust() {
        List<T> bean = new ArrayList<>();
        try {
            Common.showLog("getLocalIllust thread is : " + Thread.currentThread().getName());
            FileInputStream fis = Shaft.getContext().openFileInput("RecommendIllust");//获得输入流
            ObjectInputStream ois = new ObjectInputStream(fis);
            bean = (List<T>) ois.readObject();
            fis.close();
            ois.close();
        }catch (Exception e){
            e.printStackTrace();
        }
        return bean;
    }


    /**
     * IO线程 异步读取本地文件
     *
     * @param callback
     * @param <T>
     */
    public static <T> void getLocalIllust(Callback<List<T>> callback) {
        List<T> localIllust = new ArrayList<>();
        Observable.create((ObservableOnSubscribe<String>) emitter -> {
            emitter.onNext("开始读取本地文件");
            Common.showLog("Observable thread is : " + Thread.currentThread().getName());
            FileInputStream fis = Shaft.getContext().openFileInput("RecommendIllust");//获得输入流
            ObjectInputStream ois = new ObjectInputStream(fis);
            localIllust.addAll((List<T>) ois.readObject());
            fis.close();
            ois.close();
            emitter.onNext("本地文件读取完成");
            emitter.onComplete();
        }).subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(new Observer<String>() {
                    @Override
                    public void onSubscribe(Disposable d) {

                    }

                    @Override
                    public void onNext(String s) {
                        Common.showLog(s);
                    }

                    @Override
                    public void onError(Throwable e) {
                        e.printStackTrace();
                        Common.showToast(e.toString());
                    }

                    @Override
                    public void onComplete() {
                        callback.doSomething(localIllust);
                    }
                });
    }

    public static String getPikaImageFileName(){
        SharedPreferences localData = Shaft.getContext().getSharedPreferences(LOCAL_DATA, Context.MODE_PRIVATE);
        return localData.getString("pika file name", "nopic.png");
    }

    public static long getPikaTime(){
        SharedPreferences localData = Shaft.getContext().getSharedPreferences(LOCAL_DATA, Context.MODE_PRIVATE);
        return localData.getLong("pika file time", 0L);
    }

    public static void setPikaImageFile(String pikaFileName, IllustsBean illustsBean){
        SharedPreferences localData = Shaft.getContext().getSharedPreferences(LOCAL_DATA, Context.MODE_PRIVATE);
        String before = localData.getString("pika file name", "nopic.png");
        File file = new File(PikaActivity.FILE_PATH, before);
        if(file.exists()){
            file.delete();
        }
        SharedPreferences.Editor editor = localData.edit();
        editor.putString("pika file name", pikaFileName);
        editor.putString("pika illust", new Gson().toJson(illustsBean));
        editor.putLong("pika file time", System.currentTimeMillis());
        editor.apply();
    }

    public static void setSettings(Settings settings){
        SharedPreferences localData = Shaft.getContext().getSharedPreferences(LOCAL_DATA, Context.MODE_PRIVATE);
        Gson gson = new Gson();
        String settingsGson = gson.toJson(settings);
        SharedPreferences.Editor editor = localData.edit();
        editor.putString("settings", settingsGson);
        editor.apply();
        Shaft.sSettings = settings;
    }

    public static Settings getSettings(){
        SharedPreferences localData = Shaft.getContext().getSharedPreferences(LOCAL_DATA, Context.MODE_PRIVATE);
        String settingsString = localData.getString("settings", "");
        Settings settings = new Gson().fromJson(settingsString, Settings.class);
        return settings == null ? new Settings() : settings;
    }
}
