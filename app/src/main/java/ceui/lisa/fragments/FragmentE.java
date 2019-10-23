package ceui.lisa.fragments;

import android.view.View;

import org.greenrobot.eventbus.EventBus;

import ceui.lisa.R;
import ceui.lisa.databinding.FragmentEBinding;
import ceui.lisa.utils.Channel;
import ceui.lisa.utils.Common;

public class FragmentE extends BaseBindFragment<FragmentEBinding> {

    @Override
    void initLayout() {
        mLayoutID = R.layout.fragment_e;
    }

    @Override
    void initData() {
        baseBind.click.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Channel channel = new Channel();
                channel.setReceiver("FragmentE");
                channel.setObject(" hahhahahahha");
                EventBus.getDefault().post(channel);
            }
        });
    }

    @Override
    public boolean eventBusEnable() {
        return true;
    }

    @Override
    public void handleEvent(Channel channel) {
        Common.showToast(className + channel.getObject());
    }
}
