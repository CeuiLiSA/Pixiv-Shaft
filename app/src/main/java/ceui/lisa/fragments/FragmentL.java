package ceui.lisa.fragments;

import android.content.Intent;
import android.text.TextUtils;
import android.view.MenuItem;
import android.view.View;

import androidx.appcompat.widget.Toolbar;

import com.facebook.rebound.SimpleSpringListener;
import com.facebook.rebound.Spring;
import com.facebook.rebound.SpringConfig;
import com.facebook.rebound.SpringSystem;
import com.google.gson.Gson;

import java.io.IOException;

import ceui.lisa.R;
import ceui.lisa.activities.CoverActivity;
import ceui.lisa.activities.Shaft;
import ceui.lisa.activities.TemplateActivity;
import ceui.lisa.database.AppDatabase;
import ceui.lisa.database.UserEntity;
import ceui.lisa.databinding.ActivityLoginBinding;
import ceui.lisa.dialogs.Avoid251Dialog;
import ceui.lisa.http.ErrorCtrl;
import ceui.lisa.http.NullCtrl;
import ceui.lisa.http.Retro;
import ceui.lisa.model.ErrorResponse;
import ceui.lisa.models.SignResponse;
import ceui.lisa.models.UserModel;
import ceui.lisa.utils.Common;
import ceui.lisa.utils.Dev;
import ceui.lisa.utils.Local;
import ceui.lisa.utils.Params;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.schedulers.Schedulers;
import retrofit2.HttpException;

public class FragmentL extends BaseBindFragment<ActivityLoginBinding> {

    public static final String CLIENT_ID = "MOBrBDS8blbauoSck0ZfDbtuzpyT";
    public static final String CLIENT_SECRET = "lsACyCD94FhDUtGTXi3QzcFE2uU1hqtDaKeqrdwj";
    public static final String DEVICE_TOKEN = "pixiv";
    private static final String SIGN_TOKEN = "Bearer l-f9qZ0ZyqSwRyZs8-MymbtWBbSxmCu1pmbOlyisou8";
    private static final String SIGN_REF = "pixiv_android_app_provisional_account";
    private SpringSystem springSystem = SpringSystem.create();
    private Spring rotate;


    @Override
    void initLayout() {
        mLayoutID = R.layout.activity_login;
    }

