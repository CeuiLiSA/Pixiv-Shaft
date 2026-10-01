package ceui.lisa.fragments;

import android.annotation.SuppressLint;
import android.content.DialogInterface;
import android.content.Intent;
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

import ceui.pixiv.witstudio.dialog.WitDialog;

import java.util.Locale;

import ceui.lisa.R;
import ceui.lisa.activities.Shaft;
import ceui.lisa.activities.TemplateActivity;
import ceui.lisa.databinding.FragmentSettingsViewingBinding;
import ceui.lisa.helper.PageTransformerHelper;
import ceui.lisa.utils.Common;
import ceui.lisa.utils.Local;
import ceui.lisa.utils.Settings;
import ceui.pixiv.ui.navigation.TemplateRoute;
import ceui.pixiv.ui.comic.reader.ComicReaderSettings;

/** 设置 · 看图与详情 */
public class FragmentSettingsViewing extends SettingsPageFragment<FragmentSettingsViewingBinding> {

    /**
     * RIFE 开关点了「开」但模型缺失、已跳下载页。此时开关只停在界面上的 on,设置尚未落盘;
     * 等 onResume 按模型是否真落盘决定「落盘 + toast」还是「拨回 off 且不落盘」。
     */
    private boolean rifeAwaitingModel = false;
    /** 程序性回写开关(拨回 off)时抑制 listener,避免再次跳转或弹 toast。 */
    private boolean rifeSuppressToggleCallback = false;

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

