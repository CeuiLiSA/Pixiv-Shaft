package go;

import android.app.Application;

import java.util.logging.Logger;

public class LoadJNI {
    private static Logger log = Logger.getLogger("GoLoadJNI");

    public static final Object ctx;

    static {
        System.loadLibrary("gojni");

        Object androidCtx = null;
        try {
            // TODO(hyangah): check proguard rule.
            //Application appl = (Application)Class.forName("android.app.AppGlobals").getMethod("getInitialApplication").invoke(null);
            //androidCtx = appl.getApplicationContext();
        } catch (Exception e) {
            log.warning("Global context not found: " + e);
        } finally {
            ctx = androidCtx;
        }
    }
}
