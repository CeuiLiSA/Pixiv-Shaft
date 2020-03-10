package ceui.lisa;

public class Main {

    public static void main(String[] args) {
        System.out.println("hello world");

        FileLineCounter counter = new FileLineCounter("/Users/ceuilisa/Desktop/code/Android/Pixiv-Shaft/app/src/main/java/ceui/lisa");
        counter.run();
    }
}
