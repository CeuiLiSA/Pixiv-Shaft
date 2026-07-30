package ceui.lisa.activities;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

import ceui.lisa.R;
import ceui.lisa.utils.Common;
import ceui.lisa.utils.ReverseImage;

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
                        if (!ReverseImage.isFileSizeOkToSearch(imageUri)) {
                            Common.showToast(getString(R.string.string_410));
                            finish();
                            return;
                        }
                        Uri cachedImageUri = Common.copyUriToReverseSearchCache(imageUri);
                        if (cachedImageUri == null) {
                            Common.showToast(getString(R.string.reverse_image_copy_failed));
                            finish();
                            return;
                        }
                        ReverseImage.search(this, cachedImageUri, ReverseImage.DEFAULT_ENGINE);
                        finish();
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }
    }
}
