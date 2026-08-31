package ceui.lisa.activities;

import android.content.Intent;
import android.net.Uri;
import android.text.TextUtils;

import java.util.List;
import java.util.Locale;

import ceui.lisa.R;
import ceui.lisa.databinding.ActivityOutWakeBinding;
import ceui.loxia.AccountResponse;
import ceui.lisa.utils.Common;
import ceui.lisa.utils.Local;
import ceui.lisa.utils.Params;
import ceui.lisa.utils.PixivOperate;
import ceui.pixiv.login.PixivLogin;
import ceui.pixiv.login.PixivOAuthResult;
import ceui.pixiv.session.SessionManager;
import ceui.pixiv.witstudio.dialog.WitDialog;
import ceui.pixiv.witstudio.dialog.WitDialogAction;
import ceui.lisa.core.JavaAsync;
import ceui.pixiv.ui.navigation.TemplateRoute;

public class OutWakeActivity extends BaseActivity<ActivityOutWakeBinding> {

    public static final String HOST_ME = "pixiv.me";
    public static final String HOST_PIXIVISION = "pixivision.net";

    /**
     * 本实例是否已经发起了作品/小说详情请求。以前是 static、由 PixivOperate 跨类复位；
     * 改成实例字段后由本类自己在成功/失败回调里复位，不再有别的类写它。
     */
    private boolean requestInFlight = false;

    private static final String HOST_ACCOUNT = "account";
    private static final String HOST_SEARCH = "search";

