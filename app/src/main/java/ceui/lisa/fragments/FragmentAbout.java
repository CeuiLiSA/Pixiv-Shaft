package ceui.lisa.fragments;

import android.content.Intent;
import androidx.appcompat.widget.Toolbar;
import android.view.View;
import android.widget.RelativeLayout;

import com.blankj.utilcode.util.DeviceUtils;
import com.blankj.utilcode.util.RomUtils;

import ceui.lisa.R;
import ceui.lisa.activities.TemplateFragmentActivity;
import ceui.lisa.databinding.FragmentAboutBinding;
import ceui.lisa.utils.Common;

public class FragmentAbout extends BaseBindFragment<FragmentAboutBinding> {

    @Override
    void initLayout() {
        mLayoutID = R.layout.fragment_about;
    }


    @Override
    void initData() {
        baseBind.toolbar.setNavigationOnClickListener(view -> getActivity().finish());
        baseBind.pixivProblem.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(mContext, TemplateFragmentActivity.class);
                intent.putExtra(TemplateFragmentActivity.EXTRA_FRAGMENT, "网页链接");
                intent.putExtra("url", "https://app.pixiv.help/hc/zh-cn");
                intent.putExtra("title", "常见问题");
                startActivity(intent);
            }
        });
        baseBind.pixivUseDetail.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(mContext, TemplateFragmentActivity.class);
                intent.putExtra(TemplateFragmentActivity.EXTRA_FRAGMENT, "网页链接");
                intent.putExtra("url", "https://www.pixiv.net/terms/?page=term&appname=pixiv_ios");
                intent.putExtra("title", "服务条款");
                startActivity(intent);
            }
        });
        baseBind.pixivPrivacy.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(mContext, TemplateFragmentActivity.class);
                intent.putExtra(TemplateFragmentActivity.EXTRA_FRAGMENT, "网页链接");
                intent.putExtra("url", "https://www.pixiv.net/terms/?page=privacy&appname=pixiv_ios");
                intent.putExtra("title", "隐私政策");
                startActivity(intent);
            }
        });
        baseBind.projectWebsite.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(mContext, TemplateFragmentActivity.class);
                intent.putExtra(TemplateFragmentActivity.EXTRA_FRAGMENT, "网页链接");
                intent.putExtra("url", "https://github.com/CeuiLiSA/Pixiv-Shaft");
                intent.putExtra("title", "项目主页");
                startActivity(intent);
            }
        });
        baseBind.projectLicense.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(mContext, TemplateFragmentActivity.class);
                intent.putExtra(TemplateFragmentActivity.EXTRA_FRAGMENT, "License");
                startActivity(intent);
            }
        });
    }
}
