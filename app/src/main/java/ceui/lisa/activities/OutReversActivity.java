package ceui.lisa.activities;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

import com.blankj.utilcode.util.UriUtils;

import java.io.File;

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
                        ReverseImage.reverse(UriUtils.uri2Bytes(imageUri),
                                ReverseImage.ReverseProvider.SauceNao, new ReverseWebviewCallback(this));
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }
    }
}