    // 已经发起过 token 交换的登录 code。OAuth 授权码是单次性的,重复提交会被 Pixiv
    // 拒成「不正确的请求」(invalid_request)。static 是为了跨 Activity 重建(配置变化/
    // 回调重投递)仍能去重,避免同一个 code 交换两次。#892
    private static String sHandledLoginCode = null;
    /**
     * 进行中的 OAuth 换 token：true 表示 sHandledLoginCode 对应的请求还没回来。
     * 换 token 挂在进程级 scope 上不随本页取消；配置变更重建后的实例靠这个标记知道
     * 「结果还在路上」，登记自己为 sLoginWaiter 接管回调，而不是按 isLoggedIn() 立刻二选一
     * （那会在网络往返期间先把用户送回登录页，几百毫秒后登录态又落库——状态错乱）。
     */
    private static boolean sLoginInFlight = false;
    private static java.lang.ref.WeakReference<OutWakeActivity> sLoginWaiter = null;

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
        Uri uri = intent == null ? null : intent.getData();
        if (uri != null && routeUri(uri)) {
            return;
        }
        openDefaultDestination();
    }

    /**
     * 按历史优先级分发外部链接。返回 true 表示链接已经被消费,调用方不再执行兜底跳转。
     */
    private boolean routeUri(Uri uri) {
        String scheme = uri.getScheme();
        if (TextUtils.isEmpty(scheme)) {
            return false;
        }

        if (routePathLink(uri)) {
            return true;
        }
        if (scheme.contains("http") && routeHttpLink(uri)) {
            return true;
        }
        return (scheme.contains("pixiv") || scheme.contains("shaftintent"))
                && routeAppLink(uri);
    }

    /**
     * 处理 /artworks、/i、/novel、/n、/users 与 /u 这组路径型链接。
     * 这些规则历史上先于 scheme/host 判断,这里保持原顺序。
     */
    private boolean routePathLink(Uri uri) {
        if (uri.getPath() == null) {
            return false;
        }

        List<String> pathSegments = uri.getPathSegments();
        if (pathSegments.contains("artworks") || pathSegments.contains("i")) {
            if (requestInFlight) {
                return true;
            }
            requestInFlight = true;
            String illustId = lastPathSegment(pathSegments);
            if (!TextUtils.isEmpty(illustId)) {
                openIllust(illustId);
                return true;
            }
        }

        boolean isNovelPage = pathSegments.contains("novel")
                && !TextUtils.isEmpty(uri.getQueryParameter("id"));
        if (isNovelPage || pathSegments.contains("n")) {
            if (requestInFlight) {
                return true;
            }
            requestInFlight = true;
            String novelId = isNovelPage
                    ? uri.getQueryParameter("id")
                    : lastPathSegment(pathSegments);
            openNovel(novelId);
            return true;
        }

        if (pathSegments.contains("users") || pathSegments.contains("u")) {
            String userId = lastPathSegment(pathSegments);
            if (!TextUtils.isEmpty(userId)) {
                openUser(userId);
                return true;
            }
        }
        return false;
    }

    /** 处理 HTTP(S) 图片地址、网页特例及旧版 query 参数链接。 */
    private boolean routeHttpLink(Uri uri) {
        try {
            String uriString = uri.toString();
            String lowerCaseUri = uriString.toLowerCase(Locale.ROOT);
            if (lowerCaseUri.contains("i.pximg.net")) {
                int index = uriString.lastIndexOf("/");
                String end = uriString.substring(index + 1);
                String idString = end.split("_")[0];
                Common.showLog("end " + end + " idString " + idString);
                openIllust(idString);
                return true;
            }
            if (lowerCaseUri.contains(HOST_ME)) {
                openWebPage(uriString, HOST_ME, false);
                return true;
            }
            if (lowerCaseUri.contains(HOST_PIXIVISION)) {
                openWebPage(uriString, getString(R.string.pixiv_special), true);
                return true;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        String illustId = uri.getQueryParameter("illust_id");
        if (!TextUtils.isEmpty(illustId)) {
            openIllust(illustId);
            return true;
        }

        String userId = uri.getQueryParameter("id");
        if (!TextUtils.isEmpty(userId)) {
            openUser(userId);
            return true;
        }
        return false;
    }

    /** 处理 pixiv:// 与 shaftintent:// 内部链接。 */
    private boolean routeAppLink(Uri uri) {
        String host = uri.getHost();
        if (TextUtils.isEmpty(host)) {
            return false;
        }

        if (HOST_ACCOUNT.equals(host)) {
            handleLoginCallback(uri);
            return true;
        }
        if (host.contains("users")) {
            openUser(uri.getPath().substring(1));
            return true;
        }
        if (host.contains("illusts")) {
            openIllust(uri.getPath().substring(1));
            return true;
        }
        if (host.contains("novels")) {
            openNovel(uri.getPath().substring(1));
            return true;
        }

        // shaftintent://search?... 对外暴露的搜索入口 (#694)。
        return HOST_SEARCH.equals(host) && handleExternalSearch(uri);
    }

    private void openIllust(String illustId) {
        PixivOperate.getIllustByID(tryParseId(illustId), mContext,
                t -> { requestInFlight = false; finish(); },
                t -> requestInFlight = false);
    }

    private void openNovel(String novelId) {
        PixivOperate.getNovelByID(tryParseId(novelId), mContext,
                t -> { requestInFlight = false; finish(); },
                t -> requestInFlight = false);
    }

    private void openUser(String userId) {
        Intent intent = new Intent(mContext, UActivity.class);
        intent.putExtra(Params.USER_ID, Common.safeUserId(userId));
        startActivity(intent);
        finish();
    }

    private void openWebPage(String url, String title, boolean preferPreserve) {
        Intent intent = new Intent(mContext, TemplateActivity.class);
        intent.putExtra(Params.URL, url);
        intent.putExtra(Params.TITLE, title);
        intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, TemplateRoute.WEB_LINK.key);
        if (preferPreserve) {
            intent.putExtra(Params.PREFER_PRESERVE, true);
        }
        startActivity(intent);
        finish();
    }

    private void handleLoginCallback(Uri uri) {
        String loginCode = uri.getQueryParameter("code");
        if (loginCode != null && loginCode.equals(sHandledLoginCode)) {
            if (sLoginInFlight) {
                // 上一个实例发起的换 token 还没回来（配置变更重建）：接管回调，继续停在 loading 页。
                sLoginWaiter = new java.lang.ref.WeakReference<>(this);
                return;
            }
            if (SessionManager.INSTANCE.isLoggedIn()) {
                openMainActivity();
            } else {
                backToLoginScreen();
            }
            return;
        }

        sHandledLoginCode = loginCode;
        sLoginInFlight = true;
        sLoginWaiter = new java.lang.ref.WeakReference<>(this);
        Common.showToast(getString(R.string.trying_login));
        // 换 token 是进程级动作，不能随 Activity 取消：深色模式/语言切换等不在 configChanges 里的
        // 配置变更会重建本页，结果由 sLoginWaiter 里活着的那个实例接手跑完整的 handleLoginResult。
        JavaAsync.runDetached(() -> PixivLogin.INSTANCE.handleCallback(uri), result -> {
            OutWakeActivity host = takeLoginWaiter();
            if (host == null) {
                // 没有活着的宿主（用户已退出本页）：只把登录态落库，不导航不弹窗。
                if (result instanceof PixivOAuthResult.Success) {
                    Local.persistLoggedInUser(Shaft.sGson.fromJson(
                            ((PixivOAuthResult.Success) result).getRawBody(), AccountResponse.class));
                }
                return;
            }
            host.handleLoginResult(result);
        }, throwable -> {
            OutWakeActivity host = takeLoginWaiter();
            if (host == null) return;
            Common.showToast("登录失败");
            host.backToLoginScreen();
        });
    }

    /** 换 token 回来时结算：清 in-flight 标记，返回仍活着的等待实例（可能已换成重建后的那个）。 */
    private static OutWakeActivity takeLoginWaiter() {
        sLoginInFlight = false;
        OutWakeActivity host = sLoginWaiter != null ? sLoginWaiter.get() : null;
        sLoginWaiter = null;
        if (host == null || host.isFinishing() || host.isDestroyed()) return null;
        return host;
    }

    private void handleLoginResult(PixivOAuthResult result) {
        if (result instanceof PixivOAuthResult.Failure) {
            PixivOAuthResult.Failure failure = (PixivOAuthResult.Failure) result;
            Common.showLog("OAuth login failed: " + failure.getMessage());
            Common.showToast(loginFailureHint(failure));
            backToLoginScreen();
            return;
        }

        PixivOAuthResult.Success success = (PixivOAuthResult.Success) result;
        AccountResponse userModel = Shaft.sGson.fromJson(success.getRawBody(), AccountResponse.class);
        Common.showLog(userModel.toString());
        Common.showToast("登录成功");
        Local.persistLoggedInUser(userModel);

        ceui.pixiv.db.HistoryReporter.INSTANCE.reportSyncPref(
                userModel.getUser().getId(),
                Shaft.sSettings.isCloudHistorySync()
        );

        // 云端设置同步完成后继续原有的 R18 检查流程。
        ceui.loxia.MoonSync.syncFromCloudOnLogin(
                mActivity,
                userModel.getUser().getId(),
                () -> continueAfterMoonSync(userModel)
        );
    }

    private String loginFailureHint(PixivOAuthResult.Failure failure) {
        if (failure instanceof PixivOAuthResult.Failure.NetworkError) {
            return "登录失败：网络连接不上,请检查网络或代理后重试";
        }
        if (failure instanceof PixivOAuthResult.Failure.MissingVerifier) {
            return "登录已过期,请重新点击登录";
        }
        if (failure instanceof PixivOAuthResult.Failure.MissingCode) {
            return "登录被取消或回调异常,请重新登录";
        }
        if (failure instanceof PixivOAuthResult.Failure.ServerRejected) {
            int httpCode = ((PixivOAuthResult.Failure.ServerRejected) failure).getHttpCode();
            return "Pixiv 拒绝了登录请求(HTTP " + httpCode
                    + "),请重新点击登录(换节点无效)";
        }
        return "登录失败: " + failure.getMessage();
    }

    private void continueAfterMoonSync(AccountResponse loggedInUser) {
        // 检测是否打开 R18 并提示开启,新注册未验证邮箱用户不提示。
        if (loggedInUser.getUser().isR18Enabled()
                || !Boolean.TRUE.equals(loggedInUser.getUser().is_mail_authorized())) {
            mActivity.finish();
            Common.restart();
            return;
        }

        new WitDialog.MessageDialogBuilder(mActivity)
                .setTitle(R.string.string_216)
                .setMessage(R.string.string_400)
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
                        Intent intent = new Intent(mContext, TemplateActivity.class);
                        intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, TemplateRoute.WEB_LINK.key);
                        intent.putExtra(Params.URL, Params.URL_R18_SETTING);
                        startActivity(intent);
                    }
                })
                .create()
                .show();
    }

    private void openDefaultDestination() {
        if (SessionManager.INSTANCE.isLoggedIn()) {
            openMainActivity();
        } else {
            backToLoginScreen();
        }
    }

    private void openMainActivity() {
        startActivity(new Intent(mContext, MainActivity.class));
        finish();
    }

    /**
     * 登录回调失败后把用户送回登录注册页并结束本 Activity。少了这步会一直卡在
     * activity_out_wake 的「资源解析中」loading 页,无法返回 (#892)。
     */
    private void backToLoginScreen() {
        Intent intent = new Intent(mContext, TemplateActivity.class);
        intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, TemplateRoute.LOGIN.key);
        startActivity(intent);
        finish();
    }

    /**
     * 处理 shaftintent://search?... 外部搜索 deep link。返回 true 表示已分发,
     * 调用方应直接 return;返回 false 走默认 fallback。
     *
     * <p>支持 keyword/key 关键词,以及 illust_id、user_id、novel_id 和 key+type 简写。
     * 无 type 的纯数字 key 仍按关键词搜索,避免擅自猜测用户意图。</p>
     */
    private boolean handleExternalSearch(Uri uri) {
        String illustId = uri.getQueryParameter("illust_id");
        String userId = uri.getQueryParameter("user_id");
        String novelId = uri.getQueryParameter("novel_id");
        String keyword = uri.getQueryParameter("keyword");
        String key = uri.getQueryParameter("key");
        String type = uri.getQueryParameter("type");

        if (TextUtils.isEmpty(illustId) && TextUtils.isEmpty(userId)
                && TextUtils.isEmpty(novelId) && !TextUtils.isEmpty(key)) {
            if ("illust".equalsIgnoreCase(type)) {
                illustId = key;
            } else if ("user".equalsIgnoreCase(type)) {
                userId = key;
            } else if ("novel".equalsIgnoreCase(type)) {
                novelId = key;
            } else if (TextUtils.isEmpty(keyword)) {
                keyword = key;
            }
        }

        if (!TextUtils.isEmpty(illustId)) {
            String digits = digitsOnly(illustId);
            if (TextUtils.isEmpty(digits)) {
                return false;
            }
            openIllust(digits);
            return true;
        }
        if (!TextUtils.isEmpty(userId)) {
            String digits = digitsOnly(userId);
            if (TextUtils.isEmpty(digits)) {
                return false;
            }
            openUser(digits);
            return true;
        }
        if (!TextUtils.isEmpty(novelId)) {
            String digits = digitsOnly(novelId);
            if (TextUtils.isEmpty(digits)) {
                return false;
            }
            openNovel(digits);
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

    private String lastPathSegment(List<String> pathSegments) {
        return pathSegments.get(pathSegments.size() - 1);
    }

    private String digitsOnly(String value) {
        return value.replaceAll("\\D", "");
    }
}
