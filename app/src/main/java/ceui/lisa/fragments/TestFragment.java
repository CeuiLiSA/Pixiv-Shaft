package ceui.lisa.fragments;

import android.os.Bundle;

import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;

import ceui.lisa.R;
import ceui.lisa.adapters.ColorAdapter;
import ceui.lisa.databinding.FragmentTestBinding;
import ceui.lisa.model.ColorItem;
import ceui.lisa.utils.Common;
import ceui.lisa.viewmodel.VPModel;

public class TestFragment extends BaseFragment<FragmentTestBinding>{

    private int index;
    private VPModel mVPModel;
    private ColorAdapter mColorAdapter;

    public static TestFragment newInstance(int index) {
        Bundle args = new Bundle();
        args.putInt("index", index);
        TestFragment fragment = new TestFragment();
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    protected void initView() {
        mColorAdapter = new ColorAdapter(mVPModel.getRightList(index), mContext);
        baseBind.recyList.setLayoutManager(new LinearLayoutManager(mContext));
        baseBind.recyList.setAdapter(mColorAdapter);
    }

    @Override
    public void initModel() {
        mVPModel = new ViewModelProvider(mActivity).get(VPModel.class);
    }

    @Override
    protected void initData() {
        super.initData();
        Common.showLog("trace getRightList add " + mVPModel.getRightList(index).size());
        if (mVPModel.getRightList(index).size() == 0) {
            mVPModel.getRightList(index).add(new ColorItem(0, "矢尹紫", "#686bdd", false));
            mVPModel.getRightList(index).add(new ColorItem(1, "经典蓝", "#56baec", false));
            mVPModel.getRightList(index).add(new ColorItem(2, "官方蓝", "#008BF3", false));
            mVPModel.getRightList(index).add(new ColorItem(3, "浅葱绿", "#03d0bf", false));
            mVPModel.getRightList(index).add(new ColorItem(4, "盛夏黄", "#fee65e", false));
            mVPModel.getRightList(index).add(new ColorItem(5, "樱桃粉", "#fe83a2", false));
            mVPModel.getRightList(index).add(new ColorItem(6, "元气红", "#f44336", false));
            mVPModel.getRightList(index).add(new ColorItem(7, "基佬紫", "#673AB7", false));
            mVPModel.getRightList(index).add(new ColorItem(8, "老实绿", "#4CAF50", false));
            mVPModel.getRightList(index).add(new ColorItem(9, "少女粉", "#E91E63", false));
            mVPModel.getRightList(index).add(new ColorItem(0, "矢尹紫", "#686bdd", false));
            mVPModel.getRightList(index).add(new ColorItem(1, "经典蓝", "#56baec", false));
            mVPModel.getRightList(index).add(new ColorItem(2, "官方蓝", "#008BF3", false));
            mVPModel.getRightList(index).add(new ColorItem(3, "浅葱绿", "#03d0bf", false));
            mVPModel.getRightList(index).add(new ColorItem(4, "盛夏黄", "#fee65e", false));
            mVPModel.getRightList(index).add(new ColorItem(5, "樱桃粉", "#fe83a2", false));
            mVPModel.getRightList(index).add(new ColorItem(6, "元气红", "#f44336", false));
            mVPModel.getRightList(index).add(new ColorItem(7, "基佬紫", "#673AB7", false));
            mVPModel.getRightList(index).add(new ColorItem(8, "老实绿", "#4CAF50", false));
            mVPModel.getRightList(index).add(new ColorItem(9, "少女粉", "#E91E63", false));
            mColorAdapter.notifyDataSetChanged();
        }
    }

    @Override
    protected void initBundle(Bundle bundle) {
        index = bundle.getInt("index");
    }

    @Override
    protected void initLayout() {
        mLayoutID = R.layout.fragment_test;
    }
}
