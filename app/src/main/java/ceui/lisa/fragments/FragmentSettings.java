package ceui.lisa.fragments;

import android.app.Activity;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Environment;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.LinearLayout;

import androidx.appcompat.app.AppCompatActivity;

import com.blankj.utilcode.util.LanguageUtils;
import com.facebook.rebound.SimpleSpringListener;
import com.facebook.rebound.Spring;
import com.facebook.rebound.SpringChain;
import com.liulishuo.okdownload.core.dispatcher.DownloadDispatcher;
import com.nononsenseapps.filepicker.FilePickerActivity;
import com.nononsenseapps.filepicker.Utils;
import com.qmuiteam.qmui.skin.QMUISkinManager;
import com.qmuiteam.qmui.widget.dialog.QMUIDialog;
import com.qmuiteam.qmui.widget.dialog.QMUIDialogAction;
import com.scwang.smartrefresh.layout.SmartRefreshLayout;
import com.scwang.smartrefresh.layout.footer.FalsifyFooter;
import com.scwang.smartrefresh.layout.header.FalsifyHeader;

import java.io.File;
import java.util.List;
import java.util.Locale;

import ceui.lisa.R;
import ceui.lisa.activities.Shaft;
import ceui.lisa.activities.TemplateActivity;
import ceui.lisa.base.SwipeFragment;
import ceui.lisa.databinding.FragmentSettingsBinding;
import ceui.lisa.helper.ThemeHelper;
import ceui.lisa.utils.Common;
import ceui.lisa.utils.Local;
import ceui.lisa.utils.Params;

import static ceui.lisa.fragments.FragmentFilter.ALL_SIZE;
import static ceui.lisa.fragments.FragmentFilter.ALL_SIZE_VALUE;
import static ceui.lisa.fragments.FragmentFilter.THEME_NAME;
import static ceui.lisa.utils.Settings.ALL_LANGUAGE;


public class FragmentSettings extends SwipeFragment<FragmentSettingsBinding> {

    private static final int illustPath_CODE = 10086;
    private static final int gifResultPath_CODE = 10087;
    private static final int gifZipPath_CODE = 10088;
    private static final int gifUnzipPath_CODE = 10089;

    @Override
    public void initLayout() {
        mLayoutID = R.layout.fragment_settings;
    }

    public static FragmentSettings newInstance() {
        return new FragmentSettings();
    }

