package ceui.lisa.fragments;

import android.content.Intent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentPagerAdapter;
import androidx.fragment.app.FragmentTransaction;

import com.scwang.smartrefresh.layout.footer.FalsifyFooter;
import com.scwang.smartrefresh.layout.header.FalsifyHeader;

import ceui.lisa.R;
import ceui.lisa.activities.Shaft;
import ceui.lisa.activities.TemplateActivity;
import ceui.lisa.model.AdItem;
import ceui.lisa.databinding.FragmentCenterBinding;
import ceui.lisa.http.NullCtrl;
import ceui.lisa.http.Retro;
import ceui.lisa.model.ListIllust;
import ceui.lisa.transformer.GalleryTransformer;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.schedulers.Schedulers;

public class FragmentCenter extends BaseFragment<FragmentCenterBinding> {

    private boolean isLoad = false;

    @Override
    public void initLayout() {
        mLayoutID = R.layout.fragment_center;
    }

    @Override
    public void initView(View view) {
        FalsifyHeader falsifyHeader = new FalsifyHeader(mContext);
        falsifyHeader.setPrimaryColors(getResources().getColor(R.color.colorPrimary));
        falsifyHeader.setBackgroundColor(getResources().getColor(R.color.colorPrimary));
        baseBind.refreshLayout.setRefreshHeader(falsifyHeader);
        baseBind.refreshLayout.setRefreshFooter(new FalsifyFooter(mContext));
        baseBind.manga.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(mContext, TemplateActivity.class);
                intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "推荐漫画");
                startActivity(intent);
            }
        });
        baseBind.novel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(mContext, TemplateActivity.class);
                intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "推荐小说");
                startActivity(intent);
            }
        });

        baseBind.viewPager.getViewTreeObserver().addOnGlobalLayoutListener(
                new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override
            public void onGlobalLayout() {
                ViewGroup.LayoutParams params = baseBind.wtfHead.getLayoutParams();
                params.height = baseBind.viewPager.getTop() +
                        (baseBind.viewPager.getBottom() - baseBind.viewPager.getTop()) / 2;
                baseBind.wtfHead.setLayoutParams(params);
                baseBind.viewPager.getViewTreeObserver().removeOnGlobalLayoutListener(this);
            }
        });
    }

    @Override
    void initData() {
        if (true) {
            AdItem[] sonies = new AdItem[]{
                    new AdItem("WH-1000XM3",
                            "https://www.sonystyle.com.cn/products/headphone/wh_1000xm3/wh_1000xm3_b.html?cid=sony_pa_wh_1000xm3",
                            "https://www.sonystyle.com.cn/content/dam/sonystyle/products/headphone/wh_1000xm3/feature/1000xm3_kv.jpg"),
                    new AdItem("FE 20mm F1.8G",
                            "https://www.sonystyle.com.cn/products/lenses/sel20f18g/sel20f18g.html?cid=sony_kv2_di_sel20f18g",
                            "https://www.sonystyle.com.cn/content/dam/sonystyle/products/lenses/e_lens/sel20f18g/feature/sel20f18g_n01_200320.jpg"),
                    new AdItem("NW-A100",
                            "https://www.sonystyle.com.cn/products/media_player/nw_a100/nw_a105hn_b.html?cid=sony_pa_nw_a105hn_black",
                            "https://www.sonystyle.com.cn/content/dam/sonystyle/products/media_player/nw_a100/fecture/p1.jpg"),

                    new AdItem("PlayStation 4 Pro",
                            "https://www.sonystyle.com.cn/products/playstation/ps4pro/ps4pro_1t_b_tz.html?cid=sony_av_playstation4pro_1tb_blacktz",
                            "https://www.sonystyle.com.cn/content/dam/sonystyle/products/playstation/ps4pro_2tb/feature/p1b.jpg"),
                    new AdItem("FE 135mm F1.8GM",
                            "https://www.sonystyle.com.cn/products/lenses/sel135f18gm/sel135f18gm.html?cid=sony_di_sel135f18gm",
                            "https://www.sonystyle.com.cn/content/dam/sonystyle/products/lenses/e_lens/sel135f18gm/feature/sel135f18gm_01.jpg"),

                    new AdItem("RX0-RM2",
                            "https://www.sonystyle.com.cn/products/cyber-shot/dsc_rx0m2/dsc_rx0m2g.html?cid=sony_di_rx0m2",
                            "https://www.sonystyle.com.cn/content/dam/sonystyle/products/cyber-shot/dsc_rx0m2/feature/dsc_rx0m2_handle_01_191225.jpg"),
                    new AdItem("ILCE-7RM4",
                            "https://www.sonystyle.com.cn/products/ilc/ilce_7rm4/ilce_7rm4.html?cid=sony_di_a7rm4",
                            "https://www.sonystyle.com.cn/content/dam/sonystyle/products/ilc/e-body/ilce-7rm4/feature/ilce_7rm4_01_190926.jpg")

            };
            baseBind.viewPager.setAdapter(new FragmentPagerAdapter(getChildFragmentManager(), 0) {
                @NonNull
                @Override
                public Fragment getItem(int position) {
                    int index = position % sonies.length;
                    return FragmentAD.Companion.newInstance(sonies[index]);
                }

                @Override
                public int getCount() {
                    return Integer.MAX_VALUE;
                }
            });
            baseBind.viewPager.setPageTransformer(true, new GalleryTransformer());
            baseBind.viewPager.setOffscreenPageLimit(3);
            baseBind.viewPager.setCurrentItem(sonies.length);
        } else {
            Retro.getAppApi().getLoginBg(Shaft.sUserModel.getResponse().getAccess_token())
                    .subscribeOn(Schedulers.newThread())
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(new NullCtrl<ListIllust>() {
                        @Override
                        public void success(ListIllust listIllust) {
                            baseBind.viewPager.setAdapter(new FragmentPagerAdapter(getChildFragmentManager()) {
                                @NonNull
                                @Override
                                public Fragment getItem(int position) {
                                    int index = position % listIllust.getList().size();
                                    return FragmentImage.newInstance(listIllust.getIllusts()
                                            .get(index));
                                }

                                @Override
                                public int getCount() {
                                    return Integer.MAX_VALUE;
                                }
                            });
                            baseBind.viewPager.setPageTransformer(true, new GalleryTransformer());
                            baseBind.viewPager.setOffscreenPageLimit(3);
                            baseBind.viewPager.setCurrentItem(listIllust.getList().size());
                        }
                    });
        }
    }

    @Override
    public void setUserVisibleHint(boolean isVisibleToUser) {
        super.setUserVisibleHint(isVisibleToUser);

        if (isVisibleToUser) {
            if (!isLoad) {
                FragmentTransaction transaction = getChildFragmentManager().beginTransaction();
                transaction.add(R.id.fragment_pivision, new FragmentPivisionHorizontal());
                transaction.commit();
                isLoad = true;
            }
        } else {
        }
    }
}
