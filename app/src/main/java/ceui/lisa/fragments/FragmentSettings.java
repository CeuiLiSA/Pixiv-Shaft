package ceui.lisa.fragments;

import android.Manifest;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.text.TextUtils;
import android.transition.AutoTransition;
import android.transition.TransitionManager;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.blankj.utilcode.util.FileUtils;
import com.blankj.utilcode.util.UriUtils;
import com.google.firebase.analytics.FirebaseAnalytics;
import com.qmuiteam.qmui.skin.QMUISkinManager;
import com.qmuiteam.qmui.widget.dialog.QMUIDialog;
import com.qmuiteam.qmui.widget.dialog.QMUIDialogAction;
import com.scwang.smart.refresh.header.FalsifyFooter;
import com.scwang.smart.refresh.header.FalsifyHeader;
import com.scwang.smart.refresh.layout.SmartRefreshLayout;

import com.tbruyelle.rxpermissions3.RxPermissions;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.util.Arrays;
import java.util.Locale;

import ceui.lisa.R;
import ceui.lisa.activities.BaseActivity;
import ceui.lisa.activities.Shaft;
import ceui.lisa.activities.TemplateActivity;
import ceui.lisa.databinding.FragmentSettingsBinding;
import ceui.lisa.download.IllustDownload;
import ceui.lisa.file.LegacyFile;
import ceui.lisa.helper.NavigationLocationHelper;
import ceui.lisa.helper.PageTransformerHelper;
import ceui.lisa.helper.ThemeHelper;
import ceui.lisa.http.Retro;
import ceui.lisa.interfaces.Callback;
import ceui.lisa.utils.BackupUtils;
import ceui.lisa.utils.Common;
import ceui.lisa.utils.DownloadLimitTypeUtil;
import ceui.lisa.utils.Local;
import ceui.lisa.utils.Params;
import ceui.lisa.utils.PixivSearchParamUtil;
import ceui.lisa.utils.Settings;
import ceui.lisa.utils.UserFolderNameUtil;
import ceui.loxia.Client;

import static android.app.Activity.RESULT_OK;
import static android.provider.DocumentsContract.EXTRA_INITIAL_URI;
import static ceui.lisa.helper.ThemeHelper.ThemeType.DARK_MODE;
import static ceui.lisa.helper.ThemeHelper.ThemeType.DEFAULT_MODE;
import static ceui.lisa.helper.ThemeHelper.ThemeType.LIGHT_MODE;


public class FragmentSettings extends SwipeFragment<FragmentSettingsBinding> {

    @Override
    public void initLayout() {
        mLayoutID = R.layout.fragment_settings;
    }

