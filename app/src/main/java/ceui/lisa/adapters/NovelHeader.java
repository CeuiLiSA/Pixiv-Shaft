package ceui.lisa.adapters;

import android.content.Context;
import android.content.Intent;
import android.view.View;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;

import java.util.List;

import ceui.lisa.activities.RankActivity;
import ceui.lisa.activities.TemplateActivity;
import ceui.lisa.activities.ViewPagerActivity;
import ceui.lisa.databinding.RecyRecmdHeaderBinding;
import ceui.lisa.models.IllustsBean;
import ceui.lisa.models.NovelBean;
import ceui.lisa.utils.DataChannel;
import ceui.lisa.utils.DensityUtil;
import ceui.lisa.utils.Params;
import ceui.lisa.view.LinearItemHorizontalDecoration;

public class NovelHeader extends ViewHolder<RecyRecmdHeaderBinding> {

    public NovelHeader(RecyRecmdHeaderBinding bindView) {
        super(bindView);
    }

    public void show(Context context, List<NovelBean> illustsBeans) {
        baseBind.topRela.setVisibility(View.VISIBLE);
        Animation animation = new AlphaAnimation(0.0f, 1.0f);
        animation.setDuration(800L);
        baseBind.topRela.startAnimation(animation);
        NHAdapter adapter = new NHAdapter(illustsBeans, context);
        adapter.setOnItemClickListener((v, position, viewType) -> {
            Intent intent = new Intent(context, TemplateActivity.class);
            intent.putExtra(Params.CONTENT, illustsBeans.get(position));
            intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "小说详情");
            intent.putExtra("hideStatusBar", true);
            context.startActivity(intent);
        });
        baseBind.ranking.setAdapter(adapter);
    }

    public void initView(Context context) {
        baseBind.topRela.setVisibility(View.GONE);
        baseBind.seeMore.setOnClickListener(v -> {
            Intent intent = new Intent(context, RankActivity.class);
            intent.putExtra("dataType", "小说");
            context.startActivity(intent);
        });
        baseBind.ranking.addItemDecoration(new LinearItemHorizontalDecoration(DensityUtil.dp2px(8.0f)));
        LinearLayoutManager manager = new LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false);
        baseBind.ranking.setLayoutManager(manager);
        baseBind.ranking.setHasFixedSize(true);
    }
}
