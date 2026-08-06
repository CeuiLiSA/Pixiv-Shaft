package ceui.lisa.fragments;

import android.annotation.SuppressLint;
import android.content.DialogInterface;
import android.os.Handler;
import android.os.Looper;
import android.transition.AutoTransition;
import android.transition.TransitionManager;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.ImageButton;
import android.widget.TextView;

import com.qmuiteam.qmui.skin.QMUISkinManager;
import com.qmuiteam.qmui.widget.dialog.QMUIDialog;

import java.util.Locale;

import ceui.lisa.R;
import ceui.lisa.activities.Shaft;
import ceui.lisa.databinding.FragmentSettingsViewingBinding;
import ceui.lisa.helper.PageTransformerHelper;
import ceui.lisa.utils.Common;
import ceui.lisa.utils.Local;

/** 设置 · 看图与详情 */
public class FragmentSettingsViewing extends SettingsPageFragment<FragmentSettingsViewingBinding> {

    @Override
    public void initLayout() {
        mLayoutID = R.layout.fragment_settings_viewing;
    }

    @Override
    protected void initData() {
        // V3沉浸式作品详情
        baseBind.illustDetailV3.setChecked(Shaft.sSettings.isUseArtworkV3());
        applyArtworkV3FabOrderRowVisibility(Shaft.sSettings.isUseArtworkV3(), false);
        baseBind.illustDetailV3.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                Shaft.sSettings.setUseArtworkV3(isChecked);
                Common.showToast(getString(R.string.string_428), 2);
                Local.setSettings(Shaft.sSettings);
                applyArtworkV3FabOrderRowVisibility(isChecked, true);
            }
        });
        baseBind.illustDetailV3Rela.setOnClickListener(v -> baseBind.illustDetailV3.performClick());

        // 小说列表点击 item 直接进 V3 正文（略过详情页），默认关闭
        baseBind.novelDirectReader.setChecked(Shaft.sSettings.isNovelListDirectToReader());
        baseBind.novelDirectReader.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                Shaft.sSettings.setNovelListDirectToReader(isChecked);
                Common.showToast(getString(R.string.string_428));
                Local.setSettings(Shaft.sSettings);
            }
        });
        baseBind.novelDirectReaderRela.setOnClickListener(v -> baseBind.novelDirectReader.performClick());

        // V3详情页 下载/收藏按钮顺序
        updateArtworkV3FabOrderLabel();
        baseBind.artworkV3FabOrderSelect.setOnClickListener(v -> {
            final int index = Shaft.sSettings.isArtworkV3FabDownloadOnLeft() ? 0 : 1;
            String[] items = new String[]{
                    getString(R.string.artwork_v3_fab_order_download_left),
                    getString(R.string.artwork_v3_fab_order_bookmark_left),
            };
            new QMUIDialog.CheckableDialogBuilder(mActivity)
                    .setCheckedIndex(index)
                    .setSkinManager(QMUISkinManager.defaultInstance(mContext))
                    .addItems(items, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            if (which != index) {
                                Shaft.sSettings.setArtworkV3FabDownloadOnLeft(which == 0);
                                Local.setSettings(Shaft.sSettings);
                                updateArtworkV3FabOrderLabel();
                            }
                            dialog.dismiss();
                        }
                    })
                    .show();
        });
        baseBind.artworkV3FabOrderRela.setOnClickListener(v ->
                baseBind.artworkV3FabOrderSelect.performClick());

        // V3详情页 悬浮胶囊「跳转评论区」按钮（issue #970），默认关闭
        baseBind.artworkV3CommentJump.setChecked(Shaft.sSettings.isArtworkV3ShowCommentJumpFab());
        baseBind.artworkV3CommentJump.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                Shaft.sSettings.setArtworkV3ShowCommentJumpFab(isChecked);
                Common.showToast(getString(R.string.string_428));
                Local.setSettings(Shaft.sSettings);
            }
        });
        baseBind.artworkV3CommentJumpRela.setOnClickListener(v ->
                baseBind.artworkV3CommentJump.performClick());

        // 作品二级详情翻页模式
        String[] transformerNames = PageTransformerHelper.getTransformerNames();
        baseBind.transformType.setText(transformerNames[PageTransformerHelper.getCurrentTransformerIndex()]);
        baseBind.transformTypeRela.setOnClickListener(v ->
                new QMUIDialog.CheckableDialogBuilder(mActivity)
                        .setCheckedIndex(PageTransformerHelper.getCurrentTransformerIndex())
                        .setSkinManager(QMUISkinManager.defaultInstance(mContext))
                        .addItems(transformerNames, new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                if (which != PageTransformerHelper.getCurrentTransformerIndex()) {
                                    PageTransformerHelper.setCurrentTransformer(which);
                                    baseBind.transformType.setText(transformerNames[which]);
                                    Local.setSettings(Shaft.sSettings);
                                }
                                dialog.dismiss();
                            }
                        })
                        .show());

        // 动图 RIFE AI 补帧,默认关闭。开到 on 且模型没下载时顺手把下载页拉起来——
        // 开关保持 on,模型就位后下一次播放自动生效(引擎侧模型缺失会静默回落)。
        baseBind.ugoiraRifeEnable.setChecked(Shaft.sSettings.isUgoiraRifeEnable());
        baseBind.ugoiraRifeEnable.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                Shaft.sSettings.setUgoiraRifeEnable(isChecked);
                Common.showToast(getString(R.string.string_428));
                Local.setSettings(Shaft.sSettings);
                // 内存里可能记着旧变体(原速/补帧)的 gif,清掉,下次播放按新开关重取。
                ceui.pixiv.ui.bulk.UgoiraEngine.invalidateAll();
                if (isChecked && !ceui.pixiv.ui.interpolate.RifeInterpolator.INSTANCE.isAvailable(mContext)) {
                    android.content.Intent intent =
                            new android.content.Intent(mContext, ceui.lisa.activities.TemplateActivity.class);
                    intent.putExtra(ceui.lisa.activities.TemplateActivity.EXTRA_FRAGMENT, "RIFE补帧模型下载");
                    intent.putExtra("rife_model_name", ceui.pixiv.ui.interpolate.RifeModel.RIFE_V4_6.name());
                    startActivity(intent);
                }
            }
        });
        baseBind.ugoiraRifeEnableRela.setOnClickListener(v -> baseBind.ugoiraRifeEnable.performClick());

        // 看图时保留状态栏(刘海/挖孔)区域（issue #724），默认关闭。
        baseBind.keepStatusBarWhenViewImage.setChecked(Shaft.sSettings.isKeepStatusBarWhenViewImage());
        baseBind.keepStatusBarWhenViewImage.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                Shaft.sSettings.setKeepStatusBarWhenViewImage(isChecked);
                Common.showToast(getString(R.string.string_428));
                Local.setSettings(Shaft.sSettings);
            }
        });
        baseBind.keepStatusBarWhenViewImageRela.setOnClickListener(v ->
                baseBind.keepStatusBarWhenViewImage.performClick());

        //插画二级详情保持屏幕常亮
        baseBind.illustDetailKeepScreenOn.setChecked(Shaft.sSettings.isIllustDetailKeepScreenOn());
        baseBind.illustDetailKeepScreenOn.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                Shaft.sSettings.setIllustDetailKeepScreenOn(isChecked);
                Common.showToast(getString(R.string.string_428));
                Local.setSettings(Shaft.sSettings);
            }
        });
        baseBind.illustDetailKeepScreenOnRela.setOnClickListener(v ->
                baseBind.illustDetailKeepScreenOn.performClick());

        //插画二级详情：自定义双击放大模式（PR#900）
        baseBind.useCustomLongPressResetGroup.setVisibility(
                Shaft.sSettings.isUseCustomDoubleTapZoom() ? View.VISIBLE : View.GONE);
        baseBind.useCustomDoubleTapZoom.setChecked(Shaft.sSettings.isUseCustomDoubleTapZoom());
        baseBind.useCustomDoubleTapZoom.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                Shaft.sSettings.setUseCustomDoubleTapZoom(isChecked);
                Common.showToast(getString(R.string.string_428));
                Local.setSettings(Shaft.sSettings);
                ViewGroup customLongPressGroup = (ViewGroup) baseBind.useCustomLongPressResetGroup.getParent();
                if (customLongPressGroup != null) {
                    TransitionManager.beginDelayedTransition(customLongPressGroup, new AutoTransition());
                }
                baseBind.useCustomLongPressResetGroup.setVisibility(isChecked ? View.VISIBLE : View.GONE);
            }
        });
        baseBind.useCustomDoubleTapZoomRela.setOnClickListener(v ->
                baseBind.useCustomDoubleTapZoom.performClick());

        // 初始化缩放增量数值调节
        setupCustomZoomScaleAdjust();

        //长按复位
        baseBind.useCustomLongPressReset.setChecked(Shaft.sSettings.isUseCustomLongPressReset());
        baseBind.useCustomLongPressReset.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                Shaft.sSettings.setUseCustomLongPressReset(isChecked);
                Common.showToast(getString(R.string.string_428));
                Local.setSettings(Shaft.sSettings);
            }
        });

        baseBind.useCustomThreeLevelZoom.setChecked(Shaft.sSettings.isUseThreeLevelZoo());
        baseBind.useCustomThreeLevelZoom.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                Shaft.sSettings.setUseThreeLevelZoo(isChecked);
                Common.showToast(getString(R.string.string_428));
                Local.setSettings(Shaft.sSettings);
            }
        });
    }

    private void updateArtworkV3FabOrderLabel() {
        boolean downloadLeft = Shaft.sSettings.isArtworkV3FabDownloadOnLeft();
        baseBind.artworkV3FabOrderSelect.setText(downloadLeft
                ? R.string.artwork_v3_fab_order_download_left
                : R.string.artwork_v3_fab_order_bookmark_left);
    }

    private void applyArtworkV3FabOrderRowVisibility(boolean v3Enabled, boolean animate) {
        int visibility = v3Enabled ? View.VISIBLE : View.GONE;
        if (animate) {
            View parent = (View) baseBind.artworkV3FabOrderRela.getParent();
            if (parent instanceof ViewGroup) {
                AutoTransition transition = new AutoTransition();
                transition.setDuration(220);
                TransitionManager.beginDelayedTransition((ViewGroup) parent, transition);
            }
        }
        baseBind.artworkV3FabOrderRela.setVisibility(visibility);
        baseBind.artworkV3FabOrderDivider.setVisibility(visibility);
        baseBind.artworkV3CommentJumpRela.setVisibility(visibility);
    }

    private void setupCustomZoomScaleAdjust() {
        TextView scaleDisplay = baseBind.customZoomScaleDisplay;
        ImageButton decreaseBtn = baseBind.customZoomScaleDecrease;
        ImageButton increaseBtn = baseBind.customZoomScaleIncrease;

        // 获取当前保存的缩放增量值
        float currentScale = Shaft.sSettings.getCustomZoomAddScale();
        if (currentScale < 1.1f || currentScale > 3.0f) {
            currentScale = 1.8f; // 默认值
            Shaft.sSettings.setCustomZoomAddScale(currentScale);
        }

        // 显示当前值
        scaleDisplay.setText(String.format(Locale.US, "%.1f", currentScale));

        // 减少按钮
        decreaseBtn.setOnClickListener(v -> {
            float scale = Shaft.sSettings.getCustomZoomAddScale();
            if (scale > 1.1f) {
                scale = Math.round((scale - 0.1f) * 10f) / 10f;
                updateZoomScale(scale, scaleDisplay);
            }
        });

        // 增加按钮
        increaseBtn.setOnClickListener(v -> {
            float scale = Shaft.sSettings.getCustomZoomAddScale();
            if (scale < 3.0f) {
                scale = Math.round((scale + 0.1f) * 10f) / 10f;
                updateZoomScale(scale, scaleDisplay);
            }
        });

        // 长按快速调节（可选）
        setupLongPressAdjust(decreaseBtn, increaseBtn, scaleDisplay);
    }

    private void updateZoomScale(float newScale, TextView scaleDisplay) {
        // 保存到 Shaft.sSettings
        Shaft.sSettings.setCustomZoomAddScale(newScale);

        // 更新显示
        scaleDisplay.setText(String.format(Locale.US, "%.1f", newScale));

        // 保存设置
        Local.setSettings(Shaft.sSettings);
    }

    // 长按快速调节功能（可选）
    private Handler autoAdjustHandler;
    private Runnable autoAdjustRunnable;

    @SuppressLint("ClickableViewAccessibility")
    private void setupLongPressAdjust(ImageButton decreaseBtn,
                                      ImageButton increaseBtn,
                                      TextView scaleDisplay) {
        decreaseBtn.setOnLongClickListener(v -> {
            startAutoAdjust(scaleDisplay, false);
            return true;
        });

        increaseBtn.setOnLongClickListener(v -> {
            startAutoAdjust(scaleDisplay, true);
            return true;
        });

        View.OnTouchListener stopAdjustListener = new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                if (event.getAction() == MotionEvent.ACTION_UP ||
                        event.getAction() == MotionEvent.ACTION_CANCEL) {
                    stopAutoAdjust();
                }
                return false;
            }
        };

        decreaseBtn.setOnTouchListener(stopAdjustListener);
        increaseBtn.setOnTouchListener(stopAdjustListener);
    }

    private void startAutoAdjust(TextView scaleDisplay, boolean isIncrease) {
        stopAutoAdjust();

        if (autoAdjustHandler == null) {
            autoAdjustHandler = new Handler(Looper.getMainLooper());
        }

        autoAdjustRunnable = new Runnable() {
            @Override
            public void run() {
                float scale = Shaft.sSettings.getCustomZoomAddScale();

                if (isIncrease && scale < 3.0f) {
                    scale = Math.round((scale + 0.1f) * 10f) / 10f;
                    updateZoomScale(scale, scaleDisplay);
                } else if (!isIncrease && scale > 1.1f) {
                    scale = Math.round((scale - 0.1f) * 10f) / 10f;
                    updateZoomScale(scale, scaleDisplay);
                }

                autoAdjustHandler.postDelayed(this, 100);
            }
        };

        autoAdjustHandler.postDelayed(autoAdjustRunnable, 500);
    }

    private void stopAutoAdjust() {
        if (autoAdjustHandler != null && autoAdjustRunnable != null) {
            autoAdjustHandler.removeCallbacks(autoAdjustRunnable);
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        stopAutoAdjust();
    }
}
