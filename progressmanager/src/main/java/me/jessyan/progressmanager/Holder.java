package me.jessyan.progressmanager;

import android.util.Log;

import java.util.HashMap;

public class Holder {

    private Holder() {
    }

    private static class SingleTonHolder {
        private static final Holder INSTANCE = new Holder();
    }

    public static Holder get() {
        return SingleTonHolder.INSTANCE;
    }
}
