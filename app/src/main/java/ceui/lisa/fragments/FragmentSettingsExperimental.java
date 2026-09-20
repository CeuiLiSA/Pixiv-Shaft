package ceui.lisa.fragments;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.SeekBar;
import android.widget.TextView;

import java.text.DecimalFormat;

import com.google.firebase.analytics.FirebaseAnalytics;

import ceui.lisa.R;
import ceui.lisa.activities.Shaft;
import ceui.lisa.databinding.FragmentSettingsExperimentalBinding;
import ceui.lisa.utils.Common;
import ceui.lisa.utils.Local;
import ceui.pixiv.debug.TimberFileLog;
import ceui.pixiv.snapshot.AutoSnapshotEngine;
import ceui.pixiv.snapshot.AutoSnapshotQuota;
import ceui.pixiv.witstudio.dialog.WitDialog;
import ceui.pixiv.witstudio.dialog.WitDialogAction;
import ceui.pixiv.witstudio.dialog.WitDialogView;
import ceui.pixiv.witstudio.theme.V3Palette;

/** 设置 · 试验性 */
public class FragmentSettingsExperimental extends SettingsPageFragment<FragmentSettingsExperimentalBinding> {

    @Override
    public void initLayout() {
        mLayoutID = R.layout.fragment_settings_experimental;
    }

