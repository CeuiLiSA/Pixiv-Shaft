package ceui.lisa.fragments;

import android.graphics.Color;
import android.os.Bundle;

import ceui.lisa.R;
import ceui.lisa.core.BindFragment;
import ceui.lisa.databinding.FragmentColorBinding;

public class FragmentColor extends BindFragment<FragmentColorBinding> {

    private String color;

    public static FragmentColor newInstance(String color) {
        Bundle args = new Bundle();
        args.putString("color", color);
        FragmentColor fragment = new FragmentColor();
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void initBundle(Bundle bundle) {
        color = bundle.getString("color");
    }

    @Override
    public void getLayout() {
        mLayoutID = R.layout.fragment_color;
    }

    @Override
    public void initData() {
        bind.image.setBackgroundColor(Color.parseColor(color));
        bind.colorText.setText(color);
        bind.colorText.setVerticalMode(true);
    }
}
