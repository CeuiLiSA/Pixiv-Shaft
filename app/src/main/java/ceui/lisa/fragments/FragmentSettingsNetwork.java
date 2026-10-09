package ceui.lisa.fragments;

import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.text.InputType;
import android.text.TextUtils;
import android.transition.AutoTransition;
import android.transition.TransitionManager;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.appcompat.widget.SwitchCompat;

import ceui.pixiv.witstudio.dialog.WitDialog;
import ceui.pixiv.witstudio.dialog.WitDialogAction;
import ceui.pixiv.witstudio.dialog.WitDialogView;
import ceui.pixiv.witstudio.theme.V3Palette;

import ceui.lisa.R;
import ceui.lisa.activities.Shaft;
import ceui.lisa.activities.TemplateActivity;
import ceui.lisa.databinding.FragmentSettingsNetworkBinding;
import ceui.lisa.http.AppApiProxyInterceptor;
import ceui.lisa.http.GithubProxy;
import ceui.lisa.http.HttpDns;
import ceui.lisa.http.ImageReadTimeout;
import ceui.lisa.utils.Common;
import ceui.lisa.utils.Local;
import ceui.lisa.utils.Params;
import ceui.pixiv.api.Client;
import ceui.pixiv.ui.navigation.TemplateRoute;
import ceui.pixiv.ui.settings.GithubProxyDialog;

/** 设置 · 网络 */
public class FragmentSettingsNetwork extends SettingsPageFragment<FragmentSettingsNetworkBinding> {

    @Override
    public void initLayout() {
        mLayoutID = R.layout.fragment_settings_network;
    }

