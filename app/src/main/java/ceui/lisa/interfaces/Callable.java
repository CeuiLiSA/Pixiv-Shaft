package ceui.lisa.interfaces;

import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import ceui.lisa.utils.Channel;

/**
 * 支持远程唤醒
 *
 * @param <Target>
 */
public interface Callable<Target> {

    @Subscribe(threadMode = ThreadMode.MAIN)
    void handleEvent(Channel<Target> channel);
}
