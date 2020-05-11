package ceui.lisa.utils;

import android.text.TextUtils;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

public class Emoji {

    private static final String EMOJI_1  = "(normal)";
    private static final String EMOJI_2  = "(surprise)";
    private static final String EMOJI_3  = "(serious)";
    private static final String EMOJI_4  = "(heaven)";
    private static final String EMOJI_5  = "(happy)";
    private static final String EMOJI_6  = "(excited)";
    private static final String EMOJI_7  = "(sing)";
    private static final String EMOJI_8  = "(cry)";
    private static final String EMOJI_9  = "(normal2)";
    private static final String EMOJI_10 = "(shame2)";
    private static final String EMOJI_11 = "(love2)";
    private static final String EMOJI_12 = "(interesting2)";
    private static final String EMOJI_13 = "(blush2)";
    private static final String EMOJI_14 = "(fire2)";
    private static final String EMOJI_15 = "(angry2)";
    private static final String EMOJI_16 = "(shine2)";
    private static final String EMOJI_17 = "(panic2)";
    private static final String EMOJI_18 = "(normal3)";
    private static final String EMOJI_19 = "(satisfaction3)";
    private static final String EMOJI_20 = "(surprise3)";
    private static final String EMOJI_21 = "(smile3)";
    private static final String EMOJI_22 = "(shock3)";
    private static final String EMOJI_23 = "(gaze3)";
    private static final String EMOJI_24 = "(wink3)";
    private static final String EMOJI_25 = "(happy3)";
    private static final String EMOJI_26 = "(excited3)";
    private static final String EMOJI_27 = "(love3)";
    private static final String EMOJI_28 = "(normal4)";
    private static final String EMOJI_29 = "(surprise4)";
    private static final String EMOJI_30 = "(serious4)";
    private static final String EMOJI_31 = "(love4)";
    private static final String EMOJI_32 = "(shine4)";
    private static final String EMOJI_33 = "(sweat4)";
    private static final String EMOJI_34 = "(shame4)";
    private static final String EMOJI_35 = "(sleep4)";
    private static final String EMOJI_36 = "(heart)";
    private static final String EMOJI_37 = "(teardrop)";
    private static final String EMOJI_38 = "(star)";


    public static boolean hasEmoji(String origin) {
        if (TextUtils.isEmpty(origin)) {
            return false;
        }
        return origin.contains("(") &&
                origin.contains(")") &&
                Character.isLowerCase(
                        origin.charAt(
                                origin.indexOf("(") + 1 // （右边的必须是小写字母。
                        )
                );
    }

    public static String transform(String origin) {
        String before = origin;
        while (hasEmoji(before)) {
            int startIndex = before.indexOf("(");
            int endIndex = before.indexOf(")");
            if (startIndex >= 0 && endIndex >= startIndex) {
                String emoji = before.substring(startIndex, endIndex + 1);

                if (!TextUtils.isEmpty(emoji)) {
                    before = replace(before, emoji);
                }
            }
        }
        return before;
    }

    public static String replace(String origin, String emoji) {
        String after = map.get(emoji);
        if (!TextUtils.isEmpty(after)) {
            return origin.replace(emoji, after);
        }
        return origin.replace(emoji, "");
    }

    private static Map<String, String> map = new HashMap<>();
    private static final String HEAD = "<img class=\"_2sgsdWB\" width=\"24\" height=\"24\" src=\"";
    private static final String OFF = "\">";

    //        map.put(EMOJI_1 , HEAD + "https://s.pximg.net/common/images/emoji/101.png" + OFF);
    static {
        map.put(EMOJI_1 , HEAD + "101.png" + OFF);
        map.put(EMOJI_2 , HEAD + "102.png" + OFF);
        map.put(EMOJI_3 , HEAD + "103.png" + OFF);
        map.put(EMOJI_4 , HEAD + "104.png" + OFF);
        map.put(EMOJI_5 , HEAD + "105.png" + OFF);
        map.put(EMOJI_6 , HEAD + "106.png" + OFF);
        map.put(EMOJI_7 , HEAD + "107.png" + OFF);
        map.put(EMOJI_8 , HEAD + "108.png" + OFF);
        map.put(EMOJI_9 , HEAD + "201.png" + OFF);
        map.put(EMOJI_10, HEAD + "202.png" + OFF);
        map.put(EMOJI_11, HEAD + "203.png" + OFF);
        map.put(EMOJI_12, HEAD + "204.png" + OFF);
        map.put(EMOJI_13, HEAD + "205.png" + OFF);
        map.put(EMOJI_14, HEAD + "206.png" + OFF);
        map.put(EMOJI_15, HEAD + "207.png" + OFF);
        map.put(EMOJI_16, HEAD + "208.png" + OFF);
        map.put(EMOJI_17, HEAD + "209.png" + OFF);
        map.put(EMOJI_18, HEAD + "301.png" + OFF);
        map.put(EMOJI_19, HEAD + "302.png" + OFF);
        map.put(EMOJI_20, HEAD + "303.png" + OFF);
        map.put(EMOJI_21, HEAD + "304.png" + OFF);
        map.put(EMOJI_22, HEAD + "305.png" + OFF);
        map.put(EMOJI_23, HEAD + "306.png" + OFF);
        map.put(EMOJI_24, HEAD + "307.png" + OFF);
        map.put(EMOJI_25, HEAD + "308.png" + OFF);
        map.put(EMOJI_26, HEAD + "309.png" + OFF);
        map.put(EMOJI_27, HEAD + "310.png" + OFF);
        map.put(EMOJI_28, HEAD + "401.png" + OFF);
        map.put(EMOJI_29, HEAD + "402.png" + OFF);
        map.put(EMOJI_30, HEAD + "403.png" + OFF);
        map.put(EMOJI_31, HEAD + "404.png" + OFF);
        map.put(EMOJI_32, HEAD + "405.png" + OFF);
        map.put(EMOJI_33, HEAD + "406.png" + OFF);
        map.put(EMOJI_34, HEAD + "407.png" + OFF);
        map.put(EMOJI_35, HEAD + "408.png" + OFF);
        map.put(EMOJI_36, HEAD + "501.png" + OFF);
        map.put(EMOJI_37, HEAD + "502.png" + OFF);
        map.put(EMOJI_38, HEAD + "503.png" + OFF);
    }
}
