package ceui.lisa.fragments;

import android.content.Intent;
import android.support.v7.widget.Toolbar;
import android.view.View;
import android.widget.RelativeLayout;

import ceui.lisa.R;
import ceui.lisa.activities.TemplateFragmentActivity;

public class FragmentAbout extends BaseFragment {

    @Override
    void initLayout() {
        mLayoutID = R.layout.fragment_about;
    }

    @Override
    View initView(View v) {
        Toolbar toolbar = v.findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(view -> getActivity().finish());

        RelativeLayout whatsYourProblem = v.findViewById(R.id.pixiv_problem);
        whatsYourProblem.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(mContext, TemplateFragmentActivity.class);
                intent.putExtra(TemplateFragmentActivity.EXTRA_FRAGMENT, "网页链接");
                intent.putExtra("url", "https://app.pixiv.help/hc/zh-cn");
                intent.putExtra("title", "常见问题");
                startActivity(intent);
            }
        });

        RelativeLayout useDetail = v.findViewById(R.id.pixiv_use_detail);
        useDetail.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(mContext, TemplateFragmentActivity.class);
                intent.putExtra(TemplateFragmentActivity.EXTRA_FRAGMENT, "网页链接");
                intent.putExtra("url", "https://www.pixiv.net/terms/?page=term&appname=pixiv_ios");
                intent.putExtra("title", "服务条款");
                startActivity(intent);
            }
        });

        RelativeLayout privacy = v.findViewById(R.id.pixiv_privacy);
        privacy.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(mContext, TemplateFragmentActivity.class);
                intent.putExtra(TemplateFragmentActivity.EXTRA_FRAGMENT, "网页链接");
                intent.putExtra("url", "https://www.pixiv.net/terms/?page=privacy&appname=pixiv_ios");
                intent.putExtra("title", "隐私政策");
                startActivity(intent);
            }
        });


        RelativeLayout projectWebsite = v.findViewById(R.id.project_website);
        projectWebsite.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(mContext, TemplateFragmentActivity.class);
                intent.putExtra(TemplateFragmentActivity.EXTRA_FRAGMENT, "网页链接");
                intent.putExtra("url", "https://github.com/CeuiLiSA/Pixiv-Shaft");
                intent.putExtra("title", "项目主页");
                startActivity(intent);
            }
        });





        return v;
    }

    @Override
    void initData() {

    }
}
