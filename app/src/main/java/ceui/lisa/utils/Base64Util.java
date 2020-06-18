package ceui.lisa.utils;

import android.text.TextUtils;
import android.util.Base64;

import java.nio.charset.StandardCharsets;

public class Base64Util {

    public static String encode(String oldWord){
        if (TextUtils.isEmpty(oldWord)) {
            return "";
        }
        return Base64.encodeToString(oldWord.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
    }

    public static String decode(String encodeWord){
        if (TextUtils.isEmpty(encodeWord)) {
            return "";
        }
        return new String(Base64.decode(encodeWord, Base64.NO_WRAP), StandardCharsets.UTF_8);
    }
}
