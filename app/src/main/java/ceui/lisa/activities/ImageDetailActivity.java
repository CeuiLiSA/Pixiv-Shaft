package ceui.lisa.activities;

import android.graphics.Color;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentPagerAdapter;
import android.support.v4.view.ViewPager;
import android.view.View;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

import ceui.lisa.R;
import ceui.lisa.download.IllustDownload;
import ceui.lisa.fragments.FragmentImageDetail;
import ceui.lisa.fragments.FragmentLocalImageDetail;
import ceui.lisa.model.IllustsBean;

import static com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions.withCrossFade;

public class ImageDetailActivity extends BaseActivity {

    private IllustsBean mIllustsBean;
    private List<String> localIllust = new ArrayList<>();
    private TextView currentPage, downloadSingle;
    private String dataType = "";

    @Override
    protected void initLayout() {
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
                        View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        mLayoutID = R.layout.activity_image_detail;
    }

    @Override
    protected void initView() {
        dataType = getIntent().getStringExtra("dataType");
        ViewPager viewPager = findViewById(R.id.view_pager);
        if(dataType.equals("二级详情")) {
            currentPage = findViewById(R.id.current_page);
            downloadSingle = findViewById(R.id.download_this_one);
            mIllustsBean = (IllustsBean) getIntent().getSerializableExtra("illust");
            int index = getIntent().getIntExtra("index", 0);
            if (mIllustsBean == null) {
                return;
            }

            viewPager.setAdapter(new FragmentPagerAdapter(getSupportFragmentManager()) {
                @Override
                public Fragment getItem(int i) {
                    return FragmentImageDetail.newInstance(mIllustsBean, i);
                }

                @Override
                public int getCount() {
                    return mIllustsBean.getPage_count();
                }
            });
            viewPager.setCurrentItem(index);
            downloadSingle.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    IllustDownload.downloadIllust(mIllustsBean, viewPager.getCurrentItem());
                }
            });
            viewPager.addOnPageChangeListener(new ViewPager.OnPageChangeListener() {
                @Override
                public void onPageScrolled(int i, float v, int i1) {

                }

                @Override
                public void onPageSelected(int i) {
                    currentPage.setText("第" + (i + 1) + "P / 共" + mIllustsBean.getPage_count() + "P");
                }

                @Override
                public void onPageScrollStateChanged(int i) {

                }
            });
            currentPage.setText("第" + (index + 1) + "P / 共" + mIllustsBean.getPage_count() + "P");

        }else if(dataType.equals("下载详情")){

            currentPage = findViewById(R.id.current_page);
            downloadSingle = findViewById(R.id.download_this_one);
            localIllust = (List<String>) getIntent().getSerializableExtra("illust");
            int index = getIntent().getIntExtra("index", 0);

            viewPager.setAdapter(new FragmentPagerAdapter(getSupportFragmentManager()) {
                @Override
                public Fragment getItem(int i) {
                    return FragmentLocalImageDetail.newInstance(localIllust.get(i));
                }

                @Override
                public int getCount() {
                    return localIllust.size();
                }
            });
            currentPage.setVisibility(View.GONE);
            viewPager.setCurrentItem(index);
//            downloadSingle.setOnClickListener(new View.OnClickListener() {
//                @Override
//                public void onClick(View v) {
//                    IllustDownload.downloadIllust(mIllustsBean, viewPager.getCurrentItem());
//                }
//            });
            viewPager.addOnPageChangeListener(new ViewPager.OnPageChangeListener() {
                @Override
                public void onPageScrolled(int i, float v, int i1) {

                }

                @Override
                public void onPageSelected(int i) {
                    downloadSingle.setText("路径：" + localIllust.get(i));
                }

                @Override
                public void onPageScrollStateChanged(int i) {

                }
            });
            downloadSingle.setText("路径：" + localIllust.get(index));
        }
    }

    @Override
    protected void initData() {

    }
}
