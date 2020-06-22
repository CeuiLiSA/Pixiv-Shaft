package ceui.lisa.core;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.util.Log;
import android.widget.TextView;

import org.sufficientlysecure.htmltextview.HtmlAssetsImageGetter;
import org.sufficientlysecure.htmltextview.HtmlTextView;

import java.io.IOException;
import java.io.InputStream;

import ceui.lisa.utils.Common;

public class ImgGetter extends HtmlAssetsImageGetter {

    private Context mContext;
    private static final int BOUND = 48;

    public ImgGetter(Context context) {
        super(context);
        this.mContext = context;
    }

    public ImgGetter(TextView textView) {
        super(textView);
        this.mContext = textView.getContext();
    }

    @Override
    public Drawable getDrawable(String source) {

        try {
            InputStream inputStream = mContext.getAssets().open(source);
            Drawable d = Drawable.createFromStream(inputStream, null);
            d.setBounds(0, 0, BOUND, BOUND);
            Common.showLog("wid: " + d.getIntrinsicWidth() + " heightL: " + d.getIntrinsicHeight());
            return d;
        } catch (IOException e) {
            // prevent a crash if the resource still can't be found
            Log.e(HtmlTextView.TAG, "source could not be found: " + source);
            return null;
        }
    }
}
