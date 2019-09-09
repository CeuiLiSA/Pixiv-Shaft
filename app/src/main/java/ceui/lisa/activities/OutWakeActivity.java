package ceui.lisa.activities;

import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.text.TextUtils;
import android.view.View;

import java.util.Collections;

import ceui.lisa.R;
import ceui.lisa.interfaces.Callback;
import ceui.lisa.utils.Common;
import ceui.lisa.utils.PixivOperate;
import ceui.lisa.utils.optional.Function;
import ceui.lisa.utils.optional.Optional;

public class OutWakeActivity extends BaseActivity{

    @Override
    protected void initLayout() {
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
                        View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        mLayoutID = R.layout.activity_out_wake;
    }

    @Override
    protected void initView() {

    }


    @Override
    protected void initData() {
        Intent intent = getIntent();
        if(intent != null){
            Uri uri = intent.getData();
            if(uri != null){

                String scheme = uri.getScheme();
                if(!TextUtils.isEmpty(scheme)) {

                    //http网页跳转到这里
                    if(scheme.contains("http")) {
                        String illustID = uri.getQueryParameter("illust_id");
                        if (!TextUtils.isEmpty(illustID)) {
                            PixivOperate.getIllustByID(Shaft.sUserModel, Integer.valueOf(illustID), mContext, new Callback<Void>() {
                                @Override
                                public void doSomething(Void t) {
                                    finish();
                                }
                            });
                            return;
                        }

                        String userID = uri.getQueryParameter("id");
                        if (!TextUtils.isEmpty(userID)) {
                            Intent userIntent = new Intent(mContext, UserDetailActivity.class);
                            userIntent.putExtra("user id", Integer.valueOf(userID));
                            startActivity(userIntent);
                            finish();
                            return;
                        }
                    }

                    //pixiv内部链接，如 pixiv://illusts/73190863
                    if(scheme.contains("pixiv")){

                        String host = uri.getHost();
                        if(!TextUtils.isEmpty(host)) {
                            if(host.contains("users")) {
                                String path = uri.getPath();
                                Intent userIntent = new Intent(mContext, UserDetailActivity.class);
                                userIntent.putExtra("user id", Integer.valueOf(path.substring(1)));
                                startActivity(userIntent);
                                finish();
                                return;
                            }

                            if(host.contains("illusts")){
                                String path = uri.getPath();
                                PixivOperate.getIllustByID(Shaft.sUserModel, Integer.valueOf(path.substring(1)),
                                        mContext, new Callback<Void>() {
                                    @Override
                                    public void doSomething(Void t) {
                                        finish();
                                    }
                                });
                            }
                        }
                    }
                }
            }
        }
    }
}
