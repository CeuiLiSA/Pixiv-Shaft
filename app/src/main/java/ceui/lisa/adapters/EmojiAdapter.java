package ceui.lisa.adapters;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.util.Log;
import android.view.View;

import androidx.annotation.Nullable;

import com.bumptech.glide.Glide;

import org.sufficientlysecure.htmltextview.HtmlTextView;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

import ceui.lisa.R;
import ceui.lisa.databinding.RecyEmojiBinding;
import ceui.lisa.model.EmojiItem;
import ceui.lisa.utils.Common;


public class EmojiAdapter extends BaseAdapter<EmojiItem, RecyEmojiBinding> {

    public EmojiAdapter(@Nullable List<EmojiItem> targetList, Context context) {
        super(targetList, context);
    }

    @Override
    public void initLayout() {
        mLayoutID = R.layout.recy_emoji;
    }

    @Override
    public void bindData(EmojiItem target, ViewHolder<RecyEmojiBinding> bindView, int position) {
        try {
            InputStream inputStream = mContext.getAssets().open(target.getResource());
            Drawable d = Drawable.createFromStream(inputStream, null);
            d.setBounds(0, 0, d.getIntrinsicWidth(), d.getIntrinsicHeight());
            Glide.with(mContext)
                    .load(d)
                    .into(bindView.baseBind.emojiImg);
            Common.showLog("wid: " + d.getIntrinsicWidth() + " heightL: " + d.getIntrinsicHeight());
        } catch (IOException e) {
            e.printStackTrace();
            // prevent a crash if the resource still can't be found
            Log.e(HtmlTextView.TAG, "source could not be found: " + target.getResource());
        }
        bindView.itemView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                mOnItemClickListener.onItemClick(v, position, 0);
            }
        });
    }
}
