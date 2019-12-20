package ceui.lisa.test;

import java.io.File;

import ceui.lisa.interfaces.Callback;

public interface SendToRemote {

    void send(File file, Callback<File> callback);

}
