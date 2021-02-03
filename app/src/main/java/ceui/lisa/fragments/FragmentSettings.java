package ceui.lisa.fragments;

import android.content.DialogInterface;
import android.content.Intent;
import android.view.View;
import android.widget.CompoundButton;

import androidx.appcompat.app.AppCompatActivity;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.blankj.utilcode.util.FileUtils;
import com.blankj.utilcode.util.LanguageUtils;
import com.qmuiteam.qmui.skin.QMUISkinManager;
import com.qmuiteam.qmui.widget.dialog.QMUIDialog;
import com.qmuiteam.qmui.widget.dialog.QMUIDialogAction;
import com.scwang.smartrefresh.layout.SmartRefreshLayout;
import com.scwang.smartrefresh.layout.footer.FalsifyFooter;
import com.scwang.smartrefresh.layout.header.FalsifyHeader;

import java.util.Locale;

import ceui.lisa.R;
import ceui.lisa.activities.Shaft;
import ceui.lisa.activities.TemplateActivity;
import ceui.lisa.core.Manager;
import ceui.lisa.databinding.FragmentSettingsBinding;
import ceui.lisa.feature.HostManager;
import ceui.lisa.file.LegacyFile;
import ceui.lisa.helper.ThemeHelper;
import ceui.lisa.utils.Common;
import ceui.lisa.utils.Local;
import ceui.lisa.utils.Params;

import static ceui.lisa.fragments.FragmentFilter.ALL_SIZE_VALUE;
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


        baseBind.illustPath.setText(Shaft.sSettings.getIllustPath());
        baseBind.singleIllustPath.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Common.showToast(getString(R.string.string_329), true);
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

        baseBind.searchFilter.setText(Shaft.sSettings.getSearchFilter());
        baseBind.searchFilterRela.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String[] ALL_SIZE = new String[]{
                        getString(R.string.string_289),
                        getString(R.string.string_290),
                        getString(R.string.string_291),
                        getString(R.string.string_292),
                        getString(R.string.string_293),
                        getString(R.string.string_294),
                        getString(R.string.string_295),
                        getString(R.string.string_296),
                        getString(R.string.string_297)
                };
                new QMUIDialog.CheckableDialogBuilder(mContext)
                        .setSkinManager(QMUISkinManager.defaultInstance(mContext))
                        .addItems(ALL_SIZE, new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                Shaft.sSettings.setSearchFilter(ALL_SIZE_VALUE[which]);
                                Common.showToast("设置成功", 2);
                                Local.setSettings(Shaft.sSettings);
                                baseBind.searchFilter.setText(ALL_SIZE[which]);
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

        baseBind.themeMode.setText(Shaft.sSettings.getThemeType());
        baseBind.themeModeRela.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                final int index = ThemeHelper.getThemeType(mContext);
                String[] THEME_NAME = new String[]{
                        getString(R.string.string_298),
                        getString(R.string.string_299),
                        getString(R.string.string_300)
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
                                    Shaft.sSettings.setThemeType(((AppCompatActivity) mActivity), THEME_NAME[which]);
                                    baseBind.themeMode.setText(THEME_NAME[which]);
                                    Local.setSettings(Shaft.sSettings);
                                }
                                dialog.dismiss();
                            }
                        })
                        .show();
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
                        "2列",
                        "3列",
                        "4列"
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
                                    Common.showToast("重启APP生效", 2);
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
}