    @Override
    protected void initData() {
        baseBind.toolbar.setNavigationOnClickListener(view -> mActivity.finish());
        animate(baseBind.parentLinear);

        baseBind.loginOut.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                new QMUIDialog.CheckBoxMessageDialogBuilder(getActivity())
                        .setTitle("退出后是否删除账号信息?")
                        .setMessage("删除账号信息")
                        .setChecked(true)
                        .setSkinManager(QMUISkinManager.defaultInstance(getContext()))
                        .addAction("取消", new QMUIDialogAction.ActionListener() {
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

        baseBind.saveHistory.setChecked(Shaft.sSettings.isSaveViewHistory());
        baseBind.saveHistory.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (isChecked) {
                    Shaft.sSettings.setSaveViewHistory(true);
                } else {
                    Shaft.sSettings.setSaveViewHistory(false);
                }
                Common.showToast("设置成功", baseBind.saveHistory);
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
                if (isChecked) {
                    Shaft.sSettings.setSingleDownloadTask(true);
                    DownloadDispatcher.setMaxParallelRunningCount(1);
                } else {
                    Shaft.sSettings.setSingleDownloadTask(false);
                    DownloadDispatcher.setMaxParallelRunningCount(5);
                }
                Common.showToast("设置成功", baseBind.singleDownloadTask);
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
                Common.showToast("重启APP生效", baseBind.showLikeButton);
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
                Common.showToast("设置成功", baseBind.illustDetailUserNew);
                Local.setSettings(Shaft.sSettings);
            }
        });
        baseBind.illustDetailUserNewRela.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                baseBind.illustDetailUserNew.performClick();
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
                Common.showToast("设置成功", baseBind.relatedNoLimit);
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
                Common.showToast("设置成功", baseBind.autoDns);
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
                Common.showToast("设置成功", baseBind.firstDetailOrigin);
                Local.setSettings(Shaft.sSettings);
            }
        });
        baseBind.firstDetailOriginRela.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                baseBind.firstDetailOrigin.performClick();
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
                Common.showToast("设置成功", baseBind.deleteStarIllust);
                Local.setSettings(Shaft.sSettings);
            }
        });
        baseBind.deleteStarIllustRela.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                baseBind.deleteStarIllust.performClick();
            }
        });

        baseBind.illustPath.setText(Shaft.sSettings.getIllustPath());
        baseBind.illustPath.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent i = new Intent(mContext, FilePickerActivity.class);
                // This works if you defined the intent filter
                // Intent i = new Intent(Intent.ACTION_GET_CONTENT);

                // Set these depending on your use case. These are the defaults.
                i.putExtra(FilePickerActivity.EXTRA_ALLOW_MULTIPLE, false);
                i.putExtra(FilePickerActivity.EXTRA_ALLOW_CREATE_DIR, true);
                i.putExtra(FilePickerActivity.EXTRA_MODE, FilePickerActivity.MODE_DIR);

                // Configure initial directory by specifying a String.
                // You could specify a String like "/storage/emulated/0/", but that can
                // dangerous. Always use Android's API calls to get paths to the SD-card or
                // internal memory.
                i.putExtra(FilePickerActivity.EXTRA_START_PATH, Environment.getExternalStorageDirectory().getPath());

                startActivityForResult(i, illustPath_CODE);
            }
        });

        baseBind.gifResult.setText(Shaft.sSettings.getGifResultPath());
        baseBind.gifResult.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent i = new Intent(mContext, FilePickerActivity.class);
                // This works if you defined the intent filter
                // Intent i = new Intent(Intent.ACTION_GET_CONTENT);

                // Set these depending on your use case. These are the defaults.
                i.putExtra(FilePickerActivity.EXTRA_ALLOW_MULTIPLE, false);
                i.putExtra(FilePickerActivity.EXTRA_ALLOW_CREATE_DIR, true);
                i.putExtra(FilePickerActivity.EXTRA_MODE, FilePickerActivity.MODE_DIR);

                // Configure initial directory by specifying a String.
                // You could specify a String like "/storage/emulated/0/", but that can
                // dangerous. Always use Android's API calls to get paths to the SD-card or
                // internal memory.
                i.putExtra(FilePickerActivity.EXTRA_START_PATH, Environment.getExternalStorageDirectory().getPath());

                startActivityForResult(i, gifResultPath_CODE);
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
                new QMUIDialog.CheckableDialogBuilder(mContext)
                        .setSkinManager(QMUISkinManager.defaultInstance(mContext))
                        .addItems(ALL_SIZE, new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                Shaft.sSettings.setSearchFilter(ALL_SIZE_VALUE[which]);
                                Common.showToast("设置成功", baseBind.searchFilter);
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
                                Common.showToast("设置成功", baseBind.appLanguage);
                                Local.setSettings(Shaft.sSettings);
                                if (which == 0) {
                                    LanguageUtils.applyLanguage(Locale.SIMPLIFIED_CHINESE, "");
                                } else if (which == 1) {
                                    LanguageUtils.applyLanguage(Locale.JAPAN, "");
                                } else if (which == 2) {
                                    LanguageUtils.applyLanguage(Locale.US, "");
                                } else if (which == 3) {
                                    LanguageUtils.applyLanguage(Locale.TRADITIONAL_CHINESE, "");
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
                final int index = ThemeHelper.getThemeType();
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


        baseBind.clearGifCache.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {

            }
        });
        baseBind.refreshLayout.setRefreshHeader(new FalsifyHeader(mContext));
        baseBind.refreshLayout.setRefreshFooter(new FalsifyFooter(mContext));
    }

    private void animate(LinearLayout linearLayout) {
        SpringChain springChain = SpringChain.create(40, 8, 60, 10);

        int childCount = linearLayout.getChildCount();
        for (int i = 0; i < childCount; i++) {
            final View view = linearLayout.getChildAt(i);

            final int position = i;
            springChain.addSpring(new SimpleSpringListener() {
                @Override
                public void onSpringUpdate(Spring spring) {
                    view.setTranslationX((float) spring.getCurrentValue());
                    if (position == 0) {
                        Common.showLog(className + (float) spring.getCurrentValue());
                    }
                }
            });
        }

        List<Spring> springs = springChain.getAllSprings();
        for (int i = 0; i < springs.size(); i++) {
            springs.get(i).setCurrentValue(400);
        }
        springChain.setControlSpringIndex(0).getControlSpring().setEndValue(0);
    }


    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == illustPath_CODE && resultCode == Activity.RESULT_OK) {
            List<Uri> files = Utils.getSelectedFilesFromResult(data);
            for (Uri uri : files) {
                File file = Utils.getFileForUri(uri);
                String path = file.getPath();
                if (path.startsWith("/storage/emulated/0/")) {
                    Shaft.sSettings.setIllustPath(path);
                    Local.setSettings(Shaft.sSettings);
                    baseBind.illustPath.setText(path);
                } else {
                    Common.showToast(getString(R.string.select_inner_storage));
                }
            }
            return;
        }


        if (requestCode == gifResultPath_CODE && resultCode == Activity.RESULT_OK) {
            List<Uri> files = Utils.getSelectedFilesFromResult(data);
            for (Uri uri : files) {
                File file = Utils.getFileForUri(uri);
                String path = file.getPath();
                if (path.startsWith("/storage/emulated/0/")) {
                    Shaft.sSettings.setGifResultPath(path);
                    Local.setSettings(Shaft.sSettings);
                    baseBind.gifResult.setText(path);
                } else {
                    Common.showToast(getString(R.string.select_inner_storage));
                }
            }
            return;
        }
    }

    @Override
    public SmartRefreshLayout getSmartRefreshLayout() {
        return baseBind.refreshLayout;
    }
}
