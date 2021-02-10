package ceui.lisa.activities;

import android.content.Intent;
import android.net.Uri;
import android.text.TextUtils;
import android.view.View;

import java.util.List;

import ceui.lisa.R;
import ceui.lisa.database.AppDatabase;
import ceui.lisa.database.UserEntity;
import ceui.lisa.databinding.ActivityOutWakeBinding;
import ceui.lisa.feature.HostManager;
import ceui.lisa.fragments.FragmentLogin;
import ceui.lisa.http.AccountApi;
import ceui.lisa.http.NullCtrl;
import ceui.lisa.http.Retro;
import ceui.lisa.interfaces.Callback;
import ceui.lisa.models.UserModel;
import ceui.lisa.utils.Common;
import ceui.lisa.utils.Local;
import ceui.lisa.utils.Params;
import ceui.lisa.utils.PixivOperate;
import io.reactivex.Scheduler;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.schedulers.Schedulers;
import retrofit2.Call;
import retrofit2.Response;

import static ceui.lisa.activities.Shaft.sUserModel;

public class OutWakeActivity extends BaseActivity<ActivityOutWakeBinding> {

    public static final String HOST_ME = "pixiv.me";

    @Override
    protected int initLayout() {
        return R.layout.activity_out_wake;
    }

    @Override
    public boolean hideStatusBar() {
        return true;
    }

    @Override
    protected void initView() {

    }

    @Override
    protected void initData() {
        Intent intent = getIntent();
        if (intent != null) {
            Uri uri = intent.getData();
            if (uri != null) {

                String scheme = uri.getScheme();
                if (!TextUtils.isEmpty(scheme)) {

                    if (uri.getPath() != null) {
                        if (uri.getPath().contains("artworks")) {
                            List<String> pathArray = uri.getPathSegments();
                            String illustID = pathArray.get(pathArray.size() - 1);
                            if (!TextUtils.isEmpty(illustID)) {
                                PixivOperate.getIllustByID(Shaft.sUserModel, Integer.valueOf(illustID), mContext, new Callback<Void>() {
                                    @Override
                                    public void doSomething(Void t) {
                                        finish();
                                    }
                                });
                                finish();
                                return;
                            }
                        }

                        if (uri.getPath().contains("users")) {
                            List<String> pathArray = uri.getPathSegments();
                            String userID = pathArray.get(pathArray.size() - 1);
                            if (!TextUtils.isEmpty(userID)) {
                                Intent userIntent = new Intent(mContext, UserActivity.class);
                                userIntent.putExtra(Params.USER_ID, Integer.valueOf(userID));
                                startActivity(userIntent);
                                finish();
                                return;
                            }
                        }
                    }


                    //http网页跳转到这里
                    if (scheme.contains("http")) {
                        try {
                            String uriString = uri.toString();
                            if (uriString.contains(HostManager.HOST_OLD)) {
                                int index = uriString.lastIndexOf("/");
                                String end = uriString.substring(index + 1);
                                String idString = end.split("_")[0];

                                Common.showLog("end " + end + " idString " + idString);
                                PixivOperate.getIllustByID(Shaft.sUserModel, Integer.parseInt(idString), mContext, new Callback<Void>() {
                                    @Override
                                    public void doSomething(Void t) {
                                        finish();
                                    }
                                });
                                return;
                            } else if (uriString.contains(HOST_ME)) {
                                Intent i = new Intent(mContext, TemplateActivity.class);
                                i.putExtra(Params.URL, uriString);
                                i.putExtra(Params.TITLE, HOST_ME);
                                i.putExtra(TemplateActivity.EXTRA_FRAGMENT, "网页链接");
                                startActivity(i);
                                finish();
                                return;
                            }
                        } catch (Exception e) {
                            e.printStackTrace();
                        }


                        String illustID = uri.getQueryParameter("illust_id");
                        if (!TextUtils.isEmpty(illustID)) {
                            PixivOperate.getIllustByID(Shaft.sUserModel, Integer.parseInt(illustID), mContext, new Callback<Void>() {
                                @Override
                                public void doSomething(Void t) {
                                    finish();
                                }
                            });
                            return;
                        }

                        String userID = uri.getQueryParameter("id");
                        if (!TextUtils.isEmpty(userID)) {
                            Intent userIntent = new Intent(mContext, UserActivity.class);
                            userIntent.putExtra(Params.USER_ID, Integer.valueOf(userID));
                            startActivity(userIntent);
                            finish();
                            return;
                        }

                    }

                    //pixiv内部链接，如
                    //pixiv://illusts/73190863
                    //pixiv://account/login?code=BsQND5vc6uIWKIwLiDsh0S3h1yno6eVHDVMrX3fONgM&via=login
                    if (scheme.contains("pixiv")) {
                        String host = uri.getHost();


                        if (!TextUtils.isEmpty(host)) {

                            if (host.equals("account")) {
                                Common.showToast("尝试登陆");
                                String code = uri.getQueryParameter("code");
                                Retro.getAccountApi().newLogin(
                                        FragmentLogin.CLIENT_ID,
                                        FragmentLogin.CLIENT_SECRET,
                                        FragmentLogin.AUTH_CODE,
                                        code,
                                        HostManager.get().getPkceItem().getVerify(),
                                        FragmentLogin.CALL_BACK,
                                        true
                                ).subscribeOn(Schedulers.newThread())
                                .observeOn(AndroidSchedulers.mainThread())
                                .subscribe(new NullCtrl<UserModel>() {
                                    @Override
                                    public void success(UserModel userModel) {

                                        Common.showLog(userModel.toString());

                                        userModel.getResponse().getUser().setIs_login(true);
                                        Local.saveUser(userModel);

                                        UserEntity userEntity = new UserEntity();
                                        userEntity.setLoginTime(System.currentTimeMillis());
                                        userEntity.setUserID(userModel.getResponse().getUser().getId());
                                        userEntity.setUserGson(Shaft.sGson.toJson(Local.getUser()));


                                        AppDatabase.getAppDatabase(mContext).downloadDao().insertUser(userEntity);
                                        Intent intent = new Intent(mContext, MainActivity.class);
                                        mActivity.startActivity(intent);
                                    }

                                    @Override
                                    public void must() {
                                        super.must();
                                        mActivity.finish();
                                    }
                                });
                                return;
                            }

                            if (host.contains("users")) {
                                String path = uri.getPath();
                                Intent userIntent = new Intent(mContext, UserActivity.class);
                                userIntent.putExtra(Params.USER_ID, Integer.valueOf(path.substring(1)));
                                startActivity(userIntent);
                                finish();
                                return;
                            }

                            if (host.contains("illusts")) {
                                String path = uri.getPath();
                                PixivOperate.getIllustByID(Shaft.sUserModel, Integer.valueOf(path.substring(1)),
                                        mContext, t -> finish());
                                return;
                            }

                            if (host.contains("novels")) {
                                String path = uri.getPath();
                                PixivOperate.getNovelByID(Shaft.sUserModel, Integer.valueOf(path.substring(1)),
                                        mContext, t -> finish());
                                return;
                            }
                        }
                    }
                }
            }
        }

        if (sUserModel != null && sUserModel.getResponse().getUser().isIs_login()) {
            Intent i = new Intent(mContext, MainActivity.class);
            mActivity.startActivity(i);
            mActivity.finish();
        } else {
            Intent i = new Intent(mContext, TemplateActivity.class);
            i.putExtra(TemplateActivity.EXTRA_FRAGMENT, "登录注册");
            startActivity(i);
            finish();
        }
    }
}
