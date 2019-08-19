package ceui.lisa.fragments;

import android.app.Activity;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Environment;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.Toolbar;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.Switch;
import android.widget.TextView;

import com.facebook.rebound.SimpleSpringListener;
import com.facebook.rebound.Spring;
import com.facebook.rebound.SpringChain;
import com.nononsenseapps.filepicker.FilePickerActivity;
import com.nononsenseapps.filepicker.Utils;

import java.io.File;
import java.util.List;

import ceui.lisa.R;
import ceui.lisa.activities.LoginAlphaActivity;
import ceui.lisa.activities.Shaft;
import ceui.lisa.activities.TemplateFragmentActivity;
import ceui.lisa.utils.Common;
import ceui.lisa.utils.Local;

import static ceui.lisa.fragments.FragmentFilter.ALL_SIZE;
import static ceui.lisa.fragments.FragmentFilter.ALL_SIZE_VALUE;


public class FragmentSettings extends BaseFragment {

    private static final int illustPath_CODE = 10086;
    private static final int gifResultPath_CODE = 10087;
    private static final int gifZipPath_CODE = 10088;
    private static final int gifUnzipPath_CODE = 10089;

    private TextView illustPath, gifResultPath, gifZipPath, gifUnzipPath;

    @Override
    void initLayout() {
        mLayoutID = R.layout.fragment_settings;
    }

    @Override
    View initView(View v) {
        Toolbar toolbar = v.findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(view -> getActivity().finish());
        LinearLayout linearLayout = v.findViewById(R.id.parent_linear);
        animate(linearLayout);

        RelativeLayout loginOut = v.findViewById(R.id.login_out);
        loginOut.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(mContext, LoginAlphaActivity.class);
                startActivity(intent);
                getActivity().finish();
            }
        });

        RelativeLayout userManage = v.findViewById(R.id.user_manage);
        userManage.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(mContext, TemplateFragmentActivity.class);
                intent.putExtra(TemplateFragmentActivity.EXTRA_FRAGMENT, "账号管理");
                startActivity(intent);
                getActivity().finish();
            }
        });

        Switch staggerAnime = v.findViewById(R.id.stagger_animate);
        staggerAnime.setChecked(Shaft.sSettings.isStaggerAnime());
        staggerAnime.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (isChecked) {
                    Shaft.sSettings.setStaggerAnime(true);
                } else {
                    Shaft.sSettings.setStaggerAnime(false);
                }
                Local.setSettings(Shaft.sSettings);
            }
        });

        Switch gridAnime = v.findViewById(R.id.grid_animate);
        gridAnime.setChecked(Shaft.sSettings.isGridAnime());
        gridAnime.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (isChecked) {
                    Shaft.sSettings.setGridAnime(true);
                } else {
                    Shaft.sSettings.setGridAnime(false);
                }
                Local.setSettings(Shaft.sSettings);
            }
        });


        Switch saveHistory = v.findViewById(R.id.save_history);
        saveHistory.setChecked(Shaft.sSettings.isSaveViewHistory());
        saveHistory.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (isChecked) {
                    Shaft.sSettings.setSaveViewHistory(true);
                } else {
                    Shaft.sSettings.setSaveViewHistory(false);
                }
                Local.setSettings(Shaft.sSettings);
            }
        });

        Switch relatedNoLimit = v.findViewById(R.id.related_no_limit);
        relatedNoLimit.setChecked(Shaft.sSettings.isRelatedIllustNoLimit());
        relatedNoLimit.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (isChecked) {
                    Shaft.sSettings.setRelatedIllustNoLimit(true);
                } else {
                    Shaft.sSettings.setRelatedIllustNoLimit(false);
                }
                Local.setSettings(Shaft.sSettings);
            }
        });


        Switch autoDns = v.findViewById(R.id.auto_dns);
        autoDns.setChecked(Shaft.sSettings.isAutoFuckChina());
        autoDns.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (isChecked) {
                    Shaft.sSettings.setAutoFuckChina(true);
                } else {
                    Shaft.sSettings.setAutoFuckChina(false);
                }
                Local.setSettings(Shaft.sSettings);
            }
        });

        illustPath = v.findViewById(R.id.illust_path);
        illustPath.setText(Shaft.sSettings.getIllustPath());
        illustPath.setOnClickListener(new View.OnClickListener() {
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

        gifResultPath = v.findViewById(R.id.gif_result);
        gifResultPath.setText(Shaft.sSettings.getGifResultPath());
        gifResultPath.setOnClickListener(new View.OnClickListener() {
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

        gifZipPath = v.findViewById(R.id.gif_zip);
        gifZipPath.setText(Shaft.sSettings.getGifZipPath());
        gifZipPath.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Common.showToast("暂不支持修改");
            }
        });

        gifUnzipPath = v.findViewById(R.id.gif_unzip);
        gifUnzipPath.setText(Shaft.sSettings.getGifUnzipPath());
        gifUnzipPath.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Common.showToast("暂不支持修改");
            }
        });


        TextView fuckChina = v.findViewById(R.id.fuck_china);
        fuckChina.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(mContext, TemplateFragmentActivity.class);
                intent.putExtra(TemplateFragmentActivity.EXTRA_FRAGMENT, "网页链接");
                intent.putExtra("url", "https://github.com/Notsfsssf/Pix-EzViewer");
                intent.putExtra("title", "PxEz项目主页");
                startActivity(intent);
            }
        });

        TextView searchFilter = v.findViewById(R.id.search_filter);
        searchFilter.setText(Shaft.sSettings.getSearchFilter());
        searchFilter.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                AlertDialog.Builder builder = new AlertDialog.Builder(mContext);
                builder.setTitle("被收藏数");
                builder.setItems(ALL_SIZE, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        Shaft.sSettings.setSearchFilter(ALL_SIZE_VALUE[which]);
                        Local.setSettings(Shaft.sSettings);
                        searchFilter.setText(ALL_SIZE[which]);
                    }
                });
                AlertDialog alertDialog = builder.create();
                alertDialog.show();
            }
        });


        return v;
    }

    @Override
    void initData() {

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
                    //view.setAlpha((float) ((400 - spring.getCurrentValue()) / 400 ) );
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
            // Use the provided utility method to parse the result
            List<Uri> files = Utils.getSelectedFilesFromResult(data);
            for (Uri uri : files) {
                File file = Utils.getFileForUri(uri);
                String path = file.getPath();
                if (path.startsWith("/storage/emulated/0/")) {
                    Shaft.sSettings.setIllustPath(path);
                    Local.setSettings(Shaft.sSettings);
                    illustPath.setText(path);
                } else {
                    Common.showToast(getString(R.string.select_inner_storage));
                }
            }
            return;
        }


        if (requestCode == gifResultPath_CODE && resultCode == Activity.RESULT_OK) {
            // Use the provided utility method to parse the result
            List<Uri> files = Utils.getSelectedFilesFromResult(data);
            for (Uri uri : files) {
                File file = Utils.getFileForUri(uri);
                String path = file.getPath();
                if (path.startsWith("/storage/emulated/0/")) {
                    Shaft.sSettings.setGifResultPath(path);
                    Local.setSettings(Shaft.sSettings);
                    gifResultPath.setText(path);
                } else {
                    Common.showToast(getString(R.string.select_inner_storage));
                }
            }
            return;
        }
    }
}
