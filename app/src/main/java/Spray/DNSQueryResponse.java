//package Spray;
//
//import go.Seq;
//import java.util.Arrays;
//
//public final class DNSQueryResponse implements Seq.Proxy {
//    private final int refnum = __New();
//
//    static  {
//        Spray.touch();
//    }
//
//    public DNSQueryResponse() { Seq.trackGoRef(this.refnum, this); }
//
//    DNSQueryResponse(int paramInt) { Seq.trackGoRef(paramInt, this); }
//
//    private static native int __New();
//
//    public boolean equals(Object paramObject) {
//        if (paramObject != null) {
//            if (!(paramObject instanceof DNSQueryResponse))
//                return false;
//            DNSQueryResponse temp = (DNSQueryResponse)paramObject;
//            return (getStatus() != temp.getStatus()) ? false : ((getTruncated() != temp.getTruncated()) ? false : ((getRecursiveDesired() != temp.getRecursiveDesired()) ? false : ((getRecursiveAvailable() != temp.getRecursiveAvailable()) ? false : ((getDNSSECVerified() != temp.getDNSSECVerified()) ? false : (!(getDNSSECVerifyDisabled() != temp.getDNSSECVerifyDisabled()))))));
//        }
//        return false;
//    }
//
//    public final native boolean getDNSSECVerified();
//
//    public final native boolean getDNSSECVerifyDisabled();
//
//    public final native boolean getRecursiveAvailable();
//
//    public final native boolean getRecursiveDesired();
//
//    public final native long getStatus();
//
//    public final native boolean getTruncated();
//
//    public int hashCode() { return Arrays.hashCode(new Object[] { Long.valueOf(getStatus()), Boolean.valueOf(getTruncated()), Boolean.valueOf(getRecursiveDesired()), Boolean.valueOf(getRecursiveAvailable()), Boolean.valueOf(getDNSSECVerified()), Boolean.valueOf(getDNSSECVerifyDisabled()) }); }
//
//    public final int incRefnum() {
//        Seq.incGoRef(this.refnum, this);
//        return this.refnum;
//    }
//
//    public final native void setDNSSECVerified(boolean paramBoolean);
//
//    public final native void setDNSSECVerifyDisabled(boolean paramBoolean);
//
//    public final native void setRecursiveAvailable(boolean paramBoolean);
//
//    public final native void setRecursiveDesired(boolean paramBoolean);
//
//    public final native void setStatus(long paramLong);
//
//    public final native void setTruncated(boolean paramBoolean);
//
//    public String toString() {
//        StringBuilder stringBuilder = new StringBuilder();
//        stringBuilder.append("DNSQueryResponse");
//        stringBuilder.append("{");
//        stringBuilder.append("Status:");
//        stringBuilder.append(getStatus());
//        stringBuilder.append(",");
//        stringBuilder.append("Truncated:");
//        stringBuilder.append(getTruncated());
//        stringBuilder.append(",");
//        stringBuilder.append("RecursiveDesired:");
//        stringBuilder.append(getRecursiveDesired());
//        stringBuilder.append(",");
//        stringBuilder.append("RecursiveAvailable:");
//        stringBuilder.append(getRecursiveAvailable());
//        stringBuilder.append(",");
//        stringBuilder.append("DNSSECVerified:");
//        stringBuilder.append(getDNSSECVerified());
//        stringBuilder.append(",");
//        stringBuilder.append("DNSSECVerifyDisabled:");
//        stringBuilder.append(getDNSSECVerifyDisabled());
//        stringBuilder.append(",");
//        stringBuilder.append("}");
//        return stringBuilder.toString();
//    }
//}
