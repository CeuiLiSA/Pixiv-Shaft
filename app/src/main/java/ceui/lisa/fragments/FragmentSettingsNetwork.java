package ceui.lisa.fragments;

import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.text.InputType;
import android.text.TextUtils;
import android.transition.AutoTransition;
import android.transition.TransitionManager;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.FrameLayout;
import android.widget.ImageView;

import androidx.annotation.NonNull;

import ceui.pixiv.witstudio.dialog.WitDialog;
import ceui.pixiv.witstudio.dialog.WitDialogView;

import ceui.lisa.R;
import ceui.lisa.activities.Shaft;
import ceui.lisa.activities.TemplateActivity;
import ceui.lisa.databinding.FragmentSettingsNetworkBinding;
import ceui.lisa.http.AppApiProxyInterceptor;
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

        //App API 代理（PxveAPI 风格）：独立输入选项，与直连共存。
        //地址非空即启用（Settings#isUseAppApiProxy 由地址派生），为空显示「不代理」。
        refreshAppApiProxySummary();
        baseBind.appApiProxyRela.setOnClickListener(v -> promptAppApiProxy());

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
        // 帮助按钮移到弹窗标题栏右上角：点击「使用 PxveAPI 代理」弹出输入框，
        // 标题栏右侧提供帮助图标，点击后展示填写规范 + 安全警示。
        final WitDialog.EditTextDialogBuilder builder = new WitDialog.EditTextDialogBuilder(mContext) {
            @Override
            protected View onCreateTitle(@NonNull WitDialog dialog,
                                         @NonNull WitDialogView parent,
                                         @NonNull Context context) {
                View title = super.onCreateTitle(dialog, parent, context);
                if (title == null) {
                    return null;
                }
                float density = context.getResources().getDisplayMetrics().density;
                int helpSize = Math.round(40 * density);
                int helpPadding = Math.round(8 * density);

                FrameLayout container = new FrameLayout(context);

                // super 返回的标题 view 自带 24dp 左右 + 24dp 顶部内边距。直接塞进 FrameLayout
                // 再让图标 CENTER_VERTICAL，居中的就是「文字 + 24dp 顶部内边距」这个盒子，
                // 图标会比标题的视觉中心高出 12dp；同时 paddingEnd 只作用于标题自己，
                // 图标会一路贴到卡片右缘。所以把纵向和右侧内边距上移到容器：
                // 容器的内容区正好剩下文字本身，居中才对得上。
                int titleTop = title.getPaddingTop();
                int titleEnd = title.getPaddingEnd();
                title.setPadding(title.getPaddingStart(), 0, 0, title.getPaddingBottom());
                // 右内边距扣掉图标自身的 8dp 内衬，让 24dp 图形的右缘落在跟标题左缘
                // 同一条 24dp 栏距上（对齐的是图形，不是 40dp 的点击热区）。
                container.setPadding(0, titleTop, Math.max(0, titleEnd - helpPadding), 0);

                FrameLayout.LayoutParams titleLp = new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
                titleLp.gravity = Gravity.START | Gravity.CENTER_VERTICAL;
                container.addView(title, titleLp);

                ImageView help = new ImageView(context);
                help.setImageResource(R.drawable.ic_help_outline_black_24dp);
                help.setColorFilter(context.getColor(R.color.v3_text_2));
                help.setContentDescription(getString(R.string.app_api_proxy_help_desc));
                help.setPadding(helpPadding, helpPadding, helpPadding, helpPadding);
                help.setOnClickListener(v -> showAppApiProxyHelp());
                FrameLayout.LayoutParams helpLp = new FrameLayout.LayoutParams(helpSize, helpSize);
                helpLp.gravity = Gravity.END | Gravity.CENTER_VERTICAL;
                container.addView(help, helpLp);

                return container;
            }
        };
        builder.setTitle(R.string.app_api_proxy_title)
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
                        // resetWebApi 不需要：网页 ajax 走 www.pixiv.net，不经这个代理。
                        Retro.refreshAppApi();
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
}
