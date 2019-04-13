package ceui.lisa.utils;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;

import com.google.gson.Gson;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.ArrayList;
import java.util.List;

import ceui.lisa.activities.Shaft;
import ceui.lisa.interfs.ListShow;
import ceui.lisa.response.IllustsBean;
import ceui.lisa.response.ListIllustResponse;
import ceui.lisa.response.UserModel;
import ceui.lisa.utils.Empty;

public class Local {

    public static final String LOCAL_DATA = "local_data";
    public static final String USER = "user";

    public static void saveUser(UserModel userModel){
        if(userModel != null){
            Gson gson = new Gson();
            String userString = gson.toJson(userModel, UserModel.class);
            SharedPreferences localData = Shaft.getContext().getSharedPreferences(LOCAL_DATA, Context.MODE_PRIVATE);
            SharedPreferences.Editor editor = localData.edit();
            editor.putString(USER, userString);
            editor.apply();
        }
    }

    public static UserModel getUser(){
        SharedPreferences localData = Shaft.getContext().getSharedPreferences(LOCAL_DATA, Context.MODE_PRIVATE);
        String userString = localData.getString(USER,"");
        Gson gson = new Gson();
        UserModel userModel = gson.fromJson(userString, UserModel.class);
        return userModel;
    }

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

    public static <T> List<T> getLocalIllust() {
        List<T> bean = new ArrayList<>();
        try {
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
}