    @Override
    protected void initData() {
        baseBind.autoDns.setChecked(Shaft.sSettings.isDirectConnect());
        // DoH 只在直连开启时生效，跟随直连开关显隐
        baseBind.useSecureDnsGroup.setVisibility(
                Shaft.sSettings.isDirectConnect() ? View.VISIBLE : View.GONE);
        baseBind.autoDns.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                boolean changed = isChecked != Shaft.sSettings.isDirectConnect();
                Shaft.sSettings.setDirectConnect(isChecked);
                if (changed) {
                    // 读超时分直连 / 非直连两种量程，切模式后用户此前调的值跨模式不再适用，
                    // 重置为新模式的默认值（= 该模式上界）。必须在写盘前重置。
                    Shaft.sSettings.resetImageReadTimeout();
                }
                Common.showToast(getString(R.string.string_428), 2);
                Local.setSettings(Shaft.sSettings);
                ViewGroup secureDnsParent = (ViewGroup) baseBind.useSecureDnsGroup.getParent();
                if (secureDnsParent != null) {
                    TransitionManager.beginDelayedTransition(secureDnsParent, new AutoTransition());
                }
                baseBind.useSecureDnsGroup.setVisibility(isChecked ? View.VISIBLE : View.GONE);
                if (changed) {
                    refreshReadTimeoutSummary();
                    // issue #956: 网页 ajax 客户端（Client.webApi）也带直连拦截器，
                    // reset() 里会一并重建，否则「按 tag 筛画师作品」要重启 App 才吃到直连。
                    Client.INSTANCE.reset();
                }
            }
        });
        baseBind.directConnectLink.setOnClickListener(v -> {
            Intent intent = new Intent(mContext, TemplateActivity.class);
            intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, TemplateRoute.WEB_LINK.key);
            intent.putExtra(Params.URL, "https://github.com/Notsfsssf/Pix-EzViewer");
            intent.putExtra(Params.TITLE, "PxEz项目主页");
            startActivity(intent);
        });
        baseBind.directConnectRela.setOnClickListener(v -> baseBind.autoDns.performClick());

        //安全 DNS（DoH） issue #616
        baseBind.useSecureDns.setChecked(Shaft.sSettings.isUseSecureDns());
        baseBind.useSecureDns.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                Shaft.sSettings.setUseSecureDns(isChecked);
                Common.showToast(getString(R.string.string_428), 2);
                Local.setSettings(Shaft.sSettings);
                HttpDns.invalidate();
            }
        });
        baseBind.useSecureDnsRela.setOnClickListener(v -> baseBind.useSecureDns.performClick());

        //图片读超时：图片加载 + 下载共用的读超时，分直连 / 非直连两种量程（默认 = 该模式上界）。
        refreshReadTimeoutSummary();
        baseBind.readTimeoutRela.setOnClickListener(v -> showReadTimeoutDialog());

        //图片加速代理（issue #865）：Pixiv 官方 / pixiv.cat / 自定义反代
        refreshImageHostSummary();
        baseBind.imageHostRela.setOnClickListener(v -> showImageHostPicker());

        //App API 代理（PxveAPI 风格）：独立输入选项，与直连共存。
        //地址非空即启用（Settings#isUseAppApiProxy 由地址派生），为空显示「不代理」。
        refreshAppApiProxySummary();
        baseBind.appApiProxyRela.setOnClickListener(v -> promptAppApiProxy());

        //GitHub 加速地址（gh-proxy 风格）：与 PxveAPI 代理同组的分段末行，空 = 不使用。
        //与 PxveAPI 代理不同，这里改完**立刻生效**：前缀是在每次请求前读设置拼的
        //（GithubProxy.currentPrefix），没有需要重建的客户端，也不用重启。
        refreshGithubProxySummary();
        baseBind.githubProxyRela.setOnClickListener(v ->
                GithubProxyDialog.show(mContext, this::refreshGithubProxySummary));

        //缩略图是否显示大图
        baseBind.showLargeThumbnailImage.setChecked(Shaft.sSettings.isShowLargeThumbnailImage());
        baseBind.showLargeThumbnailImage.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                Shaft.sSettings.setShowLargeThumbnailImage(isChecked);
                Common.showToast(getString(R.string.string_428));
                Local.setSettings(Shaft.sSettings);
            }
        });
        baseBind.showLargeThumbnailImageRela.setOnClickListener(v ->
                baseBind.showLargeThumbnailImage.performClick());

        //详情是否显示原图
        baseBind.showOriginalPreviewImage.setChecked(Shaft.sSettings.isShowOriginalPreviewImage());
        baseBind.showOriginalPreviewImage.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                Shaft.sSettings.setShowOriginalPreviewImage(isChecked);
                Common.showToast(getString(R.string.string_428));
                Local.setSettings(Shaft.sSettings);
            }
        });
        baseBind.showOriginalPreviewImageRela.setOnClickListener(v ->
                baseBind.showOriginalPreviewImage.performClick());
    }

    // ── 图片加速代理（issue #865） ──────────────────────────────────────
    // 三档：0=Pixiv 官方 / 1=pixiv.cat / 2=自定义反代。只写 Settings，下次启动经
    // Shaft.onCreate 的 ImageHostManager.hydrate 生效（图片 OkHttpClient 启动时一次性
    // 构建、被 Glide 持有，与直连开关同款限制），故切换后提示重启。

    // 选项顺序 == ImageHostManager.Mode 的 ordinal == Settings.imageHostMode。
    private static final int IMAGE_HOST_MODE_CUSTOM =
            ceui.lisa.http.ImageHostManager.Mode.CUSTOM.ordinal();

    private void refreshImageHostSummary() {
        int mode = Shaft.sSettings.getImageHostMode();
        String summary;
        if (mode == ceui.lisa.http.ImageHostManager.Mode.PIXIV_CAT.ordinal()) {
            summary = getString(R.string.image_host_pixiv_cat);
        } else if (mode == ceui.lisa.http.ImageHostManager.Mode.PIXIV_RE.ordinal()) {
            summary = getString(R.string.image_host_pixiv_re);
        } else if (mode == ceui.lisa.http.ImageHostManager.Mode.PIXIV_NL.ordinal()) {
            summary = getString(R.string.image_host_pixiv_nl);
        } else if (mode == IMAGE_HOST_MODE_CUSTOM) {
            String host = Shaft.sSettings.getCustomImageHost();
            summary = TextUtils.isEmpty(host) ? getString(R.string.image_host_custom) : host;
        } else {
            summary = getString(R.string.image_host_pixiv_official);
        }
        baseBind.imageHostValue.setText(summary);
    }

    private void showImageHostPicker() {
        // 顺序必须与 ImageHostManager.Mode 的 ordinal 一致（index == mode 值）。
        String[] items = {
                getString(R.string.image_host_pixiv_official),
                getString(R.string.image_host_pixiv_cat),
                getString(R.string.image_host_pixiv_re),
                getString(R.string.image_host_pixiv_nl),
                getString(R.string.image_host_custom),
        };
        int current = Shaft.sSettings.getImageHostMode();
        if (current < 0 || current >= items.length) {
            current = 0;
        }
        new WitDialog.CheckableDialogBuilder(mContext)
                .setCheckedIndex(current)
                .addItems(items, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        dialog.dismiss();
                        if (which == IMAGE_HOST_MODE_CUSTOM) {
                            promptCustomImageHost();
                        } else {
                            applyImageHostMode(which);
                        }
                    }
                })
                .create()
                .show();
    }

    private void promptCustomImageHost() {
        final WitDialog.EditTextDialogBuilder builder = new WitDialog.EditTextDialogBuilder(mContext);
        builder.setTitle(R.string.image_host_custom)
                .setPlaceholder(getString(R.string.image_host_custom_hint))
                .setDefaultText(Shaft.sSettings.getCustomImageHost())
                .setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI)
                .addAction(getString(R.string.string_142), (dialog, index) -> dialog.dismiss())
                .addAction(getString(R.string.sure), (dialog, index) -> {
                    CharSequence text = builder.getEditText().getText();
                    String host = text == null ? "" : text.toString().trim();
                    if (TextUtils.isEmpty(host)) {
                        Common.showToast(getString(R.string.image_host_custom_empty));
                        return;
                    }
                    Shaft.sSettings.setCustomImageHost(host);
                    applyImageHostMode(IMAGE_HOST_MODE_CUSTOM);
                    dialog.dismiss();
                })
                .create()
                .show();
    }

    private void applyImageHostMode(int mode) {
        Shaft.sSettings.setImageHostMode(mode);
        Local.setSettings(Shaft.sSettings);
        refreshImageHostSummary();
        Common.showToast(getString(R.string.image_host_restart_hint), 2);
    }

    // ── 图片加载/下载断流阈值 ──────────────────────────────────────────────────────
    // 图片加载（Glide）与下载（Manager 派生）共用同一条 OkHttp client，读超时设在
    // Shaft.buildOkHttpClient() 上；设置页只写值，下次 client 构建（重启 App）才生效。
    // 量程分直连 / 非直连两种模式，默认 = 该模式上界，只能调小（更早触发「断流立即重连」）。

    private void refreshReadTimeoutSummary() {
        baseBind.readTimeoutValue.setText(readTimeoutValueLabel(
                mContext,
                Shaft.sSettings.getImageReadTimeoutSeconds(),
                Shaft.sSettings.isDirectConnect()));
    }

    /**
     * 「图片加载/下载断流阈值」入口：WitDialog 承载一条可拖动滑条（量程分模式 —— 非直连 1–10s、
     * 直连 1–30s，默认值 = 该模式上界），滑条下面还有一个开关「图片加载也容许一次断流超时并静默重试」。
     * 确认后只写设置：读超时要等下次共享 OkHttp client 构建（重启 App）才生效；开关**立即生效**。
     */
    private void showReadTimeoutDialog() {
        ReadTimeoutDialogBuilder builder = new ReadTimeoutDialogBuilder(mActivity);
        builder.setTitle(R.string.setting_read_timeout_title);
        builder.addAction(R.string.string_cancel, (dialog, which) -> dialog.dismiss());
        builder.addAction(0, R.string.sure, WitDialogAction.ACTION_PROP_POSITIVE, (dialog, which) -> {
            // 两类改动分开处理：滑条（读超时）要重启才生效；开关（图片加载静默重试）的读值在每次
            // 失败判定时现取，立即生效。提示也分开：改了滑条提示「重启后生效」，**只**改了开关提示
            // 「设置成功」—— 否则只拨一下开关再点确定会完全没有反馈。
            boolean changed = false;
            boolean restartNeeded = false;
            SeekBar slider = builder.slider;
            if (slider != null) {
                boolean direct = Shaft.sSettings.isDirectConnect();
                int chosen = builder.sliderTouched
                        ? ImageReadTimeout.secondsForProgress(slider.getProgress(), direct, builder.steps)
                        : builder.initialSeconds;
                if (chosen != Shaft.sSettings.getImageReadTimeoutSeconds()) {
                    Shaft.sSettings.setImageReadTimeoutSeconds(chosen);
                    changed = true;
                    restartNeeded = true;
                }
            }
            boolean switchChanged = false;
            boolean stallRetry = builder.stallRetrySwitch != null
                    && builder.stallRetrySwitch.isChecked();
            if (stallRetry != Shaft.sSettings.isImageLoadRetryOnStall()) {
                Shaft.sSettings.setImageLoadRetryOnStall(stallRetry);
                changed = true;
                switchChanged = true;
            }
            if (changed) {
                Local.setSettings(Shaft.sSettings);
                refreshReadTimeoutSummary();
            }
            if (restartNeeded) {
                // 滑条改了要重启才生效。这时不再补一条「设置成功」——「重启后生效」本身已经含
                // 「已保存」，两条 toast 排队弹反而吵。
                Common.showToast(getString(R.string.please_restart_app), 2);
            } else if (switchChanged) {
                Common.showToast(getString(R.string.string_428), 2);
            }
            dialog.dismiss();
        });
        builder.show();
    }

    /** WitDialog 的自定义内容：标题下的大数值 + V3 滑条 + 两端说明。结构对齐图片缓存上限弹窗。 */
    private static final class ReadTimeoutDialogBuilder extends WitDialog.CustomDialogBuilder {

        private SeekBar slider;
        private SwitchCompat stallRetrySwitch;
        private TextView valueText;
        private int initialSeconds;
        private int steps;
        private boolean sliderTouched;

        private ReadTimeoutDialogBuilder(Context context) {
            super(context);
        }

        @Override
        protected View onCreateContent(WitDialog dialog, WitDialogView parent, Context context) {
            View content = LayoutInflater.from(context)
                    .inflate(R.layout.dialog_read_timeout, parent, false);
            slider = content.findViewById(R.id.dialog_read_timeout_slider);
            valueText = content.findViewById(R.id.dialog_read_timeout_value);
            valueText.setTextColor(V3Palette.from(context).getTextAccent());

            final boolean direct = Shaft.sSettings.isDirectConnect();
            int current = Shaft.sSettings.getImageReadTimeoutSeconds();
            initialSeconds = current;
            steps = ImageReadTimeout.sliderSteps(direct);
            slider.setMax(steps);
            slider.setProgress(ImageReadTimeout.progressForSeconds(current, direct, steps));
            valueText.setText(readTimeoutValueLabel(context, current, direct));
            ((TextView) content.findViewById(R.id.dialog_read_timeout_min))
                    .setText(readTimeoutSecondsLabel(context, ImageReadTimeout.MIN_SECONDS));
            ((TextView) content.findViewById(R.id.dialog_read_timeout_max))
                    .setText(readTimeoutSecondsLabel(context, ImageReadTimeout.maxSeconds(direct)));

            stallRetrySwitch = content.findViewById(R.id.dialog_read_timeout_stall_retry);
            stallRetrySwitch.setChecked(Shaft.sSettings.isImageLoadRetryOnStall());

            slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    if (fromUser) {
                        sliderTouched = true;
                    }
                    valueText.setText(readTimeoutValueLabel(context,
                            ImageReadTimeout.secondsForProgress(progress, direct, steps), direct));
                }

                @Override
                public void onStartTrackingTouch(SeekBar seekBar) {
                }

                @Override
                public void onStopTrackingTouch(SeekBar seekBar) {
                }
            });
            return content;
        }
    }

    /** 秒数的纯数值文案（滑条两端说明用）。 */
    private static String readTimeoutSecondsLabel(Context context, int seconds) {
        return context.getString(R.string.setting_read_timeout_seconds, seconds);
    }

    /**
     * 设置行 / 弹窗大数值的文案：等于当前模式默认值（= 该模式滑条上界）时显示「默认（N秒）」，
     * 否则纯数值。用户只能往下调，所以「停在默认」是有意义的稳定状态，明说「默认」比只给数字更
     * 不容易让人误以为还没配好。
     */
    private static String readTimeoutValueLabel(Context context, int seconds, boolean direct) {
        if (seconds == ImageReadTimeout.defaultSeconds(direct)) {
            return context.getString(R.string.setting_read_timeout_default_value, seconds);
        }
        return context.getString(R.string.setting_read_timeout_seconds, seconds);
    }

    // ── App API 代理（PxveAPI 风格） ────────────────────────────────────
    // 独立输入选项：地址非空即启用（与直连共存，互不干扰），空 = 不代理。
    // 装配点（Retro.buildRetrofit / ClientManager.createAPPAPI / PixivLogin.buildClient）
    // 在**构建时**按 Settings 注入拦截器，所以地址变化后必须重建客户端才能
    // 挂载/卸载 AppApiProxyInterceptor（与直连开关同款限制）。

    private void refreshAppApiProxySummary() {
        String proxy = Shaft.sSettings.getAppApiProxy();
        baseBind.appApiProxyValue.setText(
                TextUtils.isEmpty(proxy) ? getString(R.string.app_api_proxy_empty) : proxy);
    }

    private void promptAppApiProxy() {
        // 帮助按钮在弹窗标题栏右上角：点击「使用 PxveAPI 代理」弹出输入框，
        // 标题栏右侧提供帮助图标，点击后展示填写规范 + 安全警示。
        // 图标装配（40dp 热区 / 24dp 栏距对齐）由 WitDialogBuilder.addTitleAction 提供，
        // 与 github 加速地址那边的「网络测试」图标共用同一条实现。
        final WitDialog.EditTextDialogBuilder builder = new WitDialog.EditTextDialogBuilder(mContext);
        builder.setTitle(R.string.app_api_proxy_title)
                .addTitleAction(R.drawable.ic_help_outline_black_24dp,
                        getString(R.string.app_api_proxy_help_desc),
                        v -> showAppApiProxyHelp())
                .setPlaceholder(getString(R.string.app_api_proxy_hint))
                .setDefaultText(Shaft.sSettings.getAppApiProxy())
                .setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI)
                .addAction(getString(R.string.string_142), (dialog, index) -> dialog.dismiss())
                .addAction(getString(R.string.sure), (dialog, index) -> {
                    CharSequence text = builder.getEditText().getText();
                    String proxy = text == null ? "" : text.toString().trim();
                    // 校验交给拦截器的 normalizeBase（唯一事实源）：它接受裸域名并自动补 https，
                    // 只拒绝非法 scheme（debug 下显式 http:// 是放行的）、带 query/fragment、以及解析失败。
                    // 这里不再自己判 startsWith("https://")——那比 normalizeBase 严格，
                    // 会把合法的裸域名（pxve.example.com）误拦掉；也不按 buildType 再开第二个口子，
                    // 否则 debug 下填了真正非法的地址会被静默存下，用户以为代理生效了其实全程直连。
                    if (!TextUtils.isEmpty(proxy)
                            && AppApiProxyInterceptor.normalizeBase(proxy) == null) {
                        Common.showToast(getString(R.string.app_api_proxy_https_required), 2);
                        return;
                    }
                    boolean changed = !TextUtils.equals(proxy, Shaft.sSettings.getAppApiProxy());
                    Shaft.sSettings.setAppApiProxy(proxy);
                    Local.setSettings(Shaft.sSettings);
                    refreshAppApiProxySummary();
                    if (changed) {
                        // 挂载/卸载 AppApiProxyInterceptor 需要重建客户端（与直连开关同款）。
                        // 网页 ajax 走 www.pixiv.net 不经这个代理，但 Client.reset() 顺带重建无害。
                        Client.INSTANCE.reset();
                        // PixivLogin.client 是 by lazy 单例，这里重建不了 —— 本次会话的
                        // token 自动刷新仍走旧客户端（直连 oauth）。提示用户重启才完全生效。
                        Common.showToast(getString(R.string.image_host_restart_hint), 2);
                    }
                    dialog.dismiss();
                })
                .create()
                .show();
    }

    private void showAppApiProxyHelp() {
        new WitDialog.MessageDialogBuilder(mContext)
                .setTitle(R.string.app_api_proxy_title)
                .setMessage(getString(R.string.app_api_proxy_tip) + "\n\n" +
                        getString(R.string.app_api_proxy_warning))
                .addAction(R.string.sure, (dialog, index) -> dialog.dismiss())
                .show();
    }

    // ── GitHub 加速地址（gh-proxy 风格） ───────────────────────────────
    // 使用方式就是在 https://*.github.com 的 https:// 之前插入加速地址，插入点全部收在
    // ceui.lisa.http.GithubProxy 里（「不使用」时原样返回）。这里只负责显示当前选择。

    private void refreshGithubProxySummary() {
        String prefix = GithubProxy.currentPrefix();
        baseBind.githubProxyValue.setText(
                TextUtils.isEmpty(prefix)
                        ? getString(R.string.github_proxy_none)
                        : GithubProxy.displayName(prefix));
    }
}
