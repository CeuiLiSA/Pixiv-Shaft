package ceui.lisa.fragments;

import ceui.lisa.utils.Common;

public class Refresh {

    private String mTag;

    public Refresh(String tag) {
        mTag = tag;
    }

    public String nowFresh() {
        return "开始刷新 " + mTag;
    }
}
