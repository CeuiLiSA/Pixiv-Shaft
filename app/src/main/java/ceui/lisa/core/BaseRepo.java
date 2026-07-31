package ceui.lisa.core;

import ceui.lisa.utils.Common;
import ceui.pixiv.session.SessionManager;

public class BaseRepo {

    public BaseRepo() {
        Common.showLog("BaseRepo " + getClass().getSimpleName() + " newInstance");
    }

    public int currentUserID() {
        return (int) SessionManager.INSTANCE.getLoggedInUid();
    }
}
