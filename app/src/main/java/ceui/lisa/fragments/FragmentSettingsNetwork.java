package ceui.lisa.fragments;

import android.content.DialogInterface;
import android.content.Intent;
import android.text.InputType;
import android.text.TextUtils;
import android.transition.AutoTransition;
import android.transition.TransitionManager;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;

import com.qmuiteam.qmui.skin.QMUISkinManager;
import com.qmuiteam.qmui.widget.dialog.QMUIDialog;

import ceui.lisa.R;
import ceui.lisa.activities.Shaft;
import ceui.lisa.activities.TemplateActivity;
import ceui.lisa.databinding.FragmentSettingsNetworkBinding;
import ceui.lisa.http.HttpDns;
import ceui.lisa.http.Retro;
import ceui.lisa.utils.Common;
import ceui.lisa.utils.Local;
import ceui.lisa.utils.Params;
import ceui.loxia.Client;

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
                Common.showToast(getString(R.string.string_428), 2);
                Local.setSettings(Shaft.sSettings);
                ViewGroup secureDnsParent = (ViewGroup) baseBind.useSecureDnsGroup.getParent();
                if (secureDnsParent != null) {
                    TransitionManager.beginDelayedTransition(secureDnsParent, new AutoTransition());
                }
                baseBind.useSecureDnsGroup.setVisibility(isChecked ? View.VISIBLE : View.GONE);
                if (changed) {
                    Retro.refreshAppApi();
                    // issue #956: 网页 ajax 的 Rx 客户端也带直连拦截器，漏掉它的话
                    // 「按 tag 筛画师作品」要重启 App 才吃到直连。
                    Retro.resetWebApi();
                    Client.INSTANCE.reset();
                }
            }
        });
        baseBind.directConnectLink.setOnClickListener(v -> {
            Intent intent = new Intent(mContext, TemplateActivity.class);
            intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "网页链接");
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

        //图片加速代理（issue #865）：Pixiv 官方 / pixiv.cat / 自定义反代
        refreshImageHostSummary();
        baseBind.imageHostRela.setOnClickListener(v -> showImageHostPicker());

        //App API 代理（PxveAPI 风格）：开关 + 代理地址输入框，输入框跟随开关显隐
        baseBind.appApiProxySwitch.setChecked(Shaft.sSettings.isUseAppApiProxy());
        baseBind.appApiProxyEdit.setText(Shaft.sSettings.getAppApiProxy());
        baseBind.appApiProxyGroup.setVisibility(
                Shaft.sSettings.isUseAppApiProxy() ? View.VISIBLE : View.GONE);
        baseBind.appApiProxySwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                boolean changed = isChecked != Shaft.sSettings.isUseAppApiProxy();
                Shaft.sSettings.setUseAppApiProxy(isChecked);
                Common.showToast(getString(R.string.string_428), 2);
                Local.setSettings(Shaft.sSettings);
                ViewGroup proxyParent = (ViewGroup) baseBind.appApiProxyGroup.getParent();
                if (proxyParent != null) {
                    TransitionManager.beginDelayedTransition(proxyParent, new AutoTransition());
                }
                baseBind.appApiProxyGroup.setVisibility(isChecked ? View.VISIBLE : View.GONE);
                if (changed) {
                    // 挂载/卸载 AppApiProxyInterceptor 需要重建客户端（与直连开关同款）
                    Retro.refreshAppApi();
                    Retro.resetWebApi();
                    Client.INSTANCE.reset();
                }
            }
        });
        baseBind.appApiProxyRela.setOnClickListener(v -> baseBind.appApiProxySwitch.performClick());
        // 保存按钮：校验后写入 Settings（参照 aria2/AI 翻译设置页的显式保存交互）
        baseBind.appApiProxySaveBtn.setOnClickListener(v -> saveAppApiProxy());

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
        new QMUIDialog.CheckableDialogBuilder(mContext)
                .setCheckedIndex(current)
                .setSkinManager(QMUISkinManager.defaultInstance(mContext))
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
        final QMUIDialog.EditTextDialogBuilder builder = new QMUIDialog.EditTextDialogBuilder(mContext);
        builder.setTitle(R.string.image_host_custom)
                .setSkinManager(QMUISkinManager.defaultInstance(mContext))
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

    // ── App API 代理（PxveAPI 风格） ─────────────────────────────────────
    // 填写规范与 pixiv-viewer 一致：代理地址必须带 https:// 前缀（拦截器按
    // PxveAPI 约定自动拼接 /pixiv-app-api 与 /pixiv-oauth 路径，无需手动填写）。
    private void saveAppApiProxy() {
        CharSequence text = baseBind.appApiProxyEdit.getText();
        String proxy = text == null ? "" : text.toString().trim();
        if (TextUtils.isEmpty(proxy)) {
            Common.showToast(getString(R.string.app_api_proxy_empty));
            return;
        }
        if (!proxy.startsWith("https://")) {
            Common.showToast(getString(R.string.app_api_proxy_invalid));
            return;
        }
        Shaft.sSettings.setAppApiProxy(proxy);
        Local.setSettings(Shaft.sSettings);
        Common.showToast(getString(R.string.string_428), 2);
        // 拦截器每次请求实时读取 Settings，地址变化即时生效
    }
}
