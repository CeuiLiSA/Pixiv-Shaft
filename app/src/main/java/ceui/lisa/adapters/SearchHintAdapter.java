package ceui.lisa.adapters;

import android.content.Context;
import android.content.res.Resources;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.util.TypedValue;
import android.view.View;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import ceui.lisa.R;
import ceui.lisa.databinding.RecySearchHintBinding;
import ceui.lisa.model.ListTrendingtag;
import ceui.lisa.utils.Common;

public class SearchHintAdapter extends BaseAdapter<ListTrendingtag.TrendTagsBean, RecySearchHintBinding> {

    private String mKeyword;

    public SearchHintAdapter(List<ListTrendingtag.TrendTagsBean> targetList, Context context, String keyword) {
        super(targetList, context);
        mKeyword = keyword;
    }

    @Override
    public void initLayout() {
        mLayoutID = R.layout.recy_search_hint;
    }

    @Override
    public void bindData(ListTrendingtag.TrendTagsBean target, ViewHolder<RecySearchHintBinding> bindView, int position) {
        SpannableString string = matcherSearchText(Common.resolveThemeAttribute(mContext, R.attr.colorPrimary),
                target.getName(), mKeyword);
        bindView.baseBind.titleText.setText(string);
        if (!TextUtils.isEmpty(target.getTranslated_name()) && !target.getTranslated_name().equals(target.getName())) {
            bindView.baseBind.translatedText.setText(String.format("译：%s", target.getTranslated_name()));
        }
        if (mOnItemClickListener != null) {
            bindView.itemView.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    mOnItemClickListener.onItemClick(view, position, 0);
                }
            });
        }
    }

    private SpannableString matcherSearchText(int color, String text, String keyword) {
        SpannableString spannableString = new SpannableString(text);
        Pattern pattern = Pattern.compile(keyword);
        Matcher matcher = pattern.matcher(new SpannableString(text.toLowerCase(Locale.getDefault())));
        while (matcher.find()) {
            int start = matcher.start();
            int end = matcher.end();
            spannableString.setSpan(new ForegroundColorSpan(color), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return spannableString;
    }

    public String getKeyword() {
        return mKeyword;
    }

    public void setKeyword(String mKeyword) {
        this.mKeyword = mKeyword;
    }
}
