package ceui.lisa.models;

import java.io.Serializable;

public class FramesBean implements Serializable {
    /**
     * file : 000000.jpg
     * delay : 120
     */

    private String file;
    private int delay;

    public String getFile() {
        return file;
    }

    public void setFile(String file) {
        this.file = file;
    }

    public int getDelay() {
        return delay;
    }

    public void setDelay(int delay) {
        this.delay = delay;
    }

    @Override
    public String toString() {
        return "FramesBean{" +
                "file='" + file + '\'' +
                ", delay=" + delay +
                '}';
    }
}