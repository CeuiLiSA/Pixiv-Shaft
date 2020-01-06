package ceui.lisa.gif.core;

import ceui.lisa.models.IllustsBean;

public interface GifOperate {

    int getState();

    void downloadZip();

    void unzip();

    void start();

    void saveGif();

    void play(IllustsBean pIllustsBean);
}
