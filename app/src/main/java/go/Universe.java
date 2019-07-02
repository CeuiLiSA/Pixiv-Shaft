//package go;
//
//public abstract class Universe {
//    static  {
//        Seq.touch();
//        _init();
//    }
//
//    private static native void _init();
//
//    public static void touch() {}
//
//    private static final class proxyerror extends Exception implements Seq.Proxy, error {
//        private final int refnum;
//
//        proxyerror(int param1Int) {
//            this.refnum = param1Int;
//            Seq.trackGoRef(param1Int, this);
//        }
//
//        public native String error();
//
//        public String getMessage() { return error(); }
//
//        public final int incRefnum() {
//            Seq.incGoRef(this.refnum, this);
//            return this.refnum;
//        }
//    }
//}
