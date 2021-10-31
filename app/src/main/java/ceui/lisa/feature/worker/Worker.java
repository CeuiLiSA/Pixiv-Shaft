package ceui.lisa.feature.worker;

import android.os.Handler;

import java.util.ArrayList;

import ceui.lisa.interfaces.FeedBack;

public class Worker {

    private ArrayList<AbstractTask> runningTask = new ArrayList<>();
    //private final Thread workThread = new Thread(this::execute);
    private static final Handler handler = new Handler();
    private FeedBack mFeedBack;
    private FeedBack mFinalFeedBack;

    private Worker() {
    }

    private static Worker worker;

    public static Worker get() {
        if (worker == null) {
            worker = new Worker();
        }
        return worker;
    }

    public ArrayList<AbstractTask> getRunningTask() {
        return runningTask;
    }

    public void setRunningTask(ArrayList<AbstractTask> runningTask) {
        this.runningTask = runningTask;
    }

    public void addTask(AbstractTask task) {
        if (runningTask == null) {
            runningTask = new ArrayList<>();
        }
        System.out.println("添加任务 " + task.getName());
        runningTask.add(task);
    }

    public void removeTask(AbstractTask task) {
        if (runningTask == null) {
            runningTask = new ArrayList<>();
        } else {
            runningTask.remove(task);
        }
    }

    public void removeTask(int index) {
        if (runningTask == null) {
            runningTask = new ArrayList<>();
        } else {
            if (index < runningTask.size()) {
                runningTask.remove(index);
            }
        }
    }

    public void start() {
        if (!isRunning) {
            isRunning = true;
            new Thread(this::execute).start();
        }
    }

    private boolean isRunning = false;

    private void execute() {
        if (runningTask == null) {
            runningTask = new ArrayList<>();
            isRunning = false;
            System.out.println("已完成");
            return;
        }

        if (runningTask.size() == 0) {
            isRunning = false;
            System.out.println("已完成");
            return;
        }

        final AbstractTask current = runningTask.get(0);
        current.process(new IEnd() {
            @Override
            public void next() {
                removeTask(current);
                if (mFeedBack != null) {
                    mFeedBack.doSomething();
                }
                if(runningTask == null || runningTask.size() == 0){
                    if(mFinalFeedBack != null){
                        mFinalFeedBack.doSomething();
                        mFinalFeedBack = null;
                    }
                }
                execute();
            }
        });
    }

    public static Handler getHandler() {
        return handler;
    }

    public FeedBack getFeedBack() {
        return mFeedBack;
    }

    public void setFeedBack(FeedBack feedBack) {
        mFeedBack = feedBack;
    }

    public FeedBack getFinalFeedBack() {
        return mFinalFeedBack;
    }

    public void setFinalFeedBack(FeedBack mFinalFeedBack) {
        this.mFinalFeedBack = mFinalFeedBack;
    }
}
