package ceui.lisa.fragments;

import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.text.TextUtils;
import android.view.View;
import android.widget.CompoundButton;

import androidx.appcompat.app.AppCompatActivity;

import com.blankj.utilcode.util.FileUtils;
import com.blankj.utilcode.util.LanguageUtils;
import com.google.firebase.analytics.FirebaseAnalytics;
import com.qmuiteam.qmui.skin.QMUISkinManager;
import com.qmuiteam.qmui.widget.dialog.QMUIDialog;
import com.qmuiteam.qmui.widget.dialog.QMUIDialogAction;
import com.scwang.smartrefresh.layout.SmartRefreshLayout;
import com.scwang.smartrefresh.layout.footer.FalsifyFooter;
import com.scwang.smartrefresh.layout.header.FalsifyHeader;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.util.Locale;

import ceui.lisa.R;
import ceui.lisa.activities.BaseActivity;
import ceui.lisa.activities.Shaft;
import ceui.lisa.activities.TemplateActivity;
import ceui.lisa.databinding.FragmentSettingsBinding;
import ceui.lisa.file.LegacyFile;
import ceui.lisa.helper.PageTransformerHelper;
import ceui.lisa.helper.ThemeHelper;
import ceui.lisa.utils.Common;
import ceui.lisa.utils.Local;
import ceui.lisa.utils.Params;
import ceui.lisa.utils.PixivSearchParamUtil;
import ceui.lisa.utils.Settings;

import static android.provider.DocumentsContract.EXTRA_INITIAL_URI;
import static ceui.lisa.helper.ThemeHelper.ThemeType.DARK_MODE;
import static ceui.lisa.helper.ThemeHelper.ThemeType.DEFAULT_MODE;
import static ceui.lisa.helper.ThemeHelper.ThemeType.LIGHT_MODE;
import static ceui.lisa.utils.Settings.ALL_LANGUAGE;


public class FragmentSettings extends SwipeFragment<FragmentSettingsBinding> {

    @Override
    public void initLayout() {
        mLayoutID = R.layout.fragment_settings;
    }

