package ceui.lisa.service;

public class Random {

    public static int randomInt(int from, int to){

        // int randNumber = rand.nextInt(MAX - MIN + 1) + MIN;
        java.util.Random random = new java.util.Random();
        return random.nextInt(to - from + 1) + from;
    }
}
