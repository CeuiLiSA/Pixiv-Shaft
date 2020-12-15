package ceui.lisa.fragments;

import android.content.Intent;
import android.net.Uri;
import android.view.View;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

import ceui.lisa.R;
import ceui.lisa.activities.Shaft;
import ceui.lisa.database.AppDatabase;
import ceui.lisa.database.IllustRecmdEntity;
import ceui.lisa.databinding.FragmentStorageBinding;
import ceui.lisa.models.IllustsBean;
import ceui.lisa.utils.Local;

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

            }
        });

        baseBind.save.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {



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
