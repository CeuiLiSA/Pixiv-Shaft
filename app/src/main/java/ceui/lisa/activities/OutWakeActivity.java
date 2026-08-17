package ceui.lisa.activities;

import android.content.Intent;
import android.net.Uri;
import android.text.TextUtils;

import ceui.pixiv.witstudio.dialog.WitSkinManager;
import ceui.pixiv.witstudio.dialog.WitDialog;
import ceui.pixiv.witstudio.dialog.WitDialogAction;

import java.util.List;

import ceui.lisa.R;
import ceui.lisa.database.AppDatabase;
import ceui.lisa.database.UserEntity;
import ceui.lisa.databinding.ActivityOutWakeBinding;
import ceui.lisa.interfaces.Callback;
import ceui.lisa.models.UserModel;
import ceui.lisa.utils.Common;
import ceui.lisa.utils.Local;
import ceui.lisa.utils.Params;
import ceui.lisa.utils.PixivOperate;
import ceui.pixiv.login.PixivLogin;
import ceui.pixiv.login.PixivOAuthResult;
import ceui.pixiv.session.SessionManager;
import io.reactivex.Observable;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.schedulers.Schedulers;

public class OutWakeActivity extends BaseActivity<ActivityOutWakeBinding> {

    public static final String HOST_ME = "pixiv.me";
    public static final String HOST_PIXIVISION = "pixivision.net";
    public static boolean isNetWorking = false;

    // 已经发起过 token 交换的登录 code。OAuth 授权码是单次性的,重复提交会被 Pixiv
    // 拒成「不正确的请求」(invalid_request)。static 是为了跨 Activity 重建(配置变化/
    // 回调重投递)仍能去重,避免同一个 code 交换两次。#892
    private static String sHandledLoginCode = null;

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
                        if (uri.getPathSegments().contains("artworks") || uri.getPathSegments().contains("i")) {
                            if (isNetWorking) {
                                return;
                            }
                            isNetWorking = true;
                            List<String> pathArray = uri.getPathSegments();
                            String illustID = pathArray.get(pathArray.size() - 1);
                            if (!TextUtils.isEmpty(illustID)) {
                                PixivOperate.getIllustByID(tryParseId(illustID), mContext, new Callback<Void>() {
                                    @Override
                                    public void doSomething(Void t) {
                                        finish();
                                    }
                                },null);
                                // finish(); // wait for callback
                                return;
                            }
                        }

                        if (uri.getPathSegments().contains("novel") && !TextUtils.isEmpty(uri.getQueryParameter("id"))
                                || uri.getPathSegments().contains("n")) {
                            if (isNetWorking) {
                                return;
                            }
                            isNetWorking = true;
                            String novelId;
                            if (uri.getPathSegments().contains("novel") && !TextUtils.isEmpty(uri.getQueryParameter("id"))) {
                                novelId = uri.getQueryParameter("id");
                            } else {
                                List<String> pathArray = uri.getPathSegments();
                                novelId = pathArray.get(pathArray.size() - 1);
                            }
                            PixivOperate.getNovelByID(tryParseId(novelId), mContext, new Callback<Void>() {
                                @Override
                                public void doSomething(Void t) {
                                    finish();
                                }
                            });
                            return;
                        }