    @Override
    protected void initData() {
        baseBind.toolbar.setNavigationOnClickListener(view -> mActivity.finish());
        Common.animate(baseBind.parentLinear);

        baseBind.loginOut.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                new QMUIDialog.CheckBoxMessageDialogBuilder(getActivity())
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
                                Common.logOut(mContext);
                                mActivity.finish();
                                dialog.dismiss();
                            }
                        })
                        .create()
                        .show();
            }
        });

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

        baseBind.saveHistory.setChecked(Shaft.sSettings.isSaveViewHistory());
        baseBind.saveHistory.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (isChecked) {
                    Shaft.sSettings.setSaveViewHistory(true);
                } else {
                    Shaft.sSettings.setSaveViewHistory(false);
                }
                Common.showToast("设置成功", 2);
                Local.setSettings(Shaft.sSettings);
            }
        });
        baseBind.saveHistoryRela.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                baseBind.saveHistory.performClick();
            }
        });

        baseBind.showLikeButton.setChecked(Shaft.sSettings.isPrivateStar());
        baseBind.showLikeButton.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                Shaft.sSettings.setPrivateStar(isChecked);
                Common.showToast("设置成功", 2);
                Local.setSettings(Shaft.sSettings);
            }
        });
        baseBind.showLikeButtonRela.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                baseBind.showLikeButton.performClick();
            }
        });

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

        baseBind.illustDetailUserNew.setChecked(Shaft.sSettings.isUseFragmentIllust());
        baseBind.illustDetailUserNew.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (isChecked) {
                    Shaft.sSettings.setUseFragmentIllust(true);
                } else {
                    Shaft.sSettings.setUseFragmentIllust(false);
                }
                Common.showToast("设置成功", 2);
                Local.setSettings(Shaft.sSettings);
            }
        });
        baseBind.illustDetailUserNewRela.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                baseBind.illustDetailUserNew.performClick();
            }
        });

        baseBind.userNewUser.setChecked(Shaft.sSettings.isUseNewUserPage());
        baseBind.userNewUser.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                Shaft.sSettings.setUseNewUserPage(isChecked);
                Common.showToast("设置成功", 2);
                Local.setSettings(Shaft.sSettings);
            }
        });
        baseBind.userNewUserRela.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                baseBind.userNewUser.performClick();
            }
        });

        baseBind.deleteStarIllust.setChecked(Shaft.sSettings.isDeleteStarIllust());
        baseBind.deleteStarIllust.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (isChecked) {
                    Shaft.sSettings.setDeleteStarIllust(true);
                } else {
                    Shaft.sSettings.setDeleteStarIllust(false);
                }
                Common.showToast("设置成功", 2);
                Local.setSettings(Shaft.sSettings);
            }
        });
        baseBind.deleteStarIllustRela.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                baseBind.deleteStarIllust.performClick();
            }
        });

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

        baseBind.autoDns.setChecked(Shaft.sSettings.isAutoFuckChina());
        baseBind.autoDns.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (isChecked) {
                    Shaft.sSettings.setAutoFuckChina(true);
                } else {
                    Shaft.sSettings.setAutoFuckChina(false);
                }
                Common.showToast("设置成功", 2);
                Local.setSettings(Shaft.sSettings);
            }
        });
        baseBind.fuckChinaRela.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                baseBind.autoDns.performClick();
            }
        });

        baseBind.firstDetailOrigin.setChecked(Shaft.sSettings.isUsePixivCat());
        baseBind.firstDetailOrigin.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (isChecked) {
                    Shaft.sSettings.setUsePixivCat(true);
                } else {
                    Shaft.sSettings.setUsePixivCat(false);
                }
                Common.showToast("设置成功");
                Local.setSettings(Shaft.sSettings);
            }
        });
        baseBind.firstDetailOriginRela.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                baseBind.firstDetailOrigin.performClick();
            }
        });

        baseBind.r18DivideSave.setChecked(Shaft.sSettings.isR18DivideSave());
        baseBind.r18DivideSave.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                Shaft.sSettings.setR18DivideSave(isChecked);
                Common.showToast("设置成功");
                Local.setSettings(Shaft.sSettings);
            }
        });
        baseBind.r18DivideSaveRela.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                baseBind.r18DivideSave.performClick();
            }
        });

        baseBind.hideStarBar.setChecked(Shaft.sSettings.isHideStarButtonAtMyCollection());
        baseBind.hideStarBar.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                Shaft.sSettings.setHideStarButtonAtMyCollection(isChecked);
                Common.showToast("设置成功");
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
                Common.showToast("设置成功");
                Local.setSettings(Shaft.sSettings);
            }
        });
        baseBind.selectAllTagRela.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                baseBind.selectAllTag.performClick();
            }
        });

        //是否显示原图
        baseBind.showOriginalImage.setChecked(Shaft.sSettings.isShowOriginalImage());
        baseBind.showOriginalImage.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                Shaft.sSettings.setShowOriginalImage(isChecked);
                Common.showToast("设置成功");
                Local.setSettings(Shaft.sSettings);
            }
        });
        baseBind.showOriginalImageRela.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                baseBind.showOriginalImage.performClick();
            }
        });

        updateIllustPathUI();
        if(mActivity instanceof BaseActivity){
            ((BaseActivity)mActivity).setFeedBack(this::updateIllustPathUI);
        }
        baseBind.singleIllustPath.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (Shaft.sSettings.getDownloadWay() == 0) {
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

        baseBind.fuckChina.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(mContext, TemplateActivity.class);
                intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "网页链接");
                intent.putExtra(Params.URL, "https://github.com/Notsfsssf/Pix-EzViewer");
                intent.putExtra(Params.TITLE, "PxEz项目主页");
                startActivity(intent);
            }
        });

        final String searchFilter = Shaft.sSettings.getSearchFilter();
        baseBind.searchFilter.setText(PixivSearchParamUtil.getSizeName(searchFilter));
        baseBind.searchFilterRela.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                new QMUIDialog.CheckableDialogBuilder(mContext)
                        .setCheckedIndex(PixivSearchParamUtil.getSizeIndex(searchFilter))
                        .setSkinManager(QMUISkinManager.defaultInstance(mContext))
                        .addItems(PixivSearchParamUtil.ALL_SIZE_NAME, new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                Shaft.sSettings.setSearchFilter(PixivSearchParamUtil.ALL_SIZE_VALUE[which]);
                                Common.showToast("设置成功", 2);
                                Local.setSettings(Shaft.sSettings);
                                baseBind.searchFilter.setText(PixivSearchParamUtil.ALL_SIZE_NAME[which]);
                                dialog.dismiss();
                            }
                        })
                        .create()
                        .show();
            }
        });

        baseBind.appLanguage.setText(Shaft.sSettings.getAppLanguage());
        baseBind.appLanguageRela.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                new QMUIDialog.CheckableDialogBuilder(getActivity())
                        .addItems(ALL_LANGUAGE, new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                Shaft.sSettings.setAppLanguage(ALL_LANGUAGE[which]);
                                baseBind.appLanguage.setText(ALL_LANGUAGE[which]);
                                Common.showToast("设置成功", 2);
                                Local.setSettings(Shaft.sSettings);
                                if (which == 0) {
                                    LanguageUtils.applyLanguage(Locale.SIMPLIFIED_CHINESE, "");
                                } else if (which == 1) {
                                    LanguageUtils.applyLanguage(Locale.JAPAN, "");
                                } else if (which == 2) {
                                    LanguageUtils.applyLanguage(Locale.US, "");
                                } else if (which == 3) {
                                    LanguageUtils.applyLanguage(Locale.TRADITIONAL_CHINESE, "");
                                } else if (which == 4) {
                                    LanguageUtils.applyLanguage(new Locale("RU", "ru", ""), "");
                                } else if (which == 5) {
                                    LanguageUtils.applyLanguage(Locale.KOREA, "");
                                }
                                dialog.dismiss();
                            }
                        })
                        .show();
            }
        });

        baseBind.fileNameRela.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(mContext, TemplateActivity.class);
                intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "修改命名方式");
                startActivity(intent);
            }
        });

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
                                if (which == index) {
                                    Common.showLog("什么也不做");
                                } else {
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

        baseBind.globalSwipeBack.setChecked(Shaft.sSettings.isGlobalSwipeBack());
        baseBind.globalSwipeBack.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                Shaft.sSettings.setGlobalSwipeBack(isChecked);
                Common.showToast(getString(R.string.please_restart_app));
                Local.setSettings(Shaft.sSettings);
            }
        });
        baseBind.globalSwipeBackRela.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                baseBind.globalSwipeBack.performClick();
            }
        });

        baseBind.isFirebaseEnable.setChecked(Shaft.sSettings.isFirebaseEnable());
        baseBind.isFirebaseEnable.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                Shaft.sSettings.setFirebaseEnable(isChecked);
                Local.setSettings(Shaft.sSettings);
                Common.showToast("设置成功", 2);
                FirebaseAnalytics.getInstance(mContext).setAnalyticsCollectionEnabled(isChecked);
            }
        });

        baseBind.filterComment.setChecked(Shaft.sSettings.isFilterComment());
        baseBind.filterComment.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (isChecked) {
                    Shaft.sSettings.setFilterComment(true);
                } else {
                    Shaft.sSettings.setFilterComment(false);
                }
                Common.showToast("设置成功", 2);
                Local.setSettings(Shaft.sSettings);
            }
        });
        baseBind.filterCommentRela.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                baseBind.filterComment.performClick();
            }
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
                        .setCheckedIndex(index)
                        .setSkinManager(QMUISkinManager.defaultInstance(mContext))
                        .addItems(LINE_COUNT, new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                if (which == selectIndex) {
                                    Common.showLog("什么也不做");
                                } else {
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


        setThemeName();
        baseBind.colorSelectRela.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(mContext, TemplateActivity.class);
                intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "主题颜色");
                startActivity(intent);
            }
        });

        baseBind.imageCacheSize.setText(FileUtils.getSize(new LegacyFile().imageCacheFolder(mContext)));
        baseBind.clearImageCache.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                FileUtils.deleteAllInDir(new LegacyFile().imageCacheFolder(mContext));
                Common.showToast("图片缓存清除成功！");
                baseBind.imageCacheSize.setText(FileUtils.getSize(new LegacyFile().imageCacheFolder(mContext)));
            }
        });

        baseBind.gifCacheSize.setText(FileUtils.getSize(new LegacyFile().gifCacheFolder(mContext)));
        baseBind.clearGifCache.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                FileUtils.deleteAllInDir(new LegacyFile().gifCacheFolder(mContext));
                Common.showToast("GIF缓存清除成功！");
                baseBind.gifCacheSize.setText(FileUtils.getSize(new LegacyFile().gifCacheFolder(mContext)));
            }
        });
        baseBind.refreshLayout.setRefreshHeader(new FalsifyHeader(mContext));
        baseBind.refreshLayout.setRefreshFooter(new FalsifyFooter(mContext));
    }

    @Override
    public SmartRefreshLayout getSmartRefreshLayout() {
        return baseBind.refreshLayout;
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
        baseBind.colorSelect.setText(FragmentColors.COLOR_NAMES[index]);
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
}
