package ceui.lisa.fragments;

import android.content.DialogInterface;
import android.content.Intent;
import android.os.Build;
import android.text.TextUtils;
import android.text.method.HideReturnsTransformationMethod;
import android.text.method.PasswordTransformationMethod;
import android.util.Base64;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.CompoundButton;
import android.widget.Toast;

import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.Toolbar;
import androidx.webkit.ProxyConfig;
import androidx.webkit.ProxyController;
import androidx.webkit.WebViewFeature;

import com.blankj.utilcode.util.EncodeUtils;
import com.blankj.utilcode.util.EncryptUtils;
import com.facebook.rebound.SimpleSpringListener;
import com.facebook.rebound.Spring;
import com.facebook.rebound.SpringConfig;
import com.facebook.rebound.SpringSystem;

import java.io.UnsupportedEncodingException;
import java.security.SecureRandom;
import java.util.Locale;
import java.util.concurrent.Executor;

import ceui.lisa.R;
import ceui.lisa.activities.MainActivity;
import ceui.lisa.activities.Shaft;
import ceui.lisa.activities.TemplateActivity;
import ceui.lisa.database.AppDatabase;
import ceui.lisa.database.UserEntity;
import ceui.lisa.databinding.ActivityLoginBinding;
import ceui.lisa.feature.HostManager;
import ceui.lisa.feature.PkceUtil;
import ceui.lisa.feature.WeissUtil;
import ceui.lisa.http.NullCtrl;
import ceui.lisa.http.Retro;
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

    public static final String IOS_CLIENT_ID = "KzEZED7aC0vird8jWyHM38mXjNTY";
    public static final String IOS_CLIENT_SECRET = "W9JZoJe00qPvJsiyCGT3CCtC6ZUtdpKpzMbNlUGP";

    public static final String CLIENT_ID = "MOBrBDS8blbauoSck0ZfDbtuzpyT";
    public static final String CLIENT_SECRET = "lsACyCD94FhDUtGTXi3QzcFE2uU1hqtDaKeqrdwj";
    public static final String DEVICE_TOKEN = "pixiv";
    public static final String TYPE_PASSWORD = "password";
    public static final String REFRESH_TOKEN = "refresh_token";
    public static final String AUTH_CODE = "authorization_code";
    public static final String CALL_BACK = "https://app-api.pixiv.net/web/v1/users/auth/pixiv/callback";
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

                        String pwd = exportUser.getUser().getPassword();
                        //如果是新版本加密过的,解密一下
                        if (!TextUtils.isEmpty(pwd) && pwd.startsWith(Params.SECRET_PWD_KEY)) {
                            String secret = pwd.substring(Params.SECRET_PWD_KEY.length());
                            String realPwd = Base64Util.decode(secret);
                            Common.showLog(className + "real password: " + realPwd);
                            exportUser.getUser().setPassword(realPwd);
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
        baseBind.login.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (Shaft.sSettings.isAutoFuckChina()) {
                    WeissUtil.start();
                    WeissUtil.proxy();
                }
                Intent intent = new Intent(mContext, TemplateActivity.class);
                intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "网页链接");
                intent.putExtra(Params.URL, "https://app-api.pixiv.net/web/v1/login?code_challenge=" +
                                HostManager.get().getPkceItem().getChallenge() +
                        "&code_challenge_method=S256&client=pixiv-android");
                intent.putExtra(Params.TITLE, getString(R.string.now_login));
                intent.putExtra(Params.PREFER_PRESERVE, true);
                startActivity(intent);
            }
        });

        baseBind.sign.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (Shaft.sSettings.isAutoFuckChina()) {
                    WeissUtil.start();
                    WeissUtil.proxy();
                }
                Intent intent = new Intent(mContext, TemplateActivity.class);
                intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "网页链接");
                intent.putExtra(Params.URL, "https://app-api.pixiv.net/web/v1/provisional-accounts/create?code_challenge=" +
                        HostManager.get().getPkceItem().getChallenge() +
                        "&code_challenge_method=S256&client=pixiv-android");
                intent.putExtra(Params.TITLE, getString(R.string.now_sign));
                intent.putExtra(Params.PREFER_PRESERVE, true);
                startActivity(intent);
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
        if (Shaft.getMMKV().decodeBool(Params.USE_DEBUG, false)) {
            baseBind.title.setText("Shaft(测试版)");
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
                    Shaft.getMMKV().encode(Params.USE_DEBUG, false);
                    Dev.isDev = false;
                } else if (which == 1) {
                    Shaft.getMMKV().encode(Params.USE_DEBUG, true);
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
        if (Shaft.getMMKV().decodeBool(Params.SHOW_DIALOG, true)) {
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
}
