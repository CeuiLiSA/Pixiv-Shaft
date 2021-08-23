package ceui.lisa.activities;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

import com.blankj.utilcode.util.UriUtils;

import java.io.File;

import ceui.lisa.R;
import ceui.lisa.utils.Common;
import ceui.lisa.utils.ReverseImage;
import ceui.lisa.utils.ReverseWebviewCallback;

public class OutReversActivity extends OutWakeActivity {

    @Override
    protected void initData() {
        Intent intent = getIntent();
        if (intent != null) {
            if (Intent.ACTION_SEND.equals(intent.getAction())) {
                try {
                    Bundle bundle = getIntent().getExtras();
                    if (bundle != null) {
                        Uri imageUri = getIntent().getParcelableExtra(Intent.EXTRA_STREAM);
                        File innerImageFile = UriUtils.uri2File(imageUri);
                        Uri innerImageFileUri = Uri.fromFile(innerImageFile);
                        if (!ReverseImage.isFileSizeOkToSearch(imageUri, ReverseImage.DEFAULT_ENGINE)) {
                            Common.showToast(getString(R.string.string_410));
                            finish();
                            return;
                        }
                        ReverseImage.reverse(innerImageFileUri,
                                ReverseImage.DEFAULT_ENGINE, new ReverseWebviewCallback(this, innerImageFileUri));
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }
    }
}