                        if (uri.getPathSegments().contains("users") || uri.getPathSegments().contains("u")) {
                            List<String> pathArray = uri.getPathSegments();
                            String userID = pathArray.get(pathArray.size() - 1);
                            if (!TextUtils.isEmpty(userID)) {
                                Intent userIntent = new Intent(mContext, UActivity.class);
                                userIntent.putExtra(Params.USER_ID, Common.safeUserId(userID));
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
                            if (uriString.toLowerCase().contains("i.pximg.net")) {
                                int index = uriString.lastIndexOf("/");
                                String end = uriString.substring(index + 1);
                                String idString = end.split("_")[0];

                                Common.showLog("end " + end + " idString " + idString);
                                PixivOperate.getIllustByID(tryParseId(idString), mContext, new Callback<Void>() {
                                    @Override
                                    public void doSomething(Void t) {
                                        finish();
                                    }
                                },null);
                                return;
                            } else if (uriString.toLowerCase().contains(HOST_ME)) {
                                Intent i = new Intent(mContext, TemplateActivity.class);
                                i.putExtra(Params.URL, uriString);
                                i.putExtra(Params.TITLE, HOST_ME);
                                i.putExtra(TemplateActivity.EXTRA_FRAGMENT, "网页链接");
                                startActivity(i);
                                finish();
                                return;
                            } else if (uriString.toLowerCase().contains(HOST_PIXIVISION)) {
                                Intent i = new Intent(mContext, TemplateActivity.class);
                                i.putExtra(Params.URL, uriString);
                                i.putExtra(Params.TITLE, getString(R.string.pixiv_special));
                                i.putExtra(TemplateActivity.EXTRA_FRAGMENT, "网页链接");
                                i.putExtra(Params.PREFER_PRESERVE, true);
                                startActivity(i);
                                finish();
                                return;
                            }
                        } catch (Exception e) {
                            e.printStackTrace();
                        }


                        String illustID = uri.getQueryParameter("illust_id");
                        if (!TextUtils.isEmpty(illustID)) {
                            PixivOperate.getIllustByID(tryParseId(illustID), mContext, new Callback<Void>() {
                                @Override
                                public void doSomething(Void t) {
                                    finish();
                                }
                            },null);
                            return;
                        }

                        String userID = uri.getQueryParameter("id");
                        if (!TextUtils.isEmpty(userID)) {
                            Intent userIntent = new Intent(mContext, UActivity.class);
                            userIntent.putExtra(Params.USER_ID, Common.safeUserId(userID));
                            startActivity(userIntent);
                            finish();
                            return;
                        }

                    }

