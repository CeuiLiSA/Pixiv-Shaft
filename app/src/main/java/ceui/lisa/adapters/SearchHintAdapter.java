package ceui.lisa.adapters;

import android.content.Context;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;

import com.blankj.utilcode.util.ResourceUtils;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import ceui.lisa.R;
import ceui.lisa.databinding.RecySearchHintBinding;
import ceui.lisa.model.TrendingtagResponse;

public class SearchHintAdapter extends BaseAdapter<TrendingtagResponse.TrendTagsBean, RecySearchHintBinding> {

    private String mKeyword;

    public SearchHintAdapter(List<TrendingtagResponse.TrendTagsBean> targetList, Context context, String keyword) {
        super(targetList, context);
        mKeyword = keyword;
    }

    @Override
    void initLayout() {
        mLayoutID = R.layout.recy_search_hint;
    }

    @Override
    void bindData(TrendingtagResponse.TrendTagsBean target, ViewHolder<RecySearchHintBinding> bindView, int position) {

        SpannableString string = matcherSearchText(mContext.getResources().getColor(R.color.colorPrimary),
                target.getName(), mKeyword);
        bindView.baseBind.titleText.setText(string);

        if(!TextUtils.isEmpty(target.getTranslated_name()) && !target.getTranslated_name().equals(target.getName())) {
            bindView.baseBind.translatedText.setText(String.format("译：%s", target.getTranslated_name()));
        }
    }

    private SpannableString matcherSearchText(int color, String text, String keyword) {
        SpannableString spannableString = new SpannableString(text);
        //条件 keyword
        Pattern pattern = Pattern.compile(keyword);
        //匹配
        Matcher matcher = pattern.matcher(new SpannableString(text.toLowerCase()));
        while (matcher.find()) {
            int start = matcher.start();
            int end = matcher.end();
            //ForegroundColorSpan 需要new 不然也只能是部分变色
            spannableString.setSpan(new ForegroundColorSpan(color), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        //返回变色处理的结果
        return spannableString;
    }
}
