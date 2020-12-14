package ceui.lisa.fragments;

import android.content.DialogInterface;
import android.content.Intent;
import android.text.TextUtils;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.blankj.utilcode.util.LanguageUtils;
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
import ceui.lisa.helper.ThemeHelper;
import ceui.lisa.utils.Common;
import ceui.lisa.utils.Local;
import ceui.lisa.utils.Params;

import static ceui.lisa.fragments.FragmentFilter.ALL_SIZE_VALUE;
import static ceui.lisa.utils.Settings.ALL_LANGUAGE;


public class FragmentSettings extends SwipeFragment<FragmentSettingsBinding> {

    private boolean freshPath = false;

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
                        .setSkinManager(QMUISkinManager.defaultInstance(getContext()))
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

        baseBind.singleDownloadTask.setChecked(Shaft.sSettings.isSingleDownloadTask());
        baseBind.singleDownloadTask.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                Common.showToast("设置成功", 2);
                Local.setSettings(Shaft.sSettings);
            }
        });
        baseBind.singleDownloadTaskRela.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                baseBind.singleDownloadTask.performClick();
            }
        });

        baseBind.showLikeButton.setChecked(Shaft.sSettings.isShowLikeButton());
        baseBind.showLikeButton.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (isChecked) {
                    Shaft.sSettings.setShowLikeButton(true);
                } else {
                    Shaft.sSettings.setShowLikeButton(false);
                }
                Common.showToast("重启APP生效", 2);
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

        baseBind.relatedNoLimit.setChecked(Shaft.sSettings.isRelatedIllustNoLimit());
        baseBind.relatedNoLimit.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (isChecked) {
                    Shaft.sSettings.setRelatedIllustNoLimit(true);
                } else {
                    Shaft.sSettings.setRelatedIllustNoLimit(false);
                }
                Common.showToast("设置成功", 2);
                Local.setSettings(Shaft.sSettings);
            }
        });
        baseBind.relatedNoLimitRela.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                baseBind.relatedNoLimit.performClick();
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

        baseBind.firstDetailOrigin.setChecked(Shaft.sSettings.isFirstImageSize());
        baseBind.firstDetailOrigin.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (isChecked) {
                    Shaft.sSettings.setFirstImageSize(true);
                } else {
                    Shaft.sSettings.setFirstImageSize(false);
                }
                Common.showToast("设置成功", 2);
                Local.setSettings(Shaft.sSettings);
            }
        });
        baseBind.firstDetailOriginRela.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                baseBind.firstDetailOrigin.performClick();
            }
        });

        setPath();
        baseBind.illustPath.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (Common.isAndroidQ()) {
                    freshPath = true;
                    mActivity.startActivityForResult(
                            new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), BaseActivity.ASK_URI);
                } else {
                    Common.showToast(getString(R.string.string_329), true);
                }
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
                        .setSkinManager(QMUISkinManager.defaultInstance(getContext()))
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
        baseBind.colorSelectRela.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(mContext, TemplateActivity.class);
                intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "主题颜色");
                startActivity(intent);
            }
        });

        baseBind.clearGifCache.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {

            }
        });
        baseBind.refreshLayout.setRefreshHeader(new FalsifyHeader(mContext));
        baseBind.refreshLayout.setRefreshFooter(new FalsifyFooter(mContext));
    }

    @Override
    public SmartRefreshLayout getSmartRefreshLayout() {
        return baseBind.refreshLayout;
    }

    @Override
    public void onResume() {
        super.onResume();
        if (freshPath) {
            setPath();
            freshPath = false;
        }
    }

    private void setPath() {
        try {
            if (Common.isAndroidQ()) {
                if (!TextUtils.isEmpty(Shaft.sSettings.getRootPathUri())) {
                    baseBind.illustPath.setText(URLDecoder.decode(Shaft.sSettings.getRootPathUri(), "utf-8"));
                } else {
                    baseBind.illustPath.setText(R.string.string_330);
                }
            } else {
                baseBind.illustPath.setText(Shaft.sSettings.getIllustPath());
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