        // 详情页「作品详情 / 作品档案」面板默认折叠（#1044），默认关闭
        baseBind.detailPanelCollapsed.setChecked(Shaft.sSettings.isDetailPanelCollapsedByDefault());
        baseBind.detailPanelCollapsed.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                Shaft.sSettings.setDetailPanelCollapsedByDefault(isChecked);
                Common.showToast(getString(R.string.string_428));
                Local.setSettings(Shaft.sSettings);
            }
        });
        baseBind.detailPanelCollapsedRela.setOnClickListener(v -> baseBind.detailPanelCollapsed.performClick());

        // V3详情页 悬浮胶囊位置（issue #1090）：居中 / 靠左 / 靠右
        updateArtworkV3FabPositionLabel();
        baseBind.artworkV3FabPositionSelect.setOnClickListener(v -> {
            final int index = Shaft.sSettings.getArtworkV3FabPosition();
            String[] items = new String[]{
                    getString(R.string.artwork_v3_fab_position_center),
                    getString(R.string.artwork_v3_fab_position_left),
                    getString(R.string.artwork_v3_fab_position_right),
            };
            new WitDialog.CheckableDialogBuilder(mActivity)
                    .setCheckedIndex(index)
                    .addItems(items, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            if (which != index) {
                                Shaft.sSettings.setArtworkV3FabPosition(which);
                                Local.setSettings(Shaft.sSettings);
                                updateArtworkV3FabPositionLabel();
                                applyArtworkV3FabOrderRowVisibility(Shaft.sSettings.isUseArtworkV3(), true);
                            }
                            dialog.dismiss();
                        }
                    })
                    .show();
        });
        baseBind.artworkV3FabPositionRela.setOnClickListener(v ->
                baseBind.artworkV3FabPositionSelect.performClick());

        // V3详情页 下载/收藏按钮顺序
        updateArtworkV3FabOrderLabel();
        baseBind.artworkV3FabOrderSelect.setOnClickListener(v -> {
            final int index = Shaft.sSettings.isArtworkV3FabDownloadOnLeft() ? 0 : 1;
            String[] items = new String[]{
                    getString(R.string.artwork_v3_fab_order_download_left),
                    getString(R.string.artwork_v3_fab_order_bookmark_left),
            };
            new WitDialog.CheckableDialogBuilder(mActivity)
                    .setCheckedIndex(index)
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

        // V3详情页 评论预览区块，默认开启；关掉后「跳转评论区」无处可跳，该行随之隐藏
        baseBind.artworkV3ShowComments.setChecked(Shaft.sSettings.isArtworkV3ShowComments());
        baseBind.artworkV3ShowComments.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                Shaft.sSettings.setArtworkV3ShowComments(isChecked);
                Common.showToast(getString(R.string.string_428));
                Local.setSettings(Shaft.sSettings);
                applyArtworkV3FabOrderRowVisibility(Shaft.sSettings.isUseArtworkV3(), true);
            }
        });
        baseBind.artworkV3ShowCommentsRela.setOnClickListener(v ->
                baseBind.artworkV3ShowComments.performClick());

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

        // V3详情页 多图作品自动展开剩余页（issue #1090），默认关闭
        baseBind.artworkV3AutoExpand.setChecked(Shaft.sSettings.isArtworkV3AutoExpandMultiPage());
        baseBind.artworkV3AutoExpand.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                Shaft.sSettings.setArtworkV3AutoExpandMultiPage(isChecked);
                Common.showToast(getString(R.string.string_428));
                Local.setSettings(Shaft.sSettings);
            }
        });
        baseBind.artworkV3AutoExpandRela.setOnClickListener(v ->
                baseBind.artworkV3AutoExpand.performClick());

        // 作品二级详情翻页模式
        String[] transformerNames = PageTransformerHelper.getTransformerNames();
        baseBind.transformType.setText(transformerNames[PageTransformerHelper.getCurrentTransformerIndex()]);
        baseBind.transformTypeRela.setOnClickListener(v ->
                new WitDialog.CheckableDialogBuilder(mActivity)
                        .setCheckedIndex(PageTransformerHelper.getCurrentTransformerIndex())
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

        // 动图 RIFE AI 补帧,默认关闭。开关 on 与「模型真落盘」强绑定:
        // 模型已在位 → 当场落盘 + 弹「设置成功」;模型缺失 → 先不落盘,跳下载页,
        // 等用户回来(onResume)再结算——真落了才落盘 + toast,没落就把开关拨回 off 且不落盘。
        // 归一化:别处(AI 设置页可长按删模型)把模型删掉后,把残留的 on 静默落回 false,
        // 保证「开关开 ⇒ 模型一定落盘」在任何入口下都成立。
        if (ceui.pixiv.ui.interpolate.RifePrefs.isEnabled()
                && !ceui.pixiv.ui.interpolate.RifeInterpolator.INSTANCE.isAvailable(mContext)) {
            applyUgoiraRifeEnable(false);
        }
        baseBind.ugoiraRifeEnable.setChecked(ceui.pixiv.ui.interpolate.RifePrefs.isEnabled());
        baseBind.ugoiraRifeEnable.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (rifeSuppressToggleCallback) {
                    return;
                }
                if (isChecked && !ceui.pixiv.ui.interpolate.RifeInterpolator.INSTANCE.isAvailable(mContext)) {
                    // 模型缺失:不落盘、不弹 toast,先跳下载页,回来再结算。
                    rifeAwaitingModel = true;
                    android.content.Intent intent =
                            new android.content.Intent(mContext, ceui.lisa.activities.TemplateActivity.class);
                    intent.putExtra(ceui.lisa.activities.TemplateActivity.EXTRA_FRAGMENT, TemplateRoute.RIFE_MODEL_DOWNLOAD.key);
                    intent.putExtra("rife_model_name", ceui.pixiv.ui.interpolate.RifeModel.RIFE_V4_6.name());
                    startActivity(intent);
                    return;
                }
                applyUgoiraRifeEnable(isChecked);
                Common.showToast(getString(R.string.string_428));
            }
        });
        baseBind.ugoiraRifeEnableRela.setOnClickListener(v -> baseBind.ugoiraRifeEnable.performClick());

        //动画(ugoira) 自动播放，默认开启。关闭后详情页不自动下载/播放，在图片中间显示「开始播放（下载）」按钮。
        baseBind.ugoiraAutoPlay.setChecked(Shaft.sSettings.isAutoPlayUgoira());
        baseBind.ugoiraAutoPlay.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                Shaft.sSettings.setAutoPlayUgoira(isChecked);
                Common.showToast(getString(R.string.string_428));
                Local.setSettings(Shaft.sSettings);
            }
        });
        baseBind.ugoiraAutoPlayRela.setOnClickListener(v -> baseBind.ugoiraAutoPlay.performClick());

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

        baseBind.comicReaderAutoRotateImage.setChecked(ComicReaderSettings.INSTANCE.getAutoRotateImage());
        baseBind.comicReaderAutoRotateImage.setOnCheckedChangeListener((buttonView, isChecked) ->
                ComicReaderSettings.INSTANCE.setAutoRotateImage(isChecked));
        baseBind.comicReaderAutoRotateImageRela.setOnClickListener(v ->
                baseBind.comicReaderAutoRotateImage.performClick());

        // 大图拖动退出控制：点进测试页实时调节三个灵敏度阈值（距离 / 甩速 / 缩放反馈）。
        baseBind.viewerDismissTuningRela.setOnClickListener(v -> {
            Intent intent = new Intent(mContext, TemplateActivity.class);
            intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, TemplateRoute.VIEWER_DISMISS_TUNING.key);
            startActivity(intent);
        });

        // 大图 ↔ 详情页视口联动：不跟随 / 仅已展开时 / 自动展开并跟随（默认不跟随）
        updateViewerViewportLinkLabel();
        baseBind.viewerViewportLinkRela.setOnClickListener(v -> {
            final int current = Shaft.sSettings.getViewerViewportLinkMode();
            // 选项顺序必须与 Settings.VIEWER_VIEWPORT_LINK_* 的取值一致：which 直接当模式值用
            String[] modeNames = new String[]{
                    getString(R.string.viewer_viewport_link_none),
                    getString(R.string.viewer_viewport_link_expanded_only),
                    getString(R.string.viewer_viewport_link_auto_expand),
            };
            // 参考「插画大图长按行为」：行上只留标题与当前值，解释小字挪进弹窗，
            // 标题栏右侧问号点开才展开；标题复用设置项名。
            WitDialog.CheckableDialogBuilder builder =
                    new WitDialog.CheckableDialogBuilder(mActivity)
                            .setTitle(R.string.viewer_viewport_link_title)
                            .setCheckedIndex(current)
                            .setCollapsibleHint(getString(R.string.viewer_viewport_link_hint));
            builder.addTitleAction(
                    R.drawable.ic_help_outline_black_24dp,
                    getString(R.string.viewer_viewport_link_help_desc),
                    action -> builder.setHintExpanded(!builder.isHintExpanded()));
            builder.addItems(modeNames, new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    if (which != current) {
                        Shaft.sSettings.setViewerViewportLinkMode(which);
                        Common.showToast(getString(R.string.string_428));
                        Local.setSettings(Shaft.sSettings);
                        updateViewerViewportLinkLabel();
                    }
                    dialog.dismiss();
                }
            }).show();
        });

        // 插画大图双击缩放行为：默认 / 三级 / 增量
        updateDoubleTapZoomModeLabel();
        baseBind.doubleTapZoomModeRela.setOnClickListener(v -> {
            final int current = Shaft.sSettings.getDoubleTapZoomMode();
            String[] doubleTapZoomModeNames = new String[]{
                    getString(R.string.double_tap_zoom_mode_default),
                    getString(R.string.double_tap_zoom_mode_three_level),
                    getString(R.string.double_tap_zoom_mode_incremental),
            };
            new WitDialog.CheckableDialogBuilder(mActivity)
                    .setCheckedIndex(current)
                    .addItems(doubleTapZoomModeNames, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            if (which != current) {
                                Shaft.sSettings.setDoubleTapZoomMode(which);
                                Common.showToast(getString(R.string.string_428));
                                Local.setSettings(Shaft.sSettings);
                                // 参考直连开关：切换模式时「缩放增量」行插入/移出，长按复位行随之平滑下移/上移。
                                ViewGroup parent = (ViewGroup) baseBind.doubleTapZoomGroup.getParent();
                                if (parent != null) {
                                    TransitionManager.beginDelayedTransition(parent, new AutoTransition());
                                }
                                updateDoubleTapZoomModeLabel();
                            }
                            dialog.dismiss();
                        }
                    })
                    .show();
        });

        // 大图长按行为：无 / 优先缩小一级 / 复原至最小，始终显示在双击缩放行为下方
        updateLongPressBehaviorLabel();
        baseBind.longPressBehaviorRela.setOnClickListener(v -> {
            final int current = Shaft.sSettings.getLongPressBehavior();
            // 选项顺序必须与 Settings.LONG_PRESS_BEHAVIOR_* 的取值一致：which 直接当行为值用
            String[] longPressBehaviorNames = new String[]{
                    getString(R.string.long_press_behavior_none),
                    getString(R.string.long_press_behavior_shrink_one_level),
                    getString(R.string.long_press_behavior_reset_min),
            };
            // 参考「GitHub 加速地址」：行上只留标题与当前值，解释小字挪进弹窗，
            // 标题栏右侧问号点开才展开；标题复用设置项名。
            WitDialog.CheckableDialogBuilder builder =
                    new WitDialog.CheckableDialogBuilder(mActivity)
                            .setTitle(R.string.long_press_behavior_title)
                            .setCheckedIndex(current)
                            .setCollapsibleHint(getString(R.string.long_press_behavior_hint));
            builder.addTitleAction(
                    R.drawable.ic_help_outline_black_24dp,
                    getString(R.string.long_press_behavior_help_desc),
                    action -> builder.setHintExpanded(!builder.isHintExpanded()));
            builder.addItems(longPressBehaviorNames, new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    if (which != current) {
                        Shaft.sSettings.setLongPressBehavior(which);
                        Common.showToast(getString(R.string.string_428));
                        Local.setSettings(Shaft.sSettings);
                        updateLongPressBehaviorLabel();
                    }
                    dialog.dismiss();
                }
            }).show();
        });

        // 初始化缩放增量数值调节
        setupCustomZoomScaleAdjust();
    }

    private void updateArtworkV3FabPositionLabel() {
        int labelRes;
        switch (Shaft.sSettings.getArtworkV3FabPosition()) {
            case Settings.ARTWORK_V3_FAB_POSITION_LEFT:
                labelRes = R.string.artwork_v3_fab_position_left;
                break;
            case Settings.ARTWORK_V3_FAB_POSITION_RIGHT:
                labelRes = R.string.artwork_v3_fab_position_right;
                break;
            default:
                labelRes = R.string.artwork_v3_fab_position_center;
                break;
        }
        baseBind.artworkV3FabPositionSelect.setText(labelRes);
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
        baseBind.artworkV3FabPositionRela.setVisibility(visibility);
        // 胶囊靠边时收藏心固定在外侧，顺序设置不生效，只在居中时露出（#1090）
        boolean centered = Shaft.sSettings.getArtworkV3FabPosition() == Settings.ARTWORK_V3_FAB_POSITION_CENTER;
        baseBind.artworkV3FabOrderRela.setVisibility(v3Enabled && centered ? View.VISIBLE : View.GONE);
        baseBind.artworkV3FabOrderDivider.setVisibility(visibility);
        baseBind.artworkV3ShowCommentsRela.setVisibility(visibility);
        baseBind.artworkV3CommentJumpRela.setVisibility(
                v3Enabled && Shaft.sSettings.isArtworkV3ShowComments() ? View.VISIBLE : View.GONE);
        baseBind.artworkV3AutoExpandRela.setVisibility(visibility);
    }

    private void updateDoubleTapZoomModeLabel() {
        int mode = Shaft.sSettings.getDoubleTapZoomMode();
        int labelRes;
        switch (mode) {
            case Settings.DOUBLE_TAP_ZOOM_MODE_THREE_LEVEL:
                labelRes = R.string.double_tap_zoom_mode_three_level;
                break;
            case Settings.DOUBLE_TAP_ZOOM_MODE_INCREMENTAL:
                labelRes = R.string.double_tap_zoom_mode_incremental;
                break;
            default:
                labelRes = R.string.double_tap_zoom_mode_default;
                break;
        }
        baseBind.doubleTapZoomModeValue.setText(labelRes);
        updateDoubleTapZoomDependentVisibility();
    }

    private void updateLongPressBehaviorLabel() {
        int labelRes;
        switch (Shaft.sSettings.getLongPressBehavior()) {
            case Settings.LONG_PRESS_BEHAVIOR_SHRINK_ONE_LEVEL:
                labelRes = R.string.long_press_behavior_shrink_one_level;
                break;
            case Settings.LONG_PRESS_BEHAVIOR_RESET_MIN:
                labelRes = R.string.long_press_behavior_reset_min;
                break;
            default:
                labelRes = R.string.long_press_behavior_none;
                break;
        }
        baseBind.longPressBehaviorValue.setText(labelRes);
    }

    private void updateViewerViewportLinkLabel() {
        int labelRes;
        switch (Shaft.sSettings.getViewerViewportLinkMode()) {
            case Settings.VIEWER_VIEWPORT_LINK_EXPANDED_ONLY:
                labelRes = R.string.viewer_viewport_link_expanded_only;
                break;
            case Settings.VIEWER_VIEWPORT_LINK_AUTO_EXPAND:
                labelRes = R.string.viewer_viewport_link_auto_expand;
                break;
            default:
                labelRes = R.string.viewer_viewport_link_none;
                break;
        }
        baseBind.viewerViewportLinkValue.setText(labelRes);
    }

    private void updateDoubleTapZoomDependentVisibility() {
        boolean isIncremental = Shaft.sSettings.getDoubleTapZoomMode() == Settings.DOUBLE_TAP_ZOOM_MODE_INCREMENTAL;
        // 参考直连开关的显隐方式：仅增量模式时「缩放增量」行插入在双击行为与大图长按行为之间，
        // 把大图长按行为行向下挤压；默认/三级模式时隐藏该行，大图长按行为行直接跟在双击行为下方。
        baseBind.customZoomScaleRela.setVisibility(isIncremental ? View.VISIBLE : View.GONE);
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
    public void onResume() {
        super.onResume();
        settlePendingRifeToggle();
    }

    /**
     * 从 RIFE 模型下载页回来时结算开关:模型真落盘 → 这时才把设置落盘并弹「设置成功」;
     * 没落盘(用户没下/下载失败/中途取消) → 设置保持关闭(不落盘),界面开关拨回 off 且不弹 toast。
     */
    private void settlePendingRifeToggle() {
        if (!rifeAwaitingModel) {
            return;
        }
        rifeAwaitingModel = false;
        if (ceui.pixiv.ui.interpolate.RifeInterpolator.INSTANCE.isAvailable(mContext)) {
            // 模型真落盘了,此刻才允许把「开关开」写进设置。
            applyUgoiraRifeEnable(true);
            Common.showToast(getString(R.string.string_428));
            return;
        }
        if (rootView != null) {
            rifeSuppressToggleCallback = true;
            baseBind.ugoiraRifeEnable.setChecked(false);
            rifeSuppressToggleCallback = false;
        }
    }

    /**
     * 落盘 RIFE 开关并让补帧产物缓存失效。只在「模型确已在位」或「明确关闭」时调用,
     * 以保证设置里的 on 一定对应模型已落盘。
     */
    private void applyUgoiraRifeEnable(boolean enabled) {
        // 落设备本地 MMKV(不进 Settings ⇒ 不进备份 / 云端),所以不需要 Local.setSettings。
        ceui.pixiv.ui.interpolate.RifePrefs.setEnabled(enabled);
        // 内存里可能记着旧变体(原速/补帧)的 gif,清掉,下次播放按新开关重取。
        ceui.pixiv.ui.bulk.UgoiraEngine.invalidateAll();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        stopAutoAdjust();
    }
}
