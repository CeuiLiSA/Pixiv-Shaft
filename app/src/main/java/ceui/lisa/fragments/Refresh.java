package ceui.lisa.fragments;

import ceui.lisa.utils.Common;

public class Refresh {

    private String mTag;

    public Refresh(String tag) {
        mTag = tag;
    }

    void nowFresh() {
        Common.showToast("开始刷新 " + mTag);
    }
}
