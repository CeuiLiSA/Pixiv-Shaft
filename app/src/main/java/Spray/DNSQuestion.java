//package Spray;
//
//import go.Seq;
//import java.util.Arrays;
//
//public final class DNSQuestion implements Seq.Proxy {
//    private final int refnum = __New();
//
//    static  {
//        Spray.touch();
//    }
//
//    public DNSQuestion() { Seq.trackGoRef(this.refnum, this); }
//
//    DNSQuestion(int paramInt) { Seq.trackGoRef(paramInt, this); }
//
//    private static native int __New();
//
//    public boolean equals(Object paramObject) {
//        if (paramObject != null) {
//            if (!(paramObject instanceof DNSQuestion))
//                return false;
//            DNSQuestion temp = (DNSQuestion)paramObject;
//            String str1 = getName();
//            String str2 = temp.getName();
//            if (str1 == null) {
//                if (str2 != null)
//                    return false;
//            } else if (!str1.equals(str2)) {
//                return false;
//            }
//            return getType() == temp.getType();
//        }
//        return false;
//    }
//
//    public final native String getName();
//
//    public final native long getType();
//
//    public int hashCode() { return Arrays.hashCode(new Object[] { getName(), getType()}); }
//
//    public final int incRefnum() {
//        Seq.incGoRef(this.refnum, this);
//        return this.refnum;
//    }
//
//    public final native void setName(String paramString);
//
//    public final native void setType(long paramLong);
//
//    public String toString() {
//        StringBuilder stringBuilder = new StringBuilder();
//        stringBuilder.append("DNSQuestion");
//        stringBuilder.append("{");
//        stringBuilder.append("Name:");
//        stringBuilder.append(getName());
//        stringBuilder.append(",");
//        stringBuilder.append("Type:");
//        stringBuilder.append(getType());
//        stringBuilder.append(",");
//        stringBuilder.append("}");
//        return stringBuilder.toString();
//    }
//}
