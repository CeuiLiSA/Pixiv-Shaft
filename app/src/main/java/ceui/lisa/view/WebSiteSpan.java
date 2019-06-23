package ceui.lisa.view;

import android.graphics.Color;
import android.support.annotation.NonNull;
import android.text.TextPaint;
import android.text.style.ClickableSpan;
import android.view.View;

import org.sufficientlysecure.htmltextview.ClickableTableSpan;

import ceui.lisa.R;
import ceui.lisa.activities.Shaft;

public class WebSiteSpan extends ClickableTableSpan {


    @Override
    public void onClick(@NonNull View widget) {

    }

    @Override
    public void updateDrawState(TextPaint ds) {
        //super.updateDrawState(ds);
        ds.setColor(Shaft.getContext().getResources().getColor(R.color.colorPrimary)); // 设置字体颜色
        ds.setUnderlineText(false); //去掉下划线
    }

    @Override
    public ClickableTableSpan newInstance() {
        return new WebSiteSpan();
    }
}
