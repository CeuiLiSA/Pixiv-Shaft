package ceui.lisa.feature.worker;

public interface IExecutable {

    void onStart();

    void run(IEnd end);

    void onEnd();
}
