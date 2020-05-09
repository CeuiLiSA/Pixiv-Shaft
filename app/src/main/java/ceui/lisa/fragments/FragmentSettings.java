package ceui.lisa.fragments;

import android.app.Activity;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Environment;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.LinearLayout;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.blankj.utilcode.util.FileUtils;
import com.blankj.utilcode.util.LanguageUtils;
import com.facebook.rebound.SimpleSpringListener;
import com.facebook.rebound.Spring;
import com.facebook.rebound.SpringChain;
import com.nononsenseapps.filepicker.FilePickerActivity;
import com.nononsenseapps.filepicker.Utils;
import com.scwang.smartrefresh.layout.footer.FalsifyFooter;
import com.scwang.smartrefresh.layout.header.FalsifyHeader;

import org.greenrobot.eventbus.EventBus;

import java.io.File;
import java.util.List;
import java.util.Locale;

import ceui.lisa.R;
import ceui.lisa.activities.Shaft;
import ceui.lisa.activities.TemplateActivity;
import ceui.lisa.databinding.FragmentSettingsBinding;
import ceui.lisa.helper.ThemeHelper;
import ceui.lisa.utils.Channel;
import ceui.lisa.utils.Common;
import ceui.lisa.utils.Local;
import ceui.lisa.utils.Params;

import static ceui.lisa.fragments.FragmentFilter.ALL_SIZE;
import static ceui.lisa.fragments.FragmentFilter.ALL_SIZE_VALUE;
import static ceui.lisa.fragments.FragmentFilter.THEME_NAME;
import static ceui.lisa.utils.Settings.ALL_LANGUAGE;


public class FragmentSettings extends BaseFragment<FragmentSettingsBinding> {

    private static final int illustPath_CODE = 10086;
    private static final int gifResultPath_CODE = 10087;
    private static final int gifZipPath_CODE = 10088;
    private static final int gifUnzipPath_CODE = 10089;
    private boolean shouldRefreshFragmentRight = false;

    @Override
    public void initLayout() {
        mLayoutID = R.layout.fragment_settings;
    }

    public static FragmentSettings newInstance() {
        return new FragmentSettings();
    }

    @Override
    void initData() {
        baseBind.toolbar.setNavigationOnClickListener(view -> mActivity.finish());
        animate(baseBind.parentLinear);

        baseBind.loginOut.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                new AlertDialog.Builder(mContext)
                        .setTitle(getString(R.string.login_out) + "?")
                        .setPositiveButton(R.string.login_out, new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                Common.logOut(mContext);
                                mActivity.finish();
                            }
                        })
                        .setNegativeButton(android.R.string.cancel, null)
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

        shouldRefreshFragmentRight = Shaft.sSettings.isDoubleStaggerData();
        baseBind.staggerData.setChecked(Shaft.sSettings.isDoubleStaggerData());
        baseBind.staggerData.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (isChecked) {
                    Shaft.sSettings.setDoubleStaggerData(true);
                } else {
                    Shaft.sSettings.setDoubleStaggerData(false);
                }
                Common.showToast("设置成功", baseBind.staggerData);
                Local.setSettings(Shaft.sSettings);
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
        baseBind.searchFilter.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                AlertDialog.Builder builder = new AlertDialog.Builder(mContext);
                builder.setTitle("被收藏数");
                builder.setItems(ALL_SIZE, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        Shaft.sSettings.setSearchFilter(ALL_SIZE_VALUE[which]);
                        Common.showToast("设置成功", baseBind.searchFilter);
                        Local.setSettings(Shaft.sSettings);
                        baseBind.searchFilter.setText(ALL_SIZE[which]);
                    }
                });
                AlertDialog alertDialog = builder.create();
                alertDialog.show();
            }
        });
        baseBind.appLanguage.setText(Shaft.sSettings.getAppLanguage());
        baseBind.appLanguage.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                AlertDialog.Builder builder = new AlertDialog.Builder(mContext);
                builder.setTitle(getString(R.string.language));
                builder.setItems(ALL_LANGUAGE, new DialogInterface.OnClickListener() {
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
                    }
                });
                AlertDialog alertDialog = builder.create();
                alertDialog.show();
            }
        });

        baseBind.themeMode.setText(Shaft.sSettings.getThemeType());
        baseBind.themeMode.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                AlertDialog.Builder builder = new AlertDialog.Builder(mContext);
                builder.setTitle(getString(R.string.theme_mode));
                final int index = ThemeHelper.getThemeType();
                builder.setSingleChoiceItems(THEME_NAME, index, new DialogInterface.OnClickListener() {
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
                });
                AlertDialog alertDialog = builder.create();
                alertDialog.show();
            }
        });


        baseBind.clearGifCache.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (FileUtils.delete(Shaft.sSettings.getGifUnzipPath())) {
                    Common.showLog(className + Shaft.sSettings.getGifUnzipPath());
                    Common.showToast("GIF缓存清理成功");
                }
            }
        });
        baseBind.refreshLayout.setRefreshHeader(new FalsifyHeader(mContext));
        baseBind.refreshLayout.setRefreshFooter(new FalsifyFooter(mContext));

//        baseBind.fullscreenLayout.setChecked(Shaft.sSettings.isFullscreenLayout());
//        baseBind.fullscreenLayout.setOnCheckedChangeListener((buttonView, isChecked) -> {
//            Shaft.sSettings.setFullscreenLayout(isChecked);
//            Local.setSettings(Shaft.sSettings);
//        });
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
    public void onDestroyView() {
        if (shouldRefreshFragmentRight != Shaft.sSettings.isDoubleStaggerData()) {
            Channel channel = new Channel();
            channel.setReceiver("FragmentRight");
            EventBus.getDefault().post(channel);
        }

        super.onDestroyView();
    }
}