    @Override
    public void initView(View view) {
        baseBind.toolbar.setPadding(0, Shaft.statusHeight, 0, 0);
        baseBind.toolbar.inflateMenu(R.menu.main);
        baseBind.toolbar.setOnMenuItemClickListener(new Toolbar.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(MenuItem item) {
                if (item.getItemId() == R.id.action_settings) {
                    Intent intent = new Intent(mContext, TemplateActivity.class);
                    intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "设置");
                    startActivity(intent);
                    return true;
                }
                return false;
            }
        });
        if (Shaft.sUserModel != null) {
            baseBind.userName.setText(Shaft.sUserModel.getResponse().getUser().getAccount());
            baseBind.password.requestFocus();
        }
        if (Dev.isDev) {
            baseBind.userName.setText(Dev.USER_ACCOUNT);
            baseBind.password.setText(Dev.USER_PWD);
        }
        baseBind.login.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (baseBind.userName.getText().toString().length() != 0) {
                    if (baseBind.password.getText().toString().length() != 0) {
                        login(baseBind.userName.getText().toString(), baseBind.password.getText().toString());
                    } else {
                        Common.showToast("请输入密码");
                    }
                } else {
                    Common.showToast("请输入用户名");
                }
            }
        });
        baseBind.sign.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (baseBind.signUserName.getText().toString().length() != 0) {
                    sign();
                } else {
                    Common.showToast("请输入用户名");
                }
            }
        });
        baseBind.hasNoAccount.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showSignCard();
            }
        });
        baseBind.goToLogin.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showLoginCard();
            }
        });
    }

    @Override
    void initData() {
        if (Local.getBoolean(Params.SHOW_DIALOG, true)) {
            Avoid251Dialog avoid251Dialog = new Avoid251Dialog();
            avoid251Dialog.show(getChildFragmentManager(), "Avoid251Dialog");
        }
        rotate = springSystem.createSpring();
        rotate.setSpringConfig(SpringConfig.fromOrigamiTensionAndFriction(15, 8));
    }

    public void showSignCard() {
        baseBind.fragmentLogin.setVisibility(View.INVISIBLE);
        baseBind.fragmentSign.setVisibility(View.VISIBLE);
        rotate.setCurrentValue(0);
        baseBind.fragmentSign.setCameraDistance(80000.0f);
        rotate.addListener(new SimpleSpringListener() {
            @Override
            public void onSpringUpdate(Spring spring) {
                baseBind.fragmentSign.setRotationY((float) spring.getCurrentValue());
            }

            @Override
            public void onSpringAtRest(Spring spring) {

            }
        });
        rotate.setEndValue(360.0f);
    }

    public void showLoginCard() {
        baseBind.fragmentSign.setVisibility(View.INVISIBLE);
        baseBind.fragmentLogin.setVisibility(View.VISIBLE);
        rotate.setCurrentValue(0);
        baseBind.fragmentLogin.setCameraDistance(80000.0f);
        rotate.addListener(new SimpleSpringListener() {
            @Override
            public void onSpringUpdate(Spring spring) {
                baseBind.fragmentLogin.setRotationY((float) spring.getCurrentValue());
            }

            @Override
            public void onSpringAtRest(Spring spring) {

            }
        });
        rotate.setEndValue(360.0f);
    }

    private void sign() {
        Common.hideKeyboard(mActivity);
        baseBind.progress.setVisibility(View.VISIBLE);
        Retro.getSignApi().pixivSign(SIGN_TOKEN, baseBind.signUserName.getText().toString(), SIGN_REF)
                .subscribeOn(Schedulers.newThread())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(new ErrorCtrl<SignResponse>() {
                    @Override
                    public void onNext(SignResponse signResponse) {
                        if (signResponse != null) {
                            if (signResponse.isError()) {
                                if (!TextUtils.isEmpty(signResponse.getMessage())) {
                                    Common.showToast(signResponse.getMessage());
                                } else {
                                    Common.showToast("未知错误");
                                }
                                baseBind.progress.setVisibility(View.INVISIBLE);
                            } else {
                                login(signResponse.getBody().getUser_account(), signResponse.getBody().getPassword());
                            }
                        }
                    }
                });
    }

    private void login(String username, String pwd) {
        Common.hideKeyboard(mActivity);
        baseBind.progress.setVisibility(View.VISIBLE);
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
                .subscribe(new NullCtrl<UserModel>() {
                    @Override
                    public void success(UserModel userModel) {
                        userModel.getResponse().getUser().setPassword(pwd);
                        Local.saveUser(userModel);
                        UserEntity userEntity = new UserEntity();
                        userEntity.setLoginTime(System.currentTimeMillis());
                        userEntity.setUserID(userModel.getResponse().getUser().getId());
                        userEntity.setUserGson(new Gson().toJson(userModel));
                        AppDatabase.getAppDatabase(mContext).downloadDao().insertUser(userEntity);
                        baseBind.progress.setVisibility(View.INVISIBLE);
                        Intent intent = new Intent(mContext, CoverActivity.class);
                        startActivity(intent);
                        mActivity.finish();
                    }

                    @Override
                    public void onError(Throwable e) {
                        baseBind.progress.setVisibility(View.INVISIBLE);
                        e.printStackTrace();
                        if (e instanceof HttpException) {
                            try {
                                HttpException httpException = (HttpException) e;
                                String responseString = httpException.response().errorBody().string();
                                Gson gson = new Gson();  //这个errorBody().string()只能获取一次，下一次就为空了
                                ErrorResponse response = gson.fromJson(responseString, ErrorResponse.class);
                                if (response != null) {
                                    Common.showToast(response.getErrors().getSystem().getMessage());
                                }
                            } catch (IOException ex) {
                                ex.printStackTrace();
                            }
                        }
                    }
                });
    }
}