    @Override
    protected void initData() {
        baseBind.toolbar.setNavigationOnClickListener(view -> mActivity.finish());
        Common.animate(baseBind.parentLinear);

        // 账号
        {
            baseBind.userManage.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    Intent intent = new Intent(mContext, TemplateActivity.class);
                    intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "账号管理");
                    startActivity(intent);
                }
            });

            baseBind.editAccount.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    Intent intent = new Intent(mContext, TemplateActivity.class);
                    intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "绑定邮箱");
                    startActivity(intent);
                }
            });

            baseBind.editFile.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    Intent intent = new Intent(mContext, TemplateActivity.class);
                    intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "编辑个人资料");
                    startActivity(intent);
                }
            });

            baseBind.workSpace.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    Intent intent = new Intent(mContext, TemplateActivity.class);
                    intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "我的作业环境");
                    startActivity(intent);
                }
            });

            baseBind.r18Space.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    Intent intent = new Intent(mContext, TemplateActivity.class);
                    intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "网页链接");
                    intent.putExtra(Params.URL, Params.URL_R18_SETTING);
                    startActivity(intent);
                }
            });

            baseBind.premiumSpace.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    Intent intent = new Intent(mContext, TemplateActivity.class);
                    intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "网页链接");
                    intent.putExtra(Params.URL, Params.URL_PREMIUM_SETTING);
                    startActivity(intent);
                }
            });

            baseBind.loginOut.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    QMUIDialog.CheckBoxMessageDialogBuilder builder = new QMUIDialog.CheckBoxMessageDialogBuilder(getActivity());
                    builder
                            .setTitle(getString(R.string.string_185))
                            .setMessage(getString(R.string.string_186))
                            .setChecked(true)
                            .setSkinManager(QMUISkinManager.defaultInstance(mContext))
                            .addAction(getString(R.string.string_187), new QMUIDialogAction.ActionListener() {
                                @Override
                                public void onClick(QMUIDialog dialog, int index) {
                                    dialog.dismiss();
                                }
                            })
                            .addAction(R.string.login_out, new QMUIDialogAction.ActionListener() {
                                @Override
                                public void onClick(QMUIDialog dialog, int index) {
                                    Common.logOut(mContext, builder.isChecked());
                                    mActivity.finish();
                                    dialog.dismiss();
                                }
                            })
                            .create()
                            .show();
                }
            });
        }

        // 网络
        {
            baseBind.autoDns.setChecked(Shaft.sSettings.isDirectConnect());
            baseBind.autoDns.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                    boolean changed = isChecked != Shaft.sSettings.isDirectConnect();
                    Shaft.sSettings.setDirectConnect(isChecked);
                    Common.showToast(getString(R.string.string_428), 2);
                    Local.setSettings(Shaft.sSettings);
                    if (changed) {
                        Retro.refreshAppApi();
                        Client.INSTANCE.reset();
                    }
                }
            });
            baseBind.directConnectLink.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    Intent intent = new Intent(mContext, TemplateActivity.class);
                    intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "网页链接");
                    intent.putExtra(Params.URL, "https://github.com/Notsfsssf/Pix-EzViewer");
                    intent.putExtra(Params.TITLE, "PxEz项目主页");
                    startActivity(intent);
                }
            });
            baseBind.directConnectRela.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    baseBind.autoDns.performClick();
                }
            });

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
            baseBind.showLargeThumbnailImageRela.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    baseBind.showLargeThumbnailImage.performClick();
                }
            });

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
            baseBind.showOriginalPreviewImageRela.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    baseBind.showOriginalPreviewImage.performClick();
                }
            });
        }

        // 常规
        {
            baseBind.saveHistory.setChecked(Shaft.sSettings.isSaveViewHistory());
            baseBind.saveHistory.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                    Shaft.sSettings.setSaveViewHistory(isChecked);
                    Common.showToast(getString(R.string.string_428), 2);
                    Local.setSettings(Shaft.sSettings);
                }
            });
            baseBind.saveHistoryRela.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    baseBind.saveHistory.performClick();
                }
            });

            baseBind.deleteStarIllust.setChecked(Shaft.sSettings.isDeleteStarIllust());
            baseBind.deleteStarIllust.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                    Shaft.sSettings.setDeleteStarIllust(isChecked);
                    Common.showToast(getString(R.string.string_428), 2);
                    Local.setSettings(Shaft.sSettings);
                }
            });
            baseBind.deleteStarIllustRela.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    baseBind.deleteStarIllust.performClick();
                }
            });

            baseBind.filterRankBookmarked.setChecked(Shaft.sSettings.isFilterRankBookmarked());
            baseBind.filterRankBookmarked.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                    Shaft.sSettings.setFilterRankBookmarked(isChecked);
                    Common.showToast(getString(R.string.string_428), 2);
                    Local.setSettings(Shaft.sSettings);
                }
            });
            baseBind.filterRankBookmarkedRela.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    baseBind.filterRankBookmarked.performClick();
                }
            });

            baseBind.deleteAiIllust.setChecked(Shaft.sSettings.isDeleteAIIllust());
            baseBind.deleteAiIllust.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                    Shaft.sSettings.setDeleteAIIllust(isChecked);
                    Common.showToast(getString(R.string.string_428), 2);
                    Local.setSettings(Shaft.sSettings);
                }
            });
            baseBind.deleteAiIllustRela.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    baseBind.deleteAiIllust.performClick();
                }
            });

            baseBind.toastDownloadResult.setChecked(Shaft.sSettings.isToastDownloadResult());
            baseBind.toastDownloadResult.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                    Shaft.sSettings.setToastDownloadResult(isChecked);
                    Common.showToast(getString(R.string.string_428), 2);
                    Local.setSettings(Shaft.sSettings);
                }
            });
            baseBind.toastDownloadResultRela.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    baseBind.toastDownloadResult.performClick();
                }
            });

            final String searchFilter = Shaft.sSettings.getSearchFilter();
            baseBind.searchFilter.setText(PixivSearchParamUtil.getSizeName(searchFilter));
            baseBind.searchFilterRela.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    new QMUIDialog.CheckableDialogBuilder(mContext)
                            .setCheckedIndex(PixivSearchParamUtil.getSizeIndex(Shaft.sSettings.getSearchFilter()))
                            .setSkinManager(QMUISkinManager.defaultInstance(mContext))
                            .addItems(PixivSearchParamUtil.ALL_SIZE_NAME, new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface dialog, int which) {
                                    Shaft.sSettings.setSearchFilter(PixivSearchParamUtil.ALL_SIZE_VALUE[which]);
                                    Common.showToast(getString(R.string.string_428), 2);
                                    Local.setSettings(Shaft.sSettings);
                                    baseBind.searchFilter.setText(PixivSearchParamUtil.ALL_SIZE_NAME[which]);
                                    dialog.dismiss();
                                }
                            })
                            .create()
                            .show();
                }
            });

            // 搜索结果默认排序方式
            final String searchDefaultSortType = Shaft.sSettings.getSearchDefaultSortType();
            baseBind.searchDefaultSortType.setText(PixivSearchParamUtil.getSortTypeName(searchDefaultSortType));
            baseBind.searchDefaultSortTypeRela.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    new QMUIDialog.CheckableDialogBuilder(mContext)
                            .setCheckedIndex(PixivSearchParamUtil.getSortTypeIndex(Shaft.sSettings.getSearchDefaultSortType()))
                            .setSkinManager(QMUISkinManager.defaultInstance(mContext))
                            .addItems(PixivSearchParamUtil.SORT_TYPE_NAME, new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface dialog, int which) {
                                    Shaft.sSettings.setSearchDefaultSortType(PixivSearchParamUtil.SORT_TYPE_VALUE[which]);
                                    Common.showToast(getString(R.string.string_428), 2);
                                    Local.setSettings(Shaft.sSettings);
                                    baseBind.searchDefaultSortType.setText(PixivSearchParamUtil.SORT_TYPE_NAME[which]);
                                    dialog.dismiss();
                                }
                            })
                            .create()
                            .show();
                }
            });

            // 过滤垃圾评论
            baseBind.filterComment.setChecked(Shaft.sSettings.isFilterComment());
            baseBind.filterComment.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                    Shaft.sSettings.setFilterComment(isChecked);
                    Common.showToast(getString(R.string.string_428), 2);
                    Local.setSettings(Shaft.sSettings);
                }
            });
            baseBind.filterCommentRela.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    baseBind.filterComment.performClick();
                }
            });

            // 默认开启R18内容过滤
            baseBind.r18FilterDefaultEnable.setChecked(Shaft.sSettings.isR18FilterDefaultEnable());
            baseBind.r18FilterDefaultEnable.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                    Shaft.sSettings.setR18FilterDefaultEnable(isChecked);
                    Common.showToast(getString(R.string.string_428), 2);
                    Local.setSettings(Shaft.sSettings);
                }
            });
            baseBind.r18FilterDefaultEnableRela.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    baseBind.r18FilterDefaultEnable.performClick();
                }
            });
        }

        // 界面
        {
            // APP主页显示R页面
            baseBind.mainViewR18.setChecked(Shaft.sSettings.isMainViewR18());
            baseBind.mainViewR18.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                    Shaft.sSettings.setMainViewR18(isChecked);
                    Common.showToast(getString(R.string.please_restart_app), 2);
                    Local.setSettings(Shaft.sSettings);
                }
            });
            baseBind.mainViewR18Rela.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    baseBind.mainViewR18.performClick();
                }
            });

            // 首页导航栏初始化位置
            String navigationInitPositionSettingValue = Shaft.sSettings.getNavigationInitPosition();
            final String navigationInitPosition = !TextUtils.isEmpty(navigationInitPositionSettingValue) ? navigationInitPositionSettingValue : NavigationLocationHelper.TUIJIAN;
            baseBind.navigationInitPosition.setText(NavigationLocationHelper.SETTING_NAME_MAP.get(navigationInitPosition));
            baseBind.navigationInitPositionRela.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    String[] OPTION_VALUES = NavigationLocationHelper.SETTING_NAME_MAP.keySet().toArray(new String[0]);
                    String[] OPTION_NAMES = NavigationLocationHelper.SETTING_NAME_MAP.values().toArray(new String[0]);
                    String navigationInitPositionSettingValue = Shaft.sSettings.getNavigationInitPosition();
                    final String navigationInitPosition = !TextUtils.isEmpty(navigationInitPositionSettingValue) ? navigationInitPositionSettingValue : NavigationLocationHelper.TUIJIAN;
                    final int index = Arrays.asList(OPTION_VALUES).indexOf(navigationInitPosition);
                    new QMUIDialog.CheckableDialogBuilder(mActivity)
                            .setCheckedIndex(index)
                            .setSkinManager(QMUISkinManager.defaultInstance(mContext))
                            .addItems(OPTION_NAMES, new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface dialog, int which) {
                                    if (which != index) {
                                        Shaft.sSettings.setNavigationInitPosition(OPTION_VALUES[which]);
                                        baseBind.navigationInitPosition.setText(OPTION_NAMES[which]);
                                        Local.setSettings(Shaft.sSettings);
                                    }
                                    dialog.dismiss();
                                }
                            })
                            .show();
                }
            });

            // 新版作品详情
            baseBind.illustDetailUserNew.setChecked(Shaft.sSettings.isUseFragmentIllust());
            baseBind.illustDetailUserNew.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                    Shaft.sSettings.setUseFragmentIllust(isChecked);
                    Common.showToast(getString(R.string.string_428), 2);
                    Local.setSettings(Shaft.sSettings);
                }
            });
            baseBind.illustDetailUserNewRela.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    baseBind.illustDetailUserNew.performClick();
                }
            });

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
            baseBind.illustDetailV3Rela.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    baseBind.illustDetailV3.performClick();
                }
            });

            // V3详情页 下载/收藏按钮顺序
            updateArtworkV3FabOrderLabel();
            baseBind.artworkV3FabOrderSelect.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
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
                }
            });
            baseBind.artworkV3FabOrderRela.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    baseBind.artworkV3FabOrderSelect.performClick();
                }
            });

            // 主题模式
            baseBind.themeMode.setText(Shaft.sSettings.getThemeType().toDisplayString(mContext));
            baseBind.themeModeRela.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    final int index = Shaft.sSettings.getThemeType().themeTypeIndex;
                    ThemeHelper.ThemeType[] THEME_MODES = new ThemeHelper.ThemeType[]{
                            DEFAULT_MODE,
                            LIGHT_MODE,
                            DARK_MODE
                    };
                    String[] THEME_NAME = new String[]{
                            THEME_MODES[0].toDisplayString(mContext),
                            THEME_MODES[1].toDisplayString(mContext),
                            THEME_MODES[2].toDisplayString(mContext)
                    };
                    new QMUIDialog.CheckableDialogBuilder(mActivity)
                            .setCheckedIndex(index)
                            .setSkinManager(QMUISkinManager.defaultInstance(mContext))
                            .addItems(THEME_NAME, new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface dialog, int which) {
                                    if (which != index) {
                                        Shaft.sSettings.setThemeType(((AppCompatActivity) mActivity), THEME_MODES[which]);
                                        baseBind.themeMode.setText(THEME_NAME[which]);
                                        Local.setSettings(Shaft.sSettings);
                                    }
                                    dialog.dismiss();
                                }
                            })
                            .show();
                }
            });

            // 主题色彩
            setThemeName();
            baseBind.colorSelectRela.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    Intent intent = new Intent(mContext, TemplateActivity.class);
                    intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "主题颜色");
                    startActivity(intent);
                }
            });

            baseBind.layoutMode.setText(Shaft.sSettings.isUseStaggeredLayout()
                    ? getString(R.string.layout_staggered) : getString(R.string.layout_linear));
            baseBind.layoutModeRela.setOnClickListener(v -> {
                String[] options = new String[]{
                        getString(R.string.layout_staggered),
                        getString(R.string.layout_linear)
                };
                int currentIndex = Shaft.sSettings.isUseStaggeredLayout() ? 0 : 1;
                new QMUIDialog.CheckableDialogBuilder(mActivity)
                        .setCheckedIndex(currentIndex)
                        .setSkinManager(QMUISkinManager.defaultInstance(mContext))
                        .addItems(options, (dialog, which) -> {
                            if (which != currentIndex) {
                                Shaft.sSettings.setUseStaggeredLayout(which == 0);
                                baseBind.layoutMode.setText(options[which]);
                                Local.setSettings(Shaft.sSettings);
                            }
                            dialog.dismiss();
                        })
                        .show();
            });

            baseBind.lineCount.setText(getString(R.string.string_349, Shaft.sSettings.getLineCount()));
            baseBind.lineCountRela.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    int index = 0;
                    if (Shaft.sSettings.getLineCount() == 3) {
                        index = 1;
                    } else if (Shaft.sSettings.getLineCount() == 4) {
                        index = 2;
                    }
                    String[] LINE_COUNT = new String[]{
                            getString(R.string.string_349, 2),
                            getString(R.string.string_349, 3),
                            getString(R.string.string_349, 4)
                    };
                    final int selectIndex = index;
                    new QMUIDialog.CheckableDialogBuilder(mActivity)
                            .setCheckedIndex(selectIndex)
                            .setSkinManager(QMUISkinManager.defaultInstance(mContext))
                            .addItems(LINE_COUNT, new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface dialog, int which) {
                                    if (which != selectIndex) {
                                        int lineCount = which + 2;
                                        Shaft.sSettings.setLineCount(lineCount);
                                        baseBind.lineCount.setText(getString(R.string.string_349, lineCount));
                                        Local.setSettings(Shaft.sSettings);
                                        Common.showToast(getString(R.string.please_restart_app), 2);
                                    }
                                    dialog.dismiss();
                                }
                            })
                            .show();
                }
            });

            // 首页底部页签顺序
            setOrderName();
            baseBind.orderSelect.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    final int index = Shaft.sSettings.getBottomBarOrder();
                    String[] ORDER_NAME = new String[]{
                            getString(R.string.string_343),
                            getString(R.string.string_344),
                            getString(R.string.string_345),
                            getString(R.string.string_346),
                            getString(R.string.string_347),
                            getString(R.string.string_348),
                    };
                    new QMUIDialog.CheckableDialogBuilder(mActivity)
                            .setCheckedIndex(index)
                            .setSkinManager(QMUISkinManager.defaultInstance(mContext))
                            .addItems(ORDER_NAME, new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface dialog, int which) {
                                    if (which == index) {
                                        Common.showLog("什么也不做");
                                    } else {
                                        Shaft.sSettings.setBottomBarOrder(which);
                                        baseBind.orderSelect.setText(ORDER_NAME[which]);
                                        Local.setSettings(Shaft.sSettings);
                                        Common.showToast(getString(R.string.please_restart_app));
                                    }
                                    dialog.dismiss();
                                }
                            })
                            .show();
                }
            });
            baseBind.bottomBarOrderRela.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    baseBind.orderSelect.performClick();
                }
            });

            // 语言
            baseBind.appLanguage.setText(currentLanguageDisplay());
            baseBind.appLanguageRela.setOnClickListener(v -> {
                // "跟随系统" 放在首位
                java.util.List<String> labels = new java.util.ArrayList<>();
                java.util.List<String> tags = new java.util.ArrayList<>();
                labels.add(getString(R.string.language_follow_system));
                tags.add(null);
                for (String tag : ceui.pixiv.i18n.AppLocales.INSTANCE.getSupportedTags()) {
                    labels.add(ceui.pixiv.i18n.AppLocales.INSTANCE.displayName(tag));
                    tags.add(tag);
                }
                int checkedIndex = 0; // default: follow system
                if (!ceui.pixiv.i18n.AppLocales.INSTANCE.isFollowingSystem()) {
                    String currentTag = ceui.pixiv.i18n.AppLocales.INSTANCE.currentLocale().toLanguageTag();
                    int idx = tags.indexOf(currentTag);
                    if (idx >= 0) checkedIndex = idx;
                }
                new QMUIDialog.CheckableDialogBuilder(getActivity())
                        .setCheckedIndex(checkedIndex)
                        .addItems(labels.toArray(new String[0]), (dialog, which) -> {
                            String tag = tags.get(which);
                            ceui.pixiv.i18n.AppLocales.INSTANCE.apply(tag);
                            baseBind.appLanguage.setText(labels.get(which));
                            Common.showToast(getString(R.string.string_428), 2);
                            dialog.dismiss();
                        })
                        .show();
            });
        }

        // 下载
        {
            // 以下行已被统一的"下载路径与文件名"设置页吸收——通过模板变量 / 条件块 /
            // 按 bucket 的存储后端表达，用户应去新页面调整。这里直接隐藏旧入口，
            // 避免两套并存互相覆盖。剩余保留的：长按下载开关、下载限制（WiFi）、
            // 自动点赞——这些与路径 / 文件名无关，是 UX / 网络行为开关。
            baseBind.r18DivideSaveRela.setVisibility(View.GONE);
            baseBind.aiDivideSaveRela.setVisibility(View.GONE);
            baseBind.saveForSeparateAuthorRela.setVisibility(View.GONE);
            baseBind.downloadWayRela.setVisibility(View.GONE);
            baseBind.singleIllustPath.setVisibility(View.GONE);
            baseBind.novelPathRela.setVisibility(View.GONE);

            baseBind.r18DivideSave.setChecked(Shaft.sSettings.isR18DivideSave());
            baseBind.r18DivideSave.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                    Shaft.sSettings.setR18DivideSave(isChecked);
                    Common.showToast(getString(R.string.string_428));
                    Local.setSettings(Shaft.sSettings);
                }
            });
            baseBind.r18DivideSaveRela.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    baseBind.r18DivideSave.performClick();
                }
            });

            // AI作品下载至单独的目录
            baseBind.aiDivideSave.setChecked(Shaft.sSettings.isAIDivideSave());
            baseBind.aiDivideSave.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                    Shaft.sSettings.setAIDivideSave(isChecked);
                    Common.showToast(getString(R.string.string_428));
                    Local.setSettings(Shaft.sSettings);
                }
            });
            baseBind.aiDivideSaveRela.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    baseBind.aiDivideSave.performClick();
                }
            });

            // 下载路径 / 文件名 —— 所有分目录 / 命名 / 存储位置的配置都收在这一个入口
            baseBind.fileNameS.setText(getString(R.string.download_path_title));
            baseBind.fileName.setText(getString(R.string.download_path_entry_desc));
            baseBind.fileNameRela.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    Intent intent = new Intent(mContext, TemplateActivity.class);
                    intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "下载路径与文件名");
                    startActivity(intent);
                }
            });

            // 下载内容信息头 —— 可视化勾选 / 拖拽排序小说 TXT 的元信息块
            baseBind.novelHeaderRela.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    Intent intent = new Intent(mContext, TemplateActivity.class);
                    intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "小说信息头");
                    startActivity(intent);
                }
            });

            // 默认小说下载格式
            final String[] NOVEL_FORMAT_NAMES = new String[]{
                    getString(R.string.option_always_ask),
                    getString(R.string.format_txt),
                    getString(R.string.format_markdown),
                    getString(R.string.format_epub),
                    getString(R.string.format_pdf)
            };
            final String[] NOVEL_FORMAT_VALUES = new String[]{"", "Txt", "Markdown", "Epub", "Pdf"};
            {
                int idx = 0;
                String cur = Shaft.sSettings.getDefaultNovelExportFormat();
                for (int i = 0; i < NOVEL_FORMAT_VALUES.length; i++) {
                    if (NOVEL_FORMAT_VALUES[i].equals(cur)) { idx = i; break; }
                }
                baseBind.defaultNovelFormat.setText(NOVEL_FORMAT_NAMES[idx]);
            }
            baseBind.defaultNovelFormatRela.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    int checkedIdx = 0;
                    String cur = Shaft.sSettings.getDefaultNovelExportFormat();
                    for (int i = 0; i < NOVEL_FORMAT_VALUES.length; i++) {
                        if (NOVEL_FORMAT_VALUES[i].equals(cur)) { checkedIdx = i; break; }
                    }
                    new QMUIDialog.CheckableDialogBuilder(mActivity)
                            .setCheckedIndex(checkedIdx)
                            .setSkinManager(QMUISkinManager.defaultInstance(mContext))
                            .addItems(NOVEL_FORMAT_NAMES, new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface dialog, int which) {
                                    Shaft.sSettings.setDefaultNovelExportFormat(NOVEL_FORMAT_VALUES[which]);
                                    baseBind.defaultNovelFormat.setText(NOVEL_FORMAT_NAMES[which]);
                                    Local.setSettings(Shaft.sSettings);
                                    dialog.dismiss();
                                }
                            })
                            .show();
                }
            });

            // 默认图片保存清晰度
            final String[] IMG_RES_NAMES = new String[]{
                    getString(R.string.resolution_original),
                    getString(R.string.resolution_large),
                    getString(R.string.resolution_medium),
                    getString(R.string.resolution_square_medium)
            };
            final String[] IMG_RES_VALUES = new String[]{
                    Params.IMAGE_RESOLUTION_ORIGINAL,
                    Params.IMAGE_RESOLUTION_LARGE,
                    Params.IMAGE_RESOLUTION_MEDIUM,
                    Params.IMAGE_RESOLUTION_SQUARE_MEDIUM
            };
            {
                int idx = 0;
                String cur = Shaft.sSettings.getDefaultImageResolution();
                if (!cur.isEmpty()) {
                    for (int i = 0; i < IMG_RES_VALUES.length; i++) {
                        if (IMG_RES_VALUES[i].equals(cur)) { idx = i; break; }
                    }
                }
                baseBind.defaultImageResolution.setText(IMG_RES_NAMES[idx]);
            }
            baseBind.defaultImageResolutionRela.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    int checkedIdx = 0;
                    String cur = Shaft.sSettings.getDefaultImageResolution();
                    if (!cur.isEmpty()) {
                        for (int i = 0; i < IMG_RES_VALUES.length; i++) {
                            if (IMG_RES_VALUES[i].equals(cur)) { checkedIdx = i; break; }
                        }
                    }
                    new QMUIDialog.CheckableDialogBuilder(mActivity)
                            .setCheckedIndex(checkedIdx)
                            .setSkinManager(QMUISkinManager.defaultInstance(mContext))
                            .addItems(IMG_RES_NAMES, new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface dialog, int which) {
                                    Shaft.sSettings.setDefaultImageResolution(IMG_RES_VALUES[which]);
                                    baseBind.defaultImageResolution.setText(IMG_RES_NAMES[which]);
                                    Local.setSettings(Shaft.sSettings);
                                    dialog.dismiss();
                                }
                            })
                            .show();
                }
            });

            //按作者保存到单独文件夹
            baseBind.saveForSeparateAuthor.setText(UserFolderNameUtil.getCurrentStatusName());
            baseBind.saveForSeparateAuthor.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    new QMUIDialog.CheckableDialogBuilder(mActivity)
                            .setCheckedIndex(Shaft.sSettings.getSaveForSeparateAuthorStatus())
                            .setSkinManager(QMUISkinManager.defaultInstance(mContext))
                            .addItems(UserFolderNameUtil.USER_FOLDER_NAME_NAMES, new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface dialog, int which) {
                                    if (which == Shaft.sSettings.getSaveForSeparateAuthorStatus()) {
                                        Common.showLog("什么也不做");
                                    } else {
                                        Shaft.sSettings.setSaveForSeparateAuthorStatus(which);
                                        baseBind.saveForSeparateAuthor.setText(UserFolderNameUtil.getCurrentStatusName());
                                        Local.setSettings(Shaft.sSettings);
                                    }
                                    dialog.dismiss();
                                }
                            })
                            .show();
                }
            });
            baseBind.saveForSeparateAuthorRela.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    baseBind.saveForSeparateAuthor.performClick();
                }
            });

            //插画详情长按下载
            baseBind.illustLongPressDownload.setChecked(Shaft.sSettings.isIllustLongPressDownload());
            baseBind.illustLongPressDownload.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                    Shaft.sSettings.setIllustLongPressDownload(isChecked);
                    Common.showToast(getString(R.string.please_restart_app));
                    Local.setSettings(Shaft.sSettings);
                }
            });
            baseBind.illustLongPressDownloadRela.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    baseBind.illustLongPressDownload.performClick();
                }
            });

            //下载限制类型
            final String[] DOWNLOAD_START_TYPE_NAMES = new String[]{
                    getString(DownloadLimitTypeUtil.DOWNLOAD_START_TYPE_IDS[0]),
                    getString(DownloadLimitTypeUtil.DOWNLOAD_START_TYPE_IDS[1]),
                    getString(DownloadLimitTypeUtil.DOWNLOAD_START_TYPE_IDS[2])
            };
            baseBind.downloadLimitType.setText(DOWNLOAD_START_TYPE_NAMES[DownloadLimitTypeUtil.getCurrentStatusIndex()]);
            baseBind.downloadLimitType.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    new QMUIDialog.CheckableDialogBuilder(mActivity)
                            .setCheckedIndex(Shaft.sSettings.getDownloadLimitType())
                            .setSkinManager(QMUISkinManager.defaultInstance(mContext))
                            .addItems(DOWNLOAD_START_TYPE_NAMES, new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface dialog, int which) {
                                    if (which == Shaft.sSettings.getDownloadLimitType()) {
                                        Common.showLog("什么也不做");
                                    } else {
                                        Shaft.sSettings.setDownloadLimitType(which);
                                        baseBind.downloadLimitType.setText(DOWNLOAD_START_TYPE_NAMES[DownloadLimitTypeUtil.getCurrentStatusIndex()]);
                                        Common.showToast(getString(R.string.string_428));
                                        Local.setSettings(Shaft.sSettings);
                                    }
                                    dialog.dismiss();
                                }
                            })
                            .show();
                }
            });
            baseBind.downloadLimitTypeRela.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    baseBind.downloadLimitType.performClick();
                }
            });

            // 下载模式
            String[] downloadWays = new String[]{
                    getString(R.string.string_363),
                    getString(R.string.string_364)
            };
            baseBind.downloadWay.setText(downloadWays[Shaft.sSettings.getDownloadWay()]);
            baseBind.downloadWayRela.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    new QMUIDialog.CheckableDialogBuilder(mActivity)
                            .setCheckedIndex(Shaft.sSettings.getDownloadWay())
                            .setSkinManager(QMUISkinManager.defaultInstance(mContext))
                            .addItems(downloadWays, new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface dialog, int which) {
                                    if (which == Shaft.sSettings.getDownloadWay()) {
                                        Common.showLog("什么也不做");
                                    } else {
                                        Shaft.sSettings.setDownloadWay(which);
                                        baseBind.downloadWay.setText(downloadWays[which]);
                                        Local.setSettings(Shaft.sSettings);
                                        updateIllustPathUI();
                                    }
                                    dialog.dismiss();
                                }
                            })
                            .show();
                }
            });

            updateIllustPathUI();
            if(mActivity instanceof BaseActivity){
                ((BaseActivity)mActivity).setFeedBack(this::updateIllustPathUI);
            }
            baseBind.singleIllustPath.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (Shaft.sSettings.getDownloadWay() == 0 && Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                        Common.showToast(getString(R.string.string_329), true);
                    } else {
                        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
                        if (!TextUtils.isEmpty(Shaft.sSettings.getRootPathUri()) &&
                                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            Uri start = Uri.parse(Shaft.sSettings.getRootPathUri());
                            intent.putExtra(EXTRA_INITIAL_URI, start);
                        }
                        mActivity.startActivityForResult(intent, BaseActivity.ASK_URI);
                    }
                }
            });

            baseBind.novelPath.setText(Settings.FILE_PATH_NOVEL);
            baseBind.novelPathRela.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    Common.showToast(getString(R.string.string_374), true);
                }
            });
        }

        // 个性化
        {
            baseBind.showLikeButton.setChecked(Shaft.sSettings.isPrivateStar());
            baseBind.showLikeButton.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                    Shaft.sSettings.setPrivateStar(isChecked);
                    Common.showToast(getString(R.string.string_428), 2);
                    Local.setSettings(Shaft.sSettings);
                }
            });
            baseBind.showLikeButtonRela.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    baseBind.showLikeButton.performClick();
                }
            });

            baseBind.showNovelCardTags.setChecked(Shaft.sSettings.isShowNovelCardTags());
            baseBind.showNovelCardTags.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                    Shaft.sSettings.setShowNovelCardTags(isChecked);
                    Common.showToast(getString(R.string.string_428));
                    Local.setSettings(Shaft.sSettings);
                }
            });
            baseBind.showNovelCardTagsRela.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    baseBind.showNovelCardTags.performClick();
                }
            });

            baseBind.hideStarBar.setChecked(Shaft.sSettings.isHideStarButtonAtMyCollection());
            baseBind.hideStarBar.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                    Shaft.sSettings.setHideStarButtonAtMyCollection(isChecked);
                    Common.showToast(getString(R.string.string_428));
                    Local.setSettings(Shaft.sSettings);
                }
            });
            baseBind.hideStarBarRela.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    baseBind.hideStarBar.performClick();
                }
            });

            baseBind.selectAllTag.setChecked(Shaft.sSettings.isStarWithTagSelectAll());
            baseBind.selectAllTag.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                    Shaft.sSettings.setStarWithTagSelectAll(isChecked);
                    Common.showToast(getString(R.string.string_428));
                    Local.setSettings(Shaft.sSettings);
                }
            });
            baseBind.selectAllTagRela.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    baseBind.selectAllTag.performClick();
                }
            });

            String[] transformerNames = PageTransformerHelper.getTransformerNames();
            baseBind.transformType.setText(transformerNames[PageTransformerHelper.getCurrentTransformerIndex()]);
            baseBind.transformTypeRela.setOnClickListener(new View.OnClickListener(){
                @Override
                public void onClick(View v) {
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
                            .show();
                }
            });

            baseBind.showRelatedWhenStar.setChecked(Shaft.sSettings.isShowRelatedWhenStar());
            baseBind.showRelatedWhenStar.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                    Shaft.sSettings.setShowRelatedWhenStar(isChecked);
                    Common.showToast(getString(R.string.please_restart_app));
                    Local.setSettings(Shaft.sSettings);
                }
            });
            baseBind.showRelatedWhenStarRela.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    baseBind.showRelatedWhenStar.performClick();
                }
            });

            baseBind.downloadAutoPostLike.setChecked(Shaft.sSettings.isAutoPostLikeWhenDownload());
            baseBind.downloadAutoPostLike.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                    Shaft.sSettings.setAutoPostLikeWhenDownload(isChecked);
                    Common.showToast(getString(R.string.string_428));
                    Local.setSettings(Shaft.sSettings);
                }
            });
            baseBind.downloadAutoPostLikeRela.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    baseBind.downloadAutoPostLike.performClick();
                }
            });

            baseBind.autoFollowAfterStar.setChecked(Shaft.sSettings.isAutoFollowAfterStar());
            baseBind.autoFollowAfterStar.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                    Shaft.sSettings.setAutoFollowAfterStar(isChecked);
                    Common.showToast(getString(R.string.string_428));
                    Local.setSettings(Shaft.sSettings);
                }
            });
            baseBind.autoFollowAfterStarRela.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    baseBind.autoFollowAfterStar.performClick();
                }
            });

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
            baseBind.illustDetailKeepScreenOnRela.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    baseBind.illustDetailKeepScreenOn.performClick();
                }
            });

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
        }

        // AI 超分辨率模型
        {
            ceui.pixiv.ui.upscale.UpscaleModel saved = ceui.pixiv.ui.upscale.ModelPickerDialog.Companion.getSavedModel();
            baseBind.defaultUpscaleModel.setText(saved != null ? saved.getDisplayName() : getString(R.string.string_not_set));
            baseBind.defaultUpscaleModelRela.setOnClickListener(v -> {
                ceui.pixiv.ui.upscale.ModelPickerDialog.Companion.show(getChildFragmentManager(), model -> {
                    Shaft.sSettings.setDefaultUpscaleModel(model.name());
                    Local.setSettings(Shaft.sSettings);
                    baseBind.defaultUpscaleModel.setText(model.getDisplayName());
                    return kotlin.Unit.INSTANCE;
                });
            });
        }

        // AI 抠图模型
        {
            ceui.pixiv.ui.upscale.RembgModel savedRembg = ceui.pixiv.ui.upscale.RembgModelPickerDialog.Companion.getSavedModel();
            baseBind.defaultRembgModel.setText(savedRembg != null ? savedRembg.getDisplayName() : getString(R.string.string_not_set));
            baseBind.defaultRembgModelRela.setOnClickListener(v -> {
                if (getChildFragmentManager().findFragmentByTag("RembgModelPickerDialog") != null) return;
                ceui.pixiv.ui.upscale.RembgModelPickerDialog dialog = new ceui.pixiv.ui.upscale.RembgModelPickerDialog();
                dialog.setOnModelSelected(model -> {
                    Shaft.sSettings.setDefaultRembgModel(model.name());
                    Local.setSettings(Shaft.sSettings);
                    baseBind.defaultRembgModel.setText(model.getDisplayName());
                    return kotlin.Unit.INSTANCE;
                });
                dialog.show(getChildFragmentManager(), "RembgModelPickerDialog");
            });
        }

        // OCR 模型
        {
            baseBind.ocrModelRela.setOnClickListener(v -> {
                Intent intent = new Intent(mContext, ceui.lisa.activities.TemplateActivity.class);
                intent.putExtra(ceui.lisa.activities.TemplateActivity.EXTRA_FRAGMENT, "漫画OCR模型下载");
                intent.putExtra("manga_ocr_model_name", ceui.pixiv.ui.translate.MangaOcrModel.MANGA_OCR_BASE.name());
                startActivity(intent);
            });
        }

        // 翻译模型 (Sakura — ACG 日中翻译，漫画翻译和翻译 demo 都用它)
        {
            baseBind.translationModelRela.setOnClickListener(v -> {
                Intent intent = new Intent(mContext, ceui.lisa.activities.TemplateActivity.class);
                intent.putExtra(ceui.lisa.activities.TemplateActivity.EXTRA_FRAGMENT, "Sakura翻译模型下载");
                intent.putExtra("sakura_model_name", ceui.pixiv.ui.translate.SakuraModel.SAKURA_1_5B.name());
                startActivity(intent);
            });
        }

        // 缓存
        {
            baseBind.imageCacheSize.setText(FileUtils.getSize(LegacyFile.imageCacheFolder(mContext)));
            baseBind.clearImageCache.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    FileUtils.deleteAllInDir(LegacyFile.imageCacheFolder(mContext));
                    Common.showToast(getString(R.string.success_clearImageCache));
                    baseBind.imageCacheSize.setText(FileUtils.getSize(LegacyFile.imageCacheFolder(mContext)));
                }
            });

            baseBind.gifCacheSize.setText(FileUtils.getSize(LegacyFile.gifCacheFolder(mContext)));
            baseBind.clearGifCache.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    FileUtils.deleteAllInDir(LegacyFile.gifCacheFolder(mContext));
                    Common.showToast(getString(R.string.success_clearGifCache), 2);
                    baseBind.gifCacheSize.setText(FileUtils.getSize(LegacyFile.gifCacheFolder(mContext)));
                }
            });
        }

        // 备份与还原
        {
            baseBind.backupRela.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    QMUIDialog.CheckBoxMessageDialogBuilder builder = new QMUIDialog.CheckBoxMessageDialogBuilder(getActivity());
                    builder
                            .setTitle(getString(R.string.string_420))
                            .setMessage(getString(R.string.string_423))
                            .setSkinManager(QMUISkinManager.defaultInstance(mContext))
                            .addAction(getString(R.string.string_187), new QMUIDialogAction.ActionListener() {
                                @Override
                                public void onClick(QMUIDialog dialog, int index) {
                                    dialog.dismiss();
                                }
                            })
                            .addAction(R.string.sure, new QMUIDialogAction.ActionListener() {
                                @Override
                                public void onClick(QMUIDialog dialog, int index) {
                                    String backupString = BackupUtils.getBackupString(mContext, builder.isChecked());
                                    IllustDownload.downloadBackupFile((BaseActivity<?>) mActivity, "Shaft-Backup.json", backupString, new Callback<Uri>() {
                                        @Override
                                        public void doSomething(Uri t) {
                                            Common.showToast(getString(R.string.backup_success) + Settings.FILE_PATH_BACKUP);
                                        }
                                    });
                                    dialog.dismiss();
                                }
                            })
                            .create()
                            .show();
                }
            });

            baseBind.restoreRela.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                    intent.addCategory(Intent.CATEGORY_OPENABLE);//必须
                    intent.setType("*/*");//必须
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        Uri backupFileUri = Uri.parse("content://com.android.externalstorage.documents/document/primary:"+"Download%2fShaftBackups%2fShaft-Backup.json");
//                        Common.showToast(backupFileUri);
                        intent.putExtra(EXTRA_INITIAL_URI, backupFileUri);
                    }
                    startActivityForResult(intent, Params.REQUEST_CODE_CHOOSE);
                }
            });
        }

        baseBind.refreshLayout.setRefreshHeader(new FalsifyHeader(mContext));
        baseBind.refreshLayout.setRefreshFooter(new FalsifyFooter(mContext));

        if (!Common.isAndroidQ()) {
            new RxPermissions(this)
                    .requestEachCombined(
                            Manifest.permission.WRITE_EXTERNAL_STORAGE
                    )
                    .subscribe(permission -> {
                        if (!permission.granted) {
                            Common.showToast(getString(R.string.access_denied));
                            finish();
                        }
                    });
        }
    }

    @Override
    public SmartRefreshLayout getSmartRefreshLayout() {
        return baseBind.refreshLayout;
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
    }

    private void setOrderName() {
        final int index = Shaft.sSettings.getBottomBarOrder();
        String[] ORDER_NAME = new String[]{
                getString(R.string.string_343),
                getString(R.string.string_344),
                getString(R.string.string_345),
                getString(R.string.string_346),
                getString(R.string.string_347),
                getString(R.string.string_348),
        };
        baseBind.orderSelect.setText(ORDER_NAME[index]);
    }

    private void setThemeName() {
        final int index = Shaft.sSettings.getThemeIndex();
        baseBind.colorSelect.setText(getString(FragmentColors.COLOR_NAME_CODES[index]));
    }

    private void updateIllustPathUI(){
        if (Shaft.sSettings.getDownloadWay() == 1) {
            try {
                baseBind.illustPath.setText(URLDecoder.decode(Shaft.sSettings.getRootPathUri(), "utf-8"));
            } catch (UnsupportedEncodingException e) {
                e.printStackTrace();
            }
        } else {
            baseBind.illustPath.setText(Shaft.sSettings.getIllustPath());
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        updateModelStatus();
    }

    private void updateModelStatus() {
        if (baseBind == null) return;
        boolean ocrReady = ceui.pixiv.ui.translate.MangaOcrModelManager.INSTANCE.isModelReady(
                mContext, ceui.pixiv.ui.translate.MangaOcrModel.MANGA_OCR_BASE);
        baseBind.ocrModelStatus.setText(ocrReady
                ? ceui.pixiv.ui.translate.MangaOcrModel.MANGA_OCR_BASE.getDisplayName()
                : getString(R.string.string_model_not_ready, "91MB"));

        boolean sakuraReady = ceui.pixiv.ui.translate.SakuraModelManager.INSTANCE.isModelReady(
                mContext, ceui.pixiv.ui.translate.SakuraModel.SAKURA_1_5B);
        baseBind.translationModelStatus.setText(sakuraReady
                ? ceui.pixiv.ui.translate.SakuraModel.SAKURA_1_5B.getDisplayName()
                : getString(R.string.string_model_not_ready,
                        ceui.pixiv.ui.translate.SakuraModel.SAKURA_1_5B.getSizeLabel()));
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, @Nullable @org.jetbrains.annotations.Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == Params.REQUEST_CODE_CHOOSE && resultCode == RESULT_OK && data != null) {
            try {
                Uri uri = data.getData();
                String fileString = new String(UriUtils.uri2Bytes(uri));
                boolean restoreResult = BackupUtils.restoreBackups(mContext, fileString);
                Common.showToast(restoreResult ? getString(R.string.restore_success) : getString(R.string.restore_failed));
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    private String currentLanguageDisplay() {
        if (ceui.pixiv.i18n.AppLocales.INSTANCE.isFollowingSystem()) {
            return getString(R.string.language_follow_system);
        }
        Locale loc = ceui.pixiv.i18n.AppLocales.INSTANCE.currentLocale();
        return ceui.pixiv.i18n.AppLocales.INSTANCE.displayName(loc.toLanguageTag());
    }
}
