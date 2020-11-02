package ceui.lisa.fragments;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.UriPermission;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.documentfile.provider.DocumentFile;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import ceui.lisa.R;
import ceui.lisa.activities.Shaft;
import ceui.lisa.activities.TemplateActivity;
import ceui.lisa.base.BaseFragment;
import ceui.lisa.core.DownloadItem;
import ceui.lisa.core.Manager;
import ceui.lisa.core.SAFile;
import ceui.lisa.database.AppDatabase;
import ceui.lisa.database.IllustRecmdEntity;
import ceui.lisa.databinding.FragmentStorageBinding;
import ceui.lisa.download.FileCreator;
import ceui.lisa.helper.TagFilter;
import ceui.lisa.models.IllustsBean;
import ceui.lisa.utils.Common;
import ceui.lisa.utils.Local;
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.functions.Consumer;
import okio.BufferedSource;
import okio.Okio;
import okio.Sink;
import rxhttp.RxHttp;
import rxhttp.wrapper.entity.Progress;

import static android.app.Activity.RESULT_OK;

public class FragmentStorage extends BaseFragment<FragmentStorageBinding> {

    private List<IllustRecmdEntity> localData;

    @Override
    protected void initLayout() {
        mLayoutID = R.layout.fragment_storage;
    }

    private static final int WRITE_REQUEST_CODE = 43;

    @Override
    protected void initView() {
        localData = AppDatabase.getAppDatabase(mContext).recmdDao().getAll();
        List<IllustsBean> temp = new ArrayList<>();
        for (int i = 0; i < localData.size(); i++) {
            IllustsBean illustsBean = Shaft.sGson.fromJson(
                    localData.get(i).getIllustJson(), IllustsBean.class);
            temp.add(illustsBean);
        }
        baseBind.store.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
//                if (!TextUtils.isEmpty(Shaft.sSettings.getRootPathUri())) {
//                    Common.showToast(Shaft.sSettings.getRootPathUri());
//                } else {
//                    startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), 42);
//                }

                Uri rootUri = Uri.parse(Shaft.sSettings.getRootPathUri());
                try {
                    Uri tttt = DocumentsContract.createDocument(mContext.getContentResolver(),
                            rootUri, SAFile.getMimeType(temp.get(0), 0), FileCreator.createIllustFile(temp.get(0), 0).getName());
                    Common.showLog(tttt.toString());
                } catch (FileNotFoundException e) {
                    e.printStackTrace();
                }

            }
        });

        baseBind.save.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                DocumentFile child = SAFile.getDocument(mContext, temp.get(0), 0);
                if (child.exists()) {
                    Common.showLog("child.exists() 111");
                } else {
                    Common.showLog("child.exists() 222");
                }
                Common.showLog(child.getUri().toString());

            }
        });
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) {
            return;
        }
        Uri treeUri = data.getData();
        Shaft.sSettings.setRootPathUri(treeUri.toString());
        mContext.getContentResolver().takePersistableUriPermission(treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION |
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        Local.setSettings(Shaft.sSettings);
    }
}
