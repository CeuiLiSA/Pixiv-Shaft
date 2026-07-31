package ceui.lisa.activities;

import android.content.Intent;
import android.net.Uri;

import androidx.core.content.IntentCompat;

import ceui.lisa.utils.ReverseImage;

public class OutReversActivity extends OutWakeActivity {

    @Override
    protected void initData() {
        Intent intent = getIntent();
        if (intent != null) {
            if (Intent.ACTION_SEND.equals(intent.getAction())) {
                try {
                    Uri imageUri = IntentCompat.getParcelableExtra(
                            intent, Intent.EXTRA_STREAM, Uri.class);
                    if (imageUri == null) {
                        finish();
                        return;
                    }
                    // 查大小和复制都在子线程，所以 finish 交给回调 —— 提前 finish 会把
                    // 起 TemplateActivity 用的 context 抽掉。
                    ReverseImage.searchFrom(this, imageUri, ReverseImage.DEFAULT_ENGINE, this::finish);
                } catch (Exception e) {
                    // intent 来自任意外部 app，EXTRA_STREAM 里塞了坏 Parcelable 是可能的。
                    // 这一屏挂着 SplashTheme 有真实布局，不 finish 就是留一张空白页在那。
                    e.printStackTrace();
                    finish();
                }
            }
        }
    }
}
