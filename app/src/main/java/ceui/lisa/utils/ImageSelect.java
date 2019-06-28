package ceui.lisa.utils;

import android.app.Activity;
import android.content.Context;
import android.content.pm.ActivityInfo;

import com.zhihu.matisse.Matisse;
import com.zhihu.matisse.MimeType;
import com.zhihu.matisse.engine.impl.GlideEngine;

import java.io.File;

public abstract class ImageSelect {

    private static final int REQUEST_CODE_CHOOSE = 10086;
    private Context mContext;


    public ImageSelect initContext(Context context){
        mContext = context;
        return this;
    }


    public interface OnImageSelect{

        void FuckImage(File file);

        void nothingSelect();
    }

}
