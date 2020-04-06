package ceui.lisa.adapters;

import android.content.Context;
import android.content.Intent;
import android.view.View;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.PagerSnapHelper;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

import ceui.lisa.activities.RankActivity;
import ceui.lisa.activities.ViewPagerActivity;
import ceui.lisa.adapters.RAdapter;
import ceui.lisa.adapters.ViewHolder;
import ceui.lisa.databinding.RecyRecmdHeaderBinding;
import ceui.lisa.models.IllustsBean;
import ceui.lisa.utils.DataChannel;
import ceui.lisa.utils.DensityUtil;
import ceui.lisa.view.LinearItemHorizontalDecoration;

public class RecmdHeader extends ViewHolder<RecyRecmdHeaderBinding> {

    public RecmdHeader(@NonNull View itemView) {
        super(itemView);
    }

    public void show(Context context, List<IllustsBean> illustsBeans) {
        baseBind.topRela.setVisibility(View.VISIBLE);
        Animation animation = new AlphaAnimation(0.0f, 1.0f);
        animation.setDuration(800L);
        baseBind.topRela.startAnimation(animation);
        RAdapter adapter = new RAdapter(illustsBeans, context);
        adapter.setOnItemClickListener((v, position, viewType) -> {
            DataChannel.get().setIllustList(illustsBeans);
            Intent intent = new Intent(context, ViewPagerActivity.class);
            intent.putExtra("position", position);
            context.startActivity(intent);
        });
        baseBind.ranking.setAdapter(adapter);
    }

    public void initView(Context context) {
        baseBind.topRela.setVisibility(View.GONE);
        baseBind.seeMore.setOnClickListener(v -> {
            Intent intent = new Intent(context, RankActivity.class);
            intent.putExtra("dataType", "插画");
            context.startActivity(intent);
        });
        baseBind.ranking.addItemDecoration(new LinearItemHorizontalDecoration(DensityUtil.dp2px(8.0f)));
        LinearLayoutManager manager = new LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false);
        baseBind.ranking.setLayoutManager(manager);
        baseBind.ranking.setHasFixedSize(true);
    }
}
