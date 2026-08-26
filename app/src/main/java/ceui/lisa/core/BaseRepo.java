package ceui.lisa.core;

import ceui.lisa.utils.Common;
import ceui.pixiv.session.SessionManager;

public class BaseRepo {

    public BaseRepo() {
        Common.showLog("BaseRepo " + getClass().getSimpleName() + " newInstance");
    }

    public Long currentUserID() {
        return SessionManager.INSTANCE.getLoggedInUid();
    }
}
