package ceui.lisa.gif;

import ceui.lisa.gif.core.GifOperate;
import ceui.lisa.models.IllustsBean;

public class GifManager implements GifOperate {

    @Override
    public int getState() {
        return 0;
    }

    @Override
    public void downloadZip() {

    }

    @Override
    public void unzip() {

    }

    @Override
    public void start() {

    }

    @Override
    public void saveGif() {

    }

    @Override
    public void play(IllustsBean pIllustsBean) {

    }

    private GifManager() {
    }

    private static class Holder {
        private static GifManager INSTANCE = new GifManager();
    }

    public static GifManager get() {
        return Holder.INSTANCE;
    }
}
