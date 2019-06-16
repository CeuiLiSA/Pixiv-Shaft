package ceui.lisa.fragments;

import android.view.View;
import android.widget.TextView;

import org.sufficientlysecure.htmltextview.HtmlTextView;

import ceui.lisa.R;
import ceui.lisa.response.UserDetailResponse;
import ceui.lisa.utils.Common;

public class FragmentAboutUser extends BaseFragment {

    private HtmlTextView desc, mainPage, twitter;
    private TextView computer;

    @Override
    void initLayout() {
        mLayoutID = R.layout.fragment_about_user;
    }

    @Override
    View initView(View v) {
        mainPage = v.findViewById(R.id.main_page);
        twitter = v.findViewById(R.id.twitter);
        desc = v.findViewById(R.id.description);
        computer = v.findViewById(R.id.computer);
        Common.showLog(className + "initView 结束");
        return v;
    }

    @Override
    void initData() {
    }

    public void setData(UserDetailResponse response){
        try {

        mainPage.setHtml(response.getProfile().getWebpage());
        twitter.setHtml(response.getProfile().getTwitter_account());
        desc.setHtml(response.getUser().getComment());
        computer.setText(response.getWorkspace().getDesktop());

        }
        catch (Exception e){
            e.printStackTrace();
        }
    }
}
