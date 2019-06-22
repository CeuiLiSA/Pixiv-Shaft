package ceui.lisa.activities;

import android.content.Intent;
import android.graphics.Color;
import android.support.constraint.ConstraintLayout;
import android.support.v7.widget.CardView;
import android.text.TextUtils;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.facebook.rebound.SimpleSpringListener;
import com.facebook.rebound.Spring;
import com.facebook.rebound.SpringConfig;
import com.facebook.rebound.SpringSystem;
import com.google.gson.Gson;
import com.rengwuxian.materialedittext.MaterialEditText;

import ceui.lisa.R;
import ceui.lisa.database.AppDatabase;
import ceui.lisa.database.UserEntity;
import ceui.lisa.http.ErrorCtrl;
import ceui.lisa.http.Retro;
import ceui.lisa.model.SignResponse;
import ceui.lisa.model.UserBean;
import ceui.lisa.model.UserModel;
import ceui.lisa.utils.Common;
import ceui.lisa.utils.Local;
import io.reactivex.Observer;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.Disposable;
import io.reactivex.schedulers.Schedulers;

import static ceui.lisa.fragments.FragmentLogin.CLIENT_ID;
import static ceui.lisa.fragments.FragmentLogin.CLIENT_SECRET;
import static ceui.lisa.fragments.FragmentLogin.DEVICE_TOKEN;

public class LoginAlphaActivity extends BaseActivity {

    private ConstraintLayout cardLogin, cardSign;
    private SpringSystem springSystem = SpringSystem.create();
    private Spring rotate;
    private CardView login, sign;
    private MaterialEditText userName, password, signName;
    private ProgressBar mProgressBar;
    //private static final String SIGN_TOKEN = "Bearer l-f9qZ0ZyqSwRyZs8-MymbtWBbSxmCu1pmbOlyisou8";
    private static final String SIGN_TOKEN = "pixiv";
    private static final String SIGN_REF = "pixiv_android_app_provisional_account";


    @Override
    protected void initLayout() {
        mLayoutID = R.layout.activity_login_alpha;
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    @Override
    protected void initView() {
        mProgressBar = findViewById(R.id.progress);
        signName = findViewById(R.id.sign_user_name);
        password = findViewById(R.id.password);
        userName = findViewById(R.id.user_name);
        cardLogin = findViewById(R.id.fragment_login);
        cardSign = findViewById(R.id.fragment_sign);
        login = findViewById(R.id.login);
        login.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if(userName.getText().toString().length() != 0) {
                    if(password.getText().toString().length() != 0) {
                        login(userName.getText().toString(), password.getText().toString());
                    }else {
                        Common.showToast("请输入密码");
                    }
                }else {
                    Common.showToast("请输入用户名");
                }
            }
        });
        sign = findViewById(R.id.sign);
        sign.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {

                Common.showToast("暂不支持注册。。");

//                if(signName.getText().toString().length() != 0) {
//                    sign();
//                }else {
//                    Common.showToast("请输入用户名");
//                }
            }
        });
        TextView showSign = findViewById(R.id.has_no_account);
        showSign.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showSignCard();
            }
        });
        TextView showLogin = findViewById(R.id.go_to_login);
        showLogin.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showLoginCard();
            }
        });
    }


    @Override
    protected void initData() {
        rotate = springSystem.createSpring();
        rotate.setSpringConfig(SpringConfig.fromOrigamiTensionAndFriction(15, 8));
    }

    public void showSignCard(){
        cardLogin.setVisibility(View.INVISIBLE);
        cardSign.setVisibility(View.VISIBLE);
        rotate.setCurrentValue(0);
        cardSign.setCameraDistance(80000.0f);
        rotate.addListener(new SimpleSpringListener(){
            @Override
            public void onSpringUpdate(Spring spring) {
                cardSign.setRotationY((float) spring.getCurrentValue());
            }

            @Override
            public void onSpringAtRest(Spring spring) {

            }
        });
        rotate.setEndValue(360.0f);
    }

    public void showLoginCard(){
        cardSign.setVisibility(View.INVISIBLE);
        cardLogin.setVisibility(View.VISIBLE);
        rotate.setCurrentValue(0);
        cardLogin.setCameraDistance(80000.0f);
        rotate.addListener(new SimpleSpringListener(){
            @Override
            public void onSpringUpdate(Spring spring) {
                cardLogin.setRotationY((float) spring.getCurrentValue());
            }

            @Override
            public void onSpringAtRest(Spring spring) {

            }
        });
        rotate.setEndValue(360.0f);
    }

    private void sign(){
        Common.hideKeyboard(mActivity);
        mProgressBar.setVisibility(View.VISIBLE);
        Retro.getSignApi().nowSign(SIGN_TOKEN,
                signName.getText().toString(), SIGN_REF)
                .subscribeOn(Schedulers.newThread())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(new ErrorCtrl<SignResponse>() {
                    @Override
                    public void onNext(SignResponse signResponse) {
                        if(signResponse != null){
                            if(signResponse.isError()){
                                if (!TextUtils.isEmpty(signResponse.getMessage())) {
                                    Common.showToast(signResponse.getMessage());
                                }else {
                                    Common.showToast("未知错误");
                                }
                                mProgressBar.setVisibility(View.INVISIBLE);
                            }else {
                                login(signResponse.getBody().getUser_account(), signResponse.getBody().getPassword());
                            }
                        }
                    }
                });
    }

    private void login(String username, String pwd){
        Common.hideKeyboard(mActivity);
        mProgressBar.setVisibility(View.VISIBLE);
        Retro.getAccountApi().login(
                CLIENT_ID,
                CLIENT_SECRET,
                DEVICE_TOKEN,
                true,
                "password",
                true,
                pwd,
                username).subscribeOn(Schedulers.newThread())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(new Observer<UserModel>() {
                    @Override
                    public void onSubscribe(Disposable d) {

                    }

                    @Override
                    public void onNext(UserModel userModel) {
                        if(userModel != null){
                            UserBean.ProfileImageUrlsBean profile_image_urls = userModel.getResponse().getUser().getProfile_image_urls();
                            profile_image_urls.setMedium(profile_image_urls.getPx_50x50());

                            userModel.getResponse().getUser().setPassword(password.getText().toString());
                            Local.saveUser(userModel);
                            UserEntity userEntity = new UserEntity();
                            userEntity.setLoginTime(System.currentTimeMillis());
                            userEntity.setUserID(userModel.getResponse().getUser().getId());
                            userEntity.setUserGson(new Gson().toJson(userModel));
                            AppDatabase.getAppDatabase(mContext).downloadDao().insertUser(userEntity);
                            mProgressBar.setVisibility(View.INVISIBLE);
                            Intent intent = new Intent(mContext, CoverActivity.class);
                            startActivity(intent);
                            finish();
                        }
                    }

                    @Override
                    public void onError(Throwable e) {
                        e.printStackTrace();
                    }

                    @Override
                    public void onComplete() {

                    }
                });
    }
}
