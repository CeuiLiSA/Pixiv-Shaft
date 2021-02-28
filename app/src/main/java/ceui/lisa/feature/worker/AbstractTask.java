package ceui.lisa.feature.worker;

import ceui.lisa.utils.Common;

public abstract class AbstractTask implements IExecutable{

    protected String name;
    protected long delay = 2000L;
    protected boolean shouldDelay = true;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public final void process(IEnd end) {
        Common.showLog("正在处理：" + name);
        onStart();
        run(new IEnd() {
            @Override
            public void next() {
                onEnd();
                if (shouldDelay) {
                    Worker.getHandler().postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            end.next();
                        }
                    }, delay);
                } else {
                    end.next();
                }
            }
        });
    }

    @Override
    public void onStart() {
        Common.showLog("开始执行 " + name);
    }

    @Override
    public void onEnd() {
        Common.showLog("执行结束 " + name);
    }

    public long getDelay() {
        return delay;
    }

    public void setDelay(long delay) {
        this.delay = delay;
    }

    public boolean isShouldDelay() {
        return shouldDelay;
    }

    public AbstractTask setShouldDelay(boolean shouldDelay) {
        this.shouldDelay = shouldDelay;
        return this;
    }
}
