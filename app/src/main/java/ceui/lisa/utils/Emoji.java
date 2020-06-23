package ceui.lisa.utils;

import android.text.TextUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import ceui.lisa.model.EmojiItem;

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


    /**
     * 判断一个字符串中是否包含形如 (sleep4) (heart) (star) 这样的表情
     *
     * @param origin 评论
     * @return boolean
     */
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

    /**
     * 将字符串中的表情全部替换为<img 巴拉巴拉>
     * @param origin
     * @return
     */
    public static String transform(String origin) {
        String before = origin;
        while (hasEmoji(before)) {
            int startIndex = before.indexOf("(");
            int endIndex = before.indexOf(")");
            if (startIndex >= 0 && endIndex >= startIndex) {
                //截取这个表情
                String emoji = before.substring(startIndex, endIndex + 1);

                //将表情替换为对应的<img>
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
        map.clear();
        map.put(EMOJI_1, HEAD +  "101.png" + OFF);
        map.put(EMOJI_2, HEAD +  "102.png" + OFF);
        map.put(EMOJI_3, HEAD +  "103.png" + OFF);
        map.put(EMOJI_4, HEAD +  "104.png" + OFF);
        map.put(EMOJI_5, HEAD +  "105.png" + OFF);
        map.put(EMOJI_6, HEAD +  "106.png" + OFF);
        map.put(EMOJI_7, HEAD +  "107.png" + OFF);
        map.put(EMOJI_8, HEAD +  "108.png" + OFF);
        map.put(EMOJI_9, HEAD +  "201.png" + OFF);
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

    private static final String[] RESOURCE = new String[]{
            "101.png", "102.png", "103.png", "104.png", "105.png", "106.png", "107.png",
            "108.png", "201.png", "202.png", "203.png", "204.png", "205.png", "206.png",
            "207.png", "208.png", "209.png", "301.png", "302.png", "303.png", "304.png",
            "305.png", "306.png", "307.png", "308.png", "309.png", "310.png", "401.png",
            "402.png", "403.png", "404.png", "405.png", "406.png", "407.png", "408.png",
            "501.png", "502.png", "503.png"
    };

    private static final String[] NAMES = new String[]{
            EMOJI_1, EMOJI_2, EMOJI_3, EMOJI_4, EMOJI_5, EMOJI_6,
            EMOJI_7, EMOJI_8, EMOJI_9, EMOJI_10, EMOJI_11, EMOJI_12,
            EMOJI_13, EMOJI_14, EMOJI_15, EMOJI_16, EMOJI_17, EMOJI_18,
            EMOJI_19, EMOJI_20, EMOJI_21, EMOJI_22, EMOJI_23, EMOJI_24,
            EMOJI_25, EMOJI_26, EMOJI_27, EMOJI_28, EMOJI_29, EMOJI_30,
            EMOJI_31, EMOJI_32, EMOJI_33, EMOJI_34, EMOJI_35, EMOJI_36,
            EMOJI_37, EMOJI_38
    };

    public static List<EmojiItem> getEmojis() {
        List<EmojiItem> result = new ArrayList<>();
        for (int i = 0; i < NAMES.length; i++) {
            result.add(new EmojiItem(NAMES[i], RESOURCE[i]));
        }
        return result;
    }
}
