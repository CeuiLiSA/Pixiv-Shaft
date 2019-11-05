package ceui.lisa.activities;

import android.view.View;
import android.widget.TextView;

import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentPagerAdapter;
import androidx.viewpager.widget.ViewPager;

import com.ToxicBakery.viewpager.transforms.CubeOutTransformer;
import com.blankj.utilcode.util.BarUtils;
import com.blankj.utilcode.util.ColorUtils;

import java.util.ArrayList;
import java.util.List;

import ceui.lisa.R;
import ceui.lisa.download.IllustDownload;
import ceui.lisa.fragments.FragmentImageDetail;
import ceui.lisa.fragments.FragmentLocalImageDetail;
import ceui.lisa.model.IllustsBean;

public class ImageDetailActivity extends BaseActivity {

    private IllustsBean mIllustsBean;
    private List<String> localIllust = new ArrayList<>();
    private TextView currentPage, downloadSingle, currentSize;
    private String dataType = "";

    @Override
    protected void initLayout() {
        BarUtils.setStatusBarColor(this, ColorUtils.getColor(R.color.qmui_config_color_transparent));
        if (BarUtils.isSupportNavBar()) {
            BarUtils.setNavBarVisibility(this, false);
        }
        mLayoutID = R.layout.activity_image_detail;
    }

    @Override
    protected void initView() {
        dataType = getIntent().getStringExtra("dataType");
        ViewPager viewPager = findViewById(R.id.view_pager);
        viewPager.setPageTransformer(true, new CubeOutTransformer());
        if (dataType.equals("二级详情")) {
            currentSize = findViewById(R.id.current_size);
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

        } else if (dataType.equals("下载详情")) {

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
        currentPage.setTextAppearance(mContext, R.style.shadowText);
        downloadSingle.setTextAppearance(mContext, R.style.shadowText);
    }


    @Override
    protected void initData() {

    }
}