    @Override
    protected void initData() {
        bindWitGalleryRows();
        bindLogFileRow();
        bindTriggerCrashRow();
        bindDebugMirrorBannerRow();

        // 自动快照是本地离线能力，不涉及站外 UGC，所有渠道都显示。
        baseBind.autoSnapshotOnBookmark.setChecked(Shaft.sSettings.isAutoSnapshotOnBookmark());
        baseBind.autoSnapshotOnBookmark.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                Shaft.sSettings.setAutoSnapshotOnBookmark(isChecked);
                Local.setSettings(Shaft.sSettings);
                Common.showToast(getString(R.string.string_428));
            }
        });
        baseBind.autoSnapshotOnBookmarkRela.setOnClickListener(v ->
                baseBind.autoSnapshotOnBookmark.performClick());

        bindAutoSnapshotQuotaRow();

        // 插画/漫画自动生成快照：只记录并生成本地行为信号，不涉及站外 UGC。
        baseBind.autoSnapshotOnIllustManga.setChecked(Shaft.sSettings.isAutoSnapshotOnIllustManga());
        baseBind.autoSnapshotOnIllustManga.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                Shaft.sSettings.setAutoSnapshotOnIllustManga(isChecked);
                Local.setSettings(Shaft.sSettings);
                Common.showToast(getString(R.string.string_428));
            }
        });
        baseBind.autoSnapshotOnIllustMangaRela.setOnClickListener(v ->
                baseBind.autoSnapshotOnIllustManga.performClick());

        // google(Play)渠道不展示公开聊天室横幅开关，与 SettingsCatalog 索引一致。
        if (ceui.lisa.BuildConfig.IS_LITE) {
            baseBind.showChatRoomPushBannerRela.setVisibility(View.GONE);
            // 上面整组消失后 Firebase 成了页内唯一一行,它原本用来跟前一组拉开的
            // 上外边距就成了页首一块空白,去掉。改完必须 setLayoutParams 回写:直接改 lp 字段
            // 只是碰巧因为 initData() 跑在首次 layout 之前才生效,靠的是时序不是契约;
            // setLayoutParams 内部会 requestLayout,任何时机调用都对。
            ViewGroup.MarginLayoutParams lp =
                    (ViewGroup.MarginLayoutParams) baseBind.isFirebaseEnableRela.getLayoutParams();
            lp.topMargin = 0;
            baseBind.isFirebaseEnableRela.setLayoutParams(lp);
            bindFirebaseRow();
            return;
        }

        baseBind.showChatRoomPushBanner.setChecked(Shaft.sSettings.isShowChatRoomPushBanner());

        baseBind.showChatRoomPushBanner.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                Shaft.sSettings.setShowChatRoomPushBanner(isChecked);
                Common.showToast(getString(R.string.string_428));
                Local.setSettings(Shaft.sSettings);
            }
        });
        baseBind.showChatRoomPushBannerRela.setOnClickListener(v ->
                baseBind.showChatRoomPushBanner.performClick());

        bindFirebaseRow();
    }

    /**
     * 弹窗画廊入口。一屏之内覆盖 7 种 builder 形态,是日夜 × 主题档截图验收的载体,
     * 只在 debug 包出现;phase 7 收尾时连同这一行、布局里的 RelativeLayout
     * 和 WitDialogGallery.kt 一并删除。不进 SettingsCatalog 索引——它不是用户设置。
     */
    private void bindWitGalleryRows() {
        if (!ceui.lisa.BuildConfig.DEBUG) {
            baseBind.witGalleryRela.setVisibility(View.GONE);
            return;
        }
        baseBind.witGalleryRela.setOnClickListener(v ->
                ceui.pixiv.ui.settings.WitDialogGallery.showWit(mContext));
    }

    private void bindLogFileRow() {
        baseBind.logFileEnable.setChecked(Shaft.sSettings.isLogFileEnabled());
        String folder = TimberFileLog.INSTANCE.currentFolderPath();
        baseBind.logFilePath.setText(getString(
                R.string.setting_log_file_desc,
                folder != null ? folder : getString(R.string.setting_log_file_no_file)));

        baseBind.logFileShare.setOnClickListener(v ->
                TimberFileLog.INSTANCE.shareLogFile(mContext));

        baseBind.logFileEnable.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                Shaft.sSettings.setLogFileEnabled(isChecked);
                Local.setSettings(Shaft.sSettings);
                Common.showToast(getString(R.string.please_restart_app), 2);
            }
        });
        baseBind.logFileEnableRela.setOnClickListener(v ->
                baseBind.logFileEnable.performClick());
    }

    private void bindTriggerCrashRow() {
        if (!ceui.lisa.BuildConfig.DEBUG) {
            baseBind.triggerCrashRela.setVisibility(View.GONE);
            return;
        }
        baseBind.triggerCrashRela.setOnClickListener(v -> crashDeep());
    }

    /**
     * 无限递归触发 StackOverflowError：Error 而非 Exception，catch(Exception) 抓不到，且必走未捕获异常处理器。
     */
    private void crashDeep() {
        crashDeep();
    }

    /**
     * 【临时·调试】手动弹一次「收藏库已就绪」引导 banner，用来验它的「去看看」按钮
     * （正常路径要等整份回填跑完、且一辈子只弹一次）。
     * 只在 debug 包出现；验完连同布局里那一行和 DebugMirrorBannerTrigger.kt 一起删。
     */
    private void bindDebugMirrorBannerRow() {
        if (!ceui.lisa.BuildConfig.DEBUG) {
            baseBind.debugMirrorBannerRela.setVisibility(View.GONE);
            return;
        }
        baseBind.debugMirrorBannerRela.setOnClickListener(v ->
                ceui.pixiv.ui.debug.DebugMirrorBannerTrigger.show(mContext));
    }

    private void bindFirebaseRow() {
        baseBind.isFirebaseEnable.setChecked(Shaft.sSettings.isFirebaseEnable());
        baseBind.isFirebaseEnable.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                Shaft.sSettings.setFirebaseEnable(isChecked);
                Local.setSettings(Shaft.sSettings);
                Common.showToast(getString(R.string.string_428), 2);
                FirebaseAnalytics.getInstance(mContext).setAnalyticsCollectionEnabled(isChecked);
            }
        });
        baseBind.isFirebaseEnableRela.setOnClickListener(v ->
                baseBind.isFirebaseEnable.performClick());
    }

    private void bindAutoSnapshotQuotaRow() {
        refreshAutoSnapshotQuotaLabel();
        baseBind.autoSnapshotQuotaRela.setOnClickListener(v -> showAutoSnapshotQuotaDialog());
    }

    private void refreshAutoSnapshotQuotaLabel() {
        baseBind.autoSnapshotQuota.setText(
                autoSnapshotQuotaLabel(mContext, Shaft.sSettings.getAutoSnapshotMaxMb()));
    }

    private static String autoSnapshotQuotaLabel(Context context, int limitMb) {
        if (limitMb == AutoSnapshotQuota.UNLIMITED_LIMIT_MB) {
            return context.getString(R.string.setting_auto_snapshot_quota_unlimited);
        }
        if (limitMb < 1024) {
            return context.getString(R.string.setting_auto_snapshot_quota_value, limitMb);
        }
        String gb = new DecimalFormat("0.##").format(limitMb / 1024d);
        return context.getString(R.string.setting_auto_snapshot_quota_value_gb, gb);
    }

    /**
     * 大小上限入口：WitDialog 承载一条可拖动滑条，拖到最右是「不限制」。
     * 中间值走对数刻度，否则默认 200 MB 会挤在 2 PB 量程的最左端。
     */
    private void showAutoSnapshotQuotaDialog() {
        QuotaDialogBuilder builder = new QuotaDialogBuilder(mActivity);
        builder.setTitle(R.string.setting_auto_snapshot_quota);
        builder.addAction(R.string.string_cancel, (dialog, which) -> dialog.dismiss());
        builder.addAction(0, R.string.sure, WitDialogAction.ACTION_PROP_POSITIVE, (dialog, which) -> {
            SeekBar slider = builder.slider;
            if (slider == null) {
                dialog.dismiss();
                return;
            }
            int chosen = builder.sliderTouched
                    ? AutoSnapshotQuota.limitMbForProgress(
                            slider.getProgress(), AutoSnapshotQuota.SLIDER_STEPS)
                    : builder.initialLimitMb;
            if (chosen != Shaft.sSettings.getAutoSnapshotMaxMb()) {
                Shaft.sSettings.setAutoSnapshotMaxMb(chosen);
                Local.setSettings(Shaft.sSettings);
                Common.showToast(getString(R.string.string_428));
                refreshAutoSnapshotQuotaLabel();
                // 上限改小后立刻按新值淘汰一次，否则要等下一次自动生成才收，
                // 管理页会一直显示「180 MB / 10 MB」，看起来像这个设置没生效。
                AutoSnapshotEngine.INSTANCE.onAutoQuotaLimitChanged();
            }
            dialog.dismiss();
        });
        builder.show();
    }

    /** WitDialog 的自定义内容：标题下的大数值 + V3 滑条 + 两端说明。 */
    private static final class QuotaDialogBuilder extends WitDialog.CustomDialogBuilder {

        private SeekBar slider;
        private TextView valueText;
        private int initialLimitMb;
        private boolean sliderTouched;

        private QuotaDialogBuilder(Context context) {
            super(context);
        }

        @Override
        protected View onCreateContent(WitDialog dialog, WitDialogView parent, Context context) {
            View content = LayoutInflater.from(context)
                    .inflate(R.layout.dialog_auto_snapshot_quota, parent, false);
            slider = content.findViewById(R.id.dialog_auto_snapshot_quota_slider);
            valueText = content.findViewById(R.id.dialog_auto_snapshot_quota_value);
            valueText.setTextColor(V3Palette.from(context).getTextAccent());

            int current = Shaft.sSettings.getAutoSnapshotMaxMb();
            initialLimitMb = current;
            int initialProgress = AutoSnapshotQuota.progressForLimitMb(
                    current, AutoSnapshotQuota.SLIDER_STEPS);
            slider.setMax(AutoSnapshotQuota.SLIDER_STEPS);
            slider.setProgress(initialProgress);
            valueText.setText(autoSnapshotQuotaLabel(context, current));

            slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    if (fromUser) {
                        sliderTouched = true;
                    }
                    int limitMb = AutoSnapshotQuota.limitMbForProgress(
                            progress, AutoSnapshotQuota.SLIDER_STEPS);
                    valueText.setText(autoSnapshotQuotaLabel(context, limitMb));
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
}
