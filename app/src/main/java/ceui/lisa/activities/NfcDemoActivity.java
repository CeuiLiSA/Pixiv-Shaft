package ceui.lisa.activities;

import android.app.PendingIntent;
import android.content.Intent;
import android.nfc.NfcAdapter;
import android.nfc.Tag;

import ceui.lisa.R;
import ceui.lisa.base.BaseActivity;
import ceui.lisa.databinding.ActivityNfcBinding;
import ceui.lisa.utils.Common;

public class NfcDemoActivity extends BaseActivity<ActivityNfcBinding> {

    private NfcAdapter mNfcAdapter;
    private PendingIntent pi;

    @Override
    protected int initLayout() {
        return R.layout.activity_nfc;
    }

    @Override
    protected void initView() {
        mNfcAdapter = NfcAdapter.getDefaultAdapter(this);

        pi = PendingIntent.getActivity(this, 0, new Intent(this, getClass())
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), 0);
        processIntent(getIntent());
    }

    @Override
    protected void initData() {

    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (NfcAdapter.ACTION_TAG_DISCOVERED.equals(intent.getAction())) {
            processIntent(intent);
        }
    }

    private void processIntent(Intent intent) {
        //取出封装在intent中的TAG
        Tag tagFromIntent = intent.getParcelableExtra(NfcAdapter.EXTRA_TAG);
        String CardId =ByteArrayToHexString(tagFromIntent.getId());
        Common.showLog("CardId " + tagFromIntent.toString());
        Common.showLog("intent " + intent.toString());
    }

    private String ByteArrayToHexString(byte[] inarray) {
        int i, j, in;
        String[] hex = { "0", "1", "2", "3", "4", "5", "6", "7", "8", "9", "A",
                "B", "C", "D", "E", "F" };
        String out = "";


        for (j = 0; j < inarray.length; ++j) {
            in = (int) inarray[j] & 0xff;
            i = (in >> 4) & 0x0f;
            out += hex[i];
            i = in & 0x0f;
            out += hex[i];
        }
        return out;
    }
}