                    //pixiv内部链接，如
                    //pixiv://users/73190863
                    //pixiv://illusts/73190863
                    //pixiv://account/login?code=BsQND5vc6uIWKIwLiDsh0S3h1yno6eVHDVMrX3fONgM&via=login
                    if (scheme.contains("pixiv") || scheme.contains("shaftintent")) {
                        String host = uri.getHost();


                        if (!TextUtils.isEmpty(host)) {

                            if (host.equals("account")) {
                                // 同一个 code 只交换一次。配置变化(旋转/深色模式)等会用同一个
                                // 回调 intent 重跑 initData,二次提交单次性 code 必被 Pixiv 拒成
                                // 「不正确的请求」。已处理过就跳过,落到底部登录态判断。#892
                                String loginCode = uri.getQueryParameter("code");
                                if (loginCode != null && loginCode.equals(sHandledLoginCode)) {
                                    if (SessionManager.INSTANCE.isLoggedIn()) {
                                        startActivity(new Intent(mContext, MainActivity.class));
                                    } else {
                                        backToLoginScreen();
                                        return;
                                    }
                                    finish();
                                    return;
                                }
                                sHandledLoginCode = loginCode;
                                Common.showToast(getString(R.string.trying_login));
                                Observable.fromCallable(() -> PixivLogin.INSTANCE.handleCallback(uri))
                                .subscribeOn(Schedulers.io())
                                .observeOn(AndroidSchedulers.mainThread())
                                .subscribe(result -> {
                                    if (result instanceof PixivOAuthResult.Failure) {
                                        PixivOAuthResult.Failure failure = (PixivOAuthResult.Failure) result;
                                        // 原始 message 写日志,Toast 给可操作的提示。#892:
                                        // 用户看到「不正确的请求」(ServerRejected) 却以为是网络问题,
                                        // 一直换节点,所以这里要把"该重新登录"和"该查网络"分开说。
                                        Common.showLog("OAuth login failed: " + failure.getMessage());
                                        String hint;
                                        if (failure instanceof PixivOAuthResult.Failure.NetworkError) {
                                            hint = "登录失败：网络连接不上,请检查网络或代理后重试";
                                        } else if (failure instanceof PixivOAuthResult.Failure.MissingVerifier) {
                                            hint = "登录已过期,请重新点击登录";
                                        } else if (failure instanceof PixivOAuthResult.Failure.MissingCode) {
                                            hint = "登录被取消或回调异常,请重新登录";
                                        } else if (failure instanceof PixivOAuthResult.Failure.ServerRejected) {
                                            // 换节点没用——是 Pixiv 服务端拒绝(多见 code 失效/重复使用),需要重新走一遍登录
                                            hint = "Pixiv 拒绝了登录请求(HTTP "
                                                    + ((PixivOAuthResult.Failure.ServerRejected) failure).getHttpCode()
                                                    + "),请重新点击登录(换节点无效)";
                                        } else {
                                            hint = "登录失败: " + failure.getMessage();
                                        }
                                        Common.showToast(hint);
                                        backToLoginScreen();
                                        return;
                                    }
                                    PixivOAuthResult.Success success = (PixivOAuthResult.Success) result;
                                    UserModel userModel = Shaft.sGson.fromJson(success.getRawBody(), UserModel.class);

                                    Common.showLog(userModel.toString());
                                    Common.showToast("登录成功");

                                    userModel.getUser().setIs_login(true);
                                    Local.saveUser(userModel);
                                    SessionManager.INSTANCE.updateSession(userModel);

                                    UserEntity userEntity = new UserEntity();
                                    userEntity.setLoginTime(System.currentTimeMillis());
                                    userEntity.setUserID(userModel.getUser().getId());
                                    userEntity.setUserGson(Shaft.sGson.toJson(Local.getUser()));

                                    AppDatabase.getAppDatabase(mContext).downloadDao().insertUser(userEntity);

                                    final UserModel loggedInUser = userModel;
                                    final Runnable proceedAfterMoonSync = () -> {
                                        // 检测是否打开R18并提示开启，新注册未验证邮箱用户不提示
                                        if (loggedInUser.getUser().isR18Enabled() || !loggedInUser.getUser().isIs_mail_authorized()) {
                                            mActivity.finish();
                                            Common.restart();
                                        } else {
                                            new WitDialog.MessageDialogBuilder(mActivity)
                                                    .setTitle(R.string.string_216)
                                                    .setMessage(R.string.string_400)
                                                    .setSkinManager(WitSkinManager.defaultInstance(mContext))
                                                    .addAction(R.string.string_401, new WitDialogAction.ActionListener() {
                                                        @Override
                                                        public void onClick(WitDialog dialog, int index) {
                                                            dialog.dismiss();
                                                            mActivity.finish();
                                                            Common.restart();
                                                        }
                                                    })
                                                    .addAction(R.string.string_402, new WitDialogAction.ActionListener() {
                                                        @Override
                                                        public void onClick(WitDialog dialog, int index) {
                                                            Intent intent1 = new Intent(mContext, TemplateActivity.class);
                                                            intent1.putExtra(TemplateActivity.EXTRA_FRAGMENT, "网页链接");
                                                            intent1.putExtra(Params.URL, Params.URL_R18_SETTING);
                                                            startActivity(intent1);
                                                        }
                                                    })
                                                    .create()
                                                    .show();
                                        }
                                    };

                                    // pixshaft-api: 上报当前「浏览记录云同步」开关,让 admin 的「关同步」
                                    // 列表能回填已有用户(之后每次拨开关也会即时上报)。fire-and-forget。
                                    ceui.pixiv.db.HistoryReporter.INSTANCE.reportSyncPref(
                                            loggedInUser.getUser().getId(),
                                            Shaft.sSettings.isCloudHistorySync()
                                    );

                                    // moonAPI: 拉取云端设置,如有新版本弹窗询问是否应用;完成后继续 R18 流程
                                    ceui.loxia.MoonSync.syncFromCloudOnLogin(
                                            mActivity,
                                            loggedInUser.getUser().getId(),
                                            proceedAfterMoonSync
                                    );
                                }, throwable -> {
                                    Common.showToast("登录失败");
                                    backToLoginScreen();
                                });
                                return;
                            }

                            if (host.contains("users")) {
                                String path = uri.getPath();
                                Intent userIntent = new Intent(mContext, UActivity.class);
                                userIntent.putExtra(Params.USER_ID, Common.safeUserId(path.substring(1)));
                                startActivity(userIntent);
                                finish();
                                return;
                            }

                            if (host.contains("illusts")) {
                                String path = uri.getPath();
                                PixivOperate.getIllustByID(tryParseId(path.substring(1)),
                                        mContext, t -> finish(),null);
                                return;
                            }

                            if (host.contains("novels")) {
                                String path = uri.getPath();
                                PixivOperate.getNovelByID(tryParseId(path.substring(1)),
                                        mContext, t -> finish());
                                return;
                            }

                            // shaftintent://search?... 对外暴露的搜索入口(#694)
                            //   关键词: shaftintent://search?keyword=xxx  (兼容 key=xxx)
                            //   作品ID: shaftintent://search?illust_id=xxx  (兼容 key=xxx&type=illust)
                            //   用户ID: shaftintent://search?user_id=xxx    (兼容 key=xxx&type=user)
                            //   小说ID: shaftintent://search?novel_id=xxx   (兼容 key=xxx&type=novel)
                            // 纯数字会先按 type 分发,无 type 时落到关键词搜索路径,避免歧义。
                            if (host.equals("search")) {
                                if (handleExternalSearch(uri)) {
                                    return;
                                }
                            }
                        }
                    }
                }
            }
        }

        if (SessionManager.INSTANCE.isLoggedIn()) {
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

    /**
     * 登录回调失败后把用户送回登录注册页并结束本 Activity。少了这步会一直卡在
     * activity_out_wake 的「资源解析中」loading 页,无法返回 (#892)。
     */
    private void backToLoginScreen() {
        Intent i = new Intent(mContext, TemplateActivity.class);
        i.putExtra(TemplateActivity.EXTRA_FRAGMENT, "登录注册");
        startActivity(i);
        finish();
    }

    /**
     * 处理 shaftintent://search?... 外部搜索 deep link。返回 true 表示已分发,
     * 调用方应直接 return;返回 false 走默认 fallback。
     */
    private boolean handleExternalSearch(Uri uri) {
        String illustId = uri.getQueryParameter("illust_id");
        String userId = uri.getQueryParameter("user_id");
        String novelId = uri.getQueryParameter("novel_id");
        String keyword = uri.getQueryParameter("keyword");
        String key = uri.getQueryParameter("key");
        String type = uri.getQueryParameter("type");

        // key + type 简写,映射到对应字段
        if (TextUtils.isEmpty(illustId) && TextUtils.isEmpty(userId)
                && TextUtils.isEmpty(novelId) && !TextUtils.isEmpty(key)) {
            if ("illust".equalsIgnoreCase(type)) {
                illustId = key;
            } else if ("user".equalsIgnoreCase(type)) {
                userId = key;
            } else if ("novel".equalsIgnoreCase(type)) {
                novelId = key;
            } else if (TextUtils.isEmpty(keyword)) {
                // 无 type 时把 key 当 keyword 处理。即使是纯数字也按关键词搜,
                // 让 SearchActivity 里同时给出关键词命中和 ID 直达提示,避免猜错意图。
                keyword = key;
            }
        }

        // ID 路径:粘贴来的 ID 可能夹杂表情/符号,跟 SearchAllFragment.checkAndNextId 一致,只保留数字
        if (!TextUtils.isEmpty(illustId)) {
            String digits = illustId.replaceAll("\\D", "");
            if (TextUtils.isEmpty(digits)) return false;
            PixivOperate.getIllustByID(tryParseId(digits), mContext, t -> finish(), null);
            return true;
        }
        if (!TextUtils.isEmpty(userId)) {
            String digits = userId.replaceAll("\\D", "");
            if (TextUtils.isEmpty(digits)) return false;
            Intent userIntent = new Intent(mContext, UActivity.class);
            userIntent.putExtra(Params.USER_ID, Common.safeUserId(digits));
            startActivity(userIntent);
            finish();
            return true;
        }
        if (!TextUtils.isEmpty(novelId)) {
            String digits = novelId.replaceAll("\\D", "");
            if (TextUtils.isEmpty(digits)) return false;
            PixivOperate.getNovelByID(tryParseId(digits), mContext, t -> finish());
            return true;
        }
        if (!TextUtils.isEmpty(keyword)) {
            Intent intent = new Intent(mContext, SearchActivity.class);
            intent.putExtra(Params.KEY_WORD, keyword.trim());
            intent.putExtra(Params.INDEX, 0);
            startActivity(intent);
            finish();
            return true;
        }
        return false;
    }
}
