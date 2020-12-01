package ceui.lisa.fragments;

import android.content.DialogInterface;
import android.content.Intent;
import android.text.TextUtils;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.Toolbar;

import com.blankj.utilcode.util.BarUtils;
import com.facebook.rebound.SimpleSpringListener;
import com.facebook.rebound.Spring;
import com.facebook.rebound.SpringConfig;
import com.facebook.rebound.SpringSystem;

import java.util.Locale;

import ceui.lisa.R;
import ceui.lisa.activities.MainActivity;
import ceui.lisa.activities.Shaft;
import ceui.lisa.activities.TemplateActivity;
import ceui.lisa.database.AppDatabase;
import ceui.lisa.database.UserEntity;
import ceui.lisa.databinding.ActivityLoginBinding;
import ceui.lisa.http.NullCtrl;
import ceui.lisa.http.Retro;
import ceui.lisa.models.SignResponse;
import ceui.lisa.models.UserModel;
import ceui.lisa.utils.Base64Util;
import ceui.lisa.utils.ClipBoardUtils;
import ceui.lisa.utils.Common;
import ceui.lisa.utils.Dev;
import ceui.lisa.utils.Local;
import ceui.lisa.utils.Params;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.schedulers.Schedulers;

public class FragmentLogin extends BaseFragment<ActivityLoginBinding> {

    public static final String CLIENT_ID = "MOBrBDS8blbauoSck0ZfDbtuzpyT";
    public static final String CLIENT_SECRET = "lsACyCD94FhDUtGTXi3QzcFE2uU1hqtDaKeqrdwj";
    public static final String DEVICE_TOKEN = "pixiv";
    public static final String TYPE_PASSWORD = "password";
    public static final String REFRESH_TOKEN = "refresh_token";
    private static final String SIGN_TOKEN = "Bearer l-f9qZ0ZyqSwRyZs8-MymbtWBbSxmCu1pmbOlyisou8";
    private static final String SIGN_REF = "pixiv_android_app_provisional_account";
    private static final int TAPS_TO_BE_A_DEVELOPER = 7;
    private SpringSystem springSystem = SpringSystem.create();
    private Spring rotate;
    private int mHitCountDown;//
    private Toast mHitToast;

    @Override
    public void onResume() {
        super.onResume();
        mHitCountDown = TAPS_TO_BE_A_DEVELOPER;
    }

    @Override
    public void initLayout() {
        mLayoutID = R.layout.activity_login;
    }

