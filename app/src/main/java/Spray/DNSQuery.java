//package Spray;
//
//import go.Seq;
//import java.util.Arrays;
//
//public final class DNSQuery extends Seq.Proxy {
//    private final int refnum = __New();
//
//    static  {
//        Spray.touch();
//    }
//
//    public DNSQuery() { Seq.trackGoRef(this.refnum, this); }
//
//    DNSQuery(int paramInt) { Seq.trackGoRef(paramInt, this); }
//
//    private static native int __New();
//
//    public native DNSQueryResponse do_() throws Exception;
//
//    public boolean equals(Object paramObject) {
//        if (paramObject == null || !(paramObject instanceof DNSQuery))
//            return false;
//        paramObject = (DNSQuery)paramObject;
//        return true;
//    }
//
//    public int hashCode() { return Arrays.hashCode(new Object[0]); }
//
//    public final int incRefnum() {
//        Seq.incGoRef(this.refnum, this);
//        return this.refnum;
//    }
//
//    public String toString() {
//        StringBuilder stringBuilder = new StringBuilder();
//        stringBuilder.append("DNSQuery");
//        stringBuilder.append("{");
//        stringBuilder.append("}");
//        return stringBuilder.toString();
//    }
//}
