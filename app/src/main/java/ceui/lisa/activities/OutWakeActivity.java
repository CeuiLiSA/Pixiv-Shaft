package ceui.lisa.activities;

import android.content.Intent;
import android.net.Uri;
import android.text.TextUtils;

import ceui.lisa.R;
import ceui.lisa.interfaces.Callback;
import ceui.lisa.utils.PixivOperate;

public class OutWakeActivity extends BaseActivity{

    @Override
    protected void initLayout() {
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
                String illustID = uri.getQueryParameter("illust_id");
                if (!TextUtils.isEmpty(illustID)) {
                    PixivOperate.getIllustByID(Shaft.mUserModel, Integer.valueOf(illustID), mContext, new Callback<Void>() {
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
        }
    }
}