    @Override
    public void initView() {
        baseBind.toolbar.setPadding(0, Shaft.statusHeight, 0, 0);
        baseBind.toolbar.inflateMenu(R.menu.login_menu);
        baseBind.toolbar.setOnMenuItemClickListener(new Toolbar.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(MenuItem item) {
                if (item.getItemId() == R.id.action_settings) {
                    Intent intent = new Intent(mContext, TemplateActivity.class);
                    intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "设置");
                    startActivity(intent);
                    return true;
                } else if (item.getItemId() == R.id.action_import) {
                    String userJson = ClipBoardUtils.getClipboardContent(mContext);
                    if (userJson != null
                            && !TextUtils.isEmpty(userJson)
                            && userJson.contains(Params.USER_KEY)) {
                        Common.showToast("导入成功", 2);
                        UserModel exportUser = Shaft.sGson.fromJson(userJson, UserModel.class);

                        String pwd = exportUser.getResponse().getUser().getPassword();
                        //如果是新版本加密过的,解密一下
                        if (!TextUtils.isEmpty(pwd) && pwd.startsWith(Params.SECRET_PWD_KEY)) {
                            String secret = pwd.substring(Params.SECRET_PWD_KEY.length());
                            String realPwd = Base64Util.decode(secret);
                            Common.showLog(className + "real password: " + realPwd);
                            exportUser.getResponse().getUser().setPassword(realPwd);
                        }
                        Local.saveUser(exportUser);
                        Dev.refreshUser = true;
                        Shaft.sUserModel = exportUser;
                        Intent intent = new Intent(mContext, MainActivity.class);
                        MainActivity.newInstance(intent, mContext);
                        mActivity.finish();
                    } else {
                        Common.showToast("剪贴板无用户信息", 3);
                    }
                    return true;
                }
                return false;
            }
        });
        setTitle();
        baseBind.title.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mHitCountDown > 0) {
                    mHitCountDown--;
                    if (mHitCountDown == 0) {
                        showDialog();
                    } else if (mHitCountDown > 0 && mHitCountDown < TAPS_TO_BE_A_DEVELOPER - 2) {
                        if (mHitToast != null) {
                            mHitToast.cancel();
                        }
                        mHitToast = Toast.makeText(mActivity, String.format(Locale.getDefault(),
                                "点击%d次切换版本", mHitCountDown), Toast.LENGTH_SHORT);
                        mHitToast.show();
                    }
                } else {
                    showDialog();
                }
            }
        });
        if (Shaft.sUserModel != null) {
            baseBind.userName.setText(Shaft.sUserModel.getResponse().getUser().getAccount());
            baseBind.password.requestFocus();
        }
        if (Dev.isDev) {
            baseBind.userName.setText(Dev.USER_ACCOUNT);
            baseBind.password.setText(Dev.USER_PWD);
            baseBind.password.setSelection(Dev.USER_PWD.length());
        }
        baseBind.login.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (baseBind.userName.getText().toString().length() != 0) {
                    if (baseBind.password.getText().toString().length() != 0) {
                        login(baseBind.userName.getText().toString(), baseBind.password.getText().toString());
                    } else {
                        Common.showToast("请输入密码", 3);
                    }
                } else {
                    Common.showToast("请输入用户名", 3);
                }
            }
        });
        baseBind.sign.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (baseBind.signUserName.getText().toString().length() != 0) {
                    sign();
                } else {
                    Common.showToast("请输入用户名", 3);
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

    private void setTitle() {
        if (Local.getBoolean(Params.USE_DEBUG, false)) {
            baseBind.title.setText("Shaft(测试版)");
            baseBind.userName.setText(Dev.USER_ACCOUNT);
            baseBind.password.setText(Dev.USER_PWD);
            baseBind.password.setSelection(Dev.USER_PWD.length());
        } else {
            baseBind.title.setText("Shaft");
        }
    }

    private void showDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(mContext);
        String[] titles = new String[]{"使用正式版", "使用测试版"};
        builder.setItems(titles, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                if (which == 0) {
                    Local.setBoolean(Params.USE_DEBUG, false);
                    Dev.isDev = false;
                } else if (which == 1) {
                    Local.setBoolean(Params.USE_DEBUG, true);
                    Dev.isDev = true;
                }
                mHitCountDown = TAPS_TO_BE_A_DEVELOPER;
                setTitle();
            }
        });
        AlertDialog alertDialog = builder.create();
        alertDialog.show();
    }

    @Override
    protected void initData() {
        if (Local.getBoolean(Params.SHOW_DIALOG, true)) {
            Common.createDialog(mContext);
        }
        rotate = springSystem.createSpring();
        rotate.setSpringConfig(SpringConfig.fromOrigamiTensionAndFriction(15, 8));

        //使两个cardview高度，大小保持一致
        baseBind.cardLogin.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override
            public void onGlobalLayout() {
                final int height = baseBind.cardLogin.getHeight();

                ViewGroup.LayoutParams paramsSign = baseBind.cardSign.getLayoutParams();
                paramsSign.height = height;
                baseBind.cardSign.setLayoutParams(paramsSign);

                baseBind.cardLogin.getViewTreeObserver().removeOnGlobalLayoutListener(this);
            }
        });
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
                .subscribe(new NullCtrl<SignResponse>() {
                    @Override
                    public void success(SignResponse signResponse) {
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
                });
    }

    private void login(String username, String pwd) {
        Common.hideKeyboard(mActivity);
        baseBind.progress.setVisibility(View.VISIBLE);
        Retro.getAccountApi().login(
                CLIENT_ID,
                CLIENT_SECRET,
                DEVICE_TOKEN,
                Boolean.TRUE,
                TYPE_PASSWORD,
                Boolean.TRUE,
                pwd,
                username).subscribeOn(Schedulers.newThread())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(new NullCtrl<UserModel>() {
                    @Override
                    public void success(UserModel userModel) {
                        userModel.getResponse().getUser().setPassword(pwd);
                        userModel.getResponse().getUser().setIs_login(true);
                        Local.saveUser(userModel);


                        UserEntity userEntity = new UserEntity();
                        userEntity.setLoginTime(System.currentTimeMillis());
                        userEntity.setUserID(userModel.getResponse().getUser().getId());
                        userEntity.setUserGson(Shaft.sGson.toJson(Local.getUser()));



                        AppDatabase.getAppDatabase(mContext).downloadDao().insertUser(userEntity);
                        baseBind.progress.setVisibility(View.INVISIBLE);
                        if (isAdded()) {
                            Intent intent = new Intent(mContext, MainActivity.class);
                            mActivity.startActivity(intent);
                            mActivity.finish();
                        }
                    }

                    @Override
                    public void onError(Throwable e) {
                        super.onError(e);
                        baseBind.progress.setVisibility(View.INVISIBLE);
                    }
                });
    }
}
