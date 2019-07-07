//package Spray;
//
//import go.Seq;
//import java.util.Arrays;
//
//public final class DNSAnswer implements Seq.Proxy {
//    private final int refnum = __New();
//
//    static  {
//        Spray.touch();
//    }
//
//    public DNSAnswer() { Seq.trackGoRef(this.refnum, this); }
//
//    DNSAnswer(int paramInt) { Seq.trackGoRef(paramInt, this); }
//
//    private static native int __New();
//
//    public boolean equals(Object paramObject) {
//        if (paramObject != null) {
//            if (!(paramObject instanceof DNSAnswer))
//                return false;
//            DNSAnswer temp = (DNSAnswer)paramObject;
//            if (getType() != temp.getType())
//                return false;
//            if (getTTL() != temp.getTTL())
//                return false;
//            paramObject = getData();
//            String str = temp.getData();
//            if (paramObject == null) {
//                if (str != null)
//                    return false;
//            } else if (!paramObject.equals(str)) {
//                return false;
//            }
//            return true;
//        }
//        return false;
//    }
//
//    public final native String getData();
//
//    public final native long getTTL();
//
//    public final native long getType();
//
//    public int hashCode() { return Arrays.hashCode(new Object[] { Long.valueOf(getType()), Long.valueOf(getTTL()), getData() }); }
//
//    public final int incRefnum() {
//        Seq.incGoRef(this.refnum, this);
//        return this.refnum;
//    }
//
//    public final native void setData(String paramString);
//
//    public final native void setTTL(long paramLong);
//
//    public final native void setType(long paramLong);
//
//    public String toString() {
//        StringBuilder stringBuilder = new StringBuilder();
//        stringBuilder.append("DNSAnswer");
//        stringBuilder.append("{");
//        stringBuilder.append("Type:");
//        stringBuilder.append(getType());
//        stringBuilder.append(",");
//        stringBuilder.append("TTL:");
//        stringBuilder.append(getTTL());
//        stringBuilder.append(",");
//        stringBuilder.append("Data:");
//        stringBuilder.append(getData());
//        stringBuilder.append(",");
//        stringBuilder.append("}");
//        return stringBuilder.toString();
//    }
//}
