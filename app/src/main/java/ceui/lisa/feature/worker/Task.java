package ceui.lisa.feature.worker;

public class Task extends AbstractTask{

    @Override
    public void run(IEnd end) {
        try {
            System.out.println(name + " 开始工作 ");
            Thread.sleep(1000L);
            end.next();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
