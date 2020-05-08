package ceui.lisa.adapters;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.databinding.DataBindingUtil;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.StaggeredGridLayoutManager;

import java.util.ArrayList;
import java.util.List;

import ceui.lisa.R;
import ceui.lisa.http.NullCtrl;
import ceui.lisa.http.Retro;
import ceui.lisa.model.ListIllust;
import ceui.lisa.models.IllustsBean;
import ceui.lisa.models.NovelBean;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.schedulers.Schedulers;

import static ceui.lisa.activities.Shaft.sUserModel;

public class NAdapterWithHeadView extends NAdapter {

    private NovelHeader novelHeader = null;

    public NAdapterWithHeadView(List<NovelBean> targetList, Context context) {
        super(targetList, context);
    }

    @Override
    public int headerSize() {
        return 1;
    }

    @Override
    public ViewHolder getHeader(ViewGroup parent) {
        novelHeader = new NovelHeader(
                DataBindingUtil.inflate(
                        LayoutInflater.from(mContext),
                        R.layout.recy_recmd_header,
                        null,
                        false
                )
        );
        novelHeader.initView(mContext);
        return novelHeader;
    }

    public void setHeadData(List<NovelBean> novelBeans) {
        if (novelHeader != null) {
            novelHeader.show(mContext, novelBeans);
        }
    }
}
