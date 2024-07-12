package ceui.lisa.utils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class CharDicWithArr {

    static List<String> dicList = new ArrayList<>(Arrays.asList(
            "normal", "surprise", "serious", "heaven", "happy", "excited", "sing", "cry", "normal2", "shame2",
            "love2", "interesting2", "blush2", "fire2", "angry2", "shine2", "panic2", "normal3", "satisfaction3",
            "surprise3", "smile3", "shock3", "gaze3", "wink3", "happy3", "excited3", "love3", "normal4",
            "surprise4", "serious4", "love4", "shine4", "sweat4", "shame4", "sleep4", "heart", "teardrop", "star"
            , "st你好ar"
    ));

    public static void main(String[] args) {

        字典树检测表情_测试一();
        字典树检测表情_测试二();

    }

    private static void 字典树检测表情_测试二() {
        System.out.println("================ 字典树检测表情_测试二 ");
        List<String> dicListFix = new ArrayList<>();
        for (String s : dicList) {
            dicListFix.add("(" + s + ")");
        }
        CharDicWithArr dic = new CharDicWithArr(dicListFix);

        System.out.println(dic.containsBy("465456456(fsdf)5645") + " , 预期 : false ");
        System.out.println(dic.containsBy("465456456(love4)9347457") + " , 预期 : true ");
        System.out.println(dic.containsBy("4654564{5}6(lov)9347{heart}457") + " , 预期 : false ");
        System.out.println(dic.containsBy("46545(lovvvv)64{5}6(love4)9347(heart)") + " , 预期 : true ");
        System.out.println(dic.containsBy("46545(lovvvv)64{5}6(love4)9347(heart)457") + " , 预期 : true ");
        System.out.println(dic.containsBy("") + " , 预期 : false ");
        System.out.println(dic.containsBy("(love4)") + " , 预期 : true ");
        System.out.println(dic.containsBy("(lov5756756757)") + " , 预期 : false ");
    }

    private static void 字典树检测表情_测试一() {
        System.out.println("================ 字典树检测表情_测试一 ");
        CharDicWithArr dic = new CharDicWithArr(dicList);

        System.out.println(dic.containsByWithPrefix("(", "(love4)") + " , 预期 : true ");
        System.out.println(dic.containsByWithPrefix("(", "465456456(fsdf)5645") + " , 预期 : false ");
        System.out.println(dic.containsByWithPrefix("(", "465456456(love4)9347457") + " , 预期 : true ");
        System.out.println(dic.containsByWithPrefix("{", "4654564{5}6(love4)9347{heart}457") + " , 预期 : true ");
        System.out.println(dic.containsByWithPrefix("{", "46545(lovvvv)64{5}6(love4)9347(heart)") + " , 预期 : false ");
        System.out.println(dic.containsByWithPrefix("(", "46545(lovvvv)64{5}6(love4)9347(heart)457") + " , 预期 : true ");
        System.out.println(dic.containsByWithPrefix("(", "") + " , 预期 : false ");
        System.out.println(dic.containsByWithPrefix("(", "(") + " , 预期 : false ");
        System.out.println(dic.containsByWithPrefix("(", "(love4)") + " , 预期 : true ");
        System.out.println(dic.containsByWithPrefix("(", "(lov5756756757)") + " , 预期 : false ");
    }

    private boolean containsByWithPrefix(String prefix, String s) {
        for (int left = -1, length = prefix.length(); (left = s.indexOf(prefix, left + 1) + length) >= length; ) {
            for (Node ro = root; left < s.length() && s.charAt(left) < arrLen && (ro = ro.sons[s.charAt(left++)]) != null; ) {
                if (ro.isEnd) {
                    return true;
                }
            }
        }
        return false;
    }

    static int arrLen = 128;

    public static class Node {
        public Node[] sons;
        /**
         * 是否是字母的末位
         */
        public boolean isEnd;
        public int length;

        public Node() {
            sons = new Node[arrLen];
            isEnd = false;
        }

    }

    public CharDicWithArr() {
        root = new Node();
    }

    public CharDicWithArr(List<String> list) {
        root = new Node();
        generateNodeByStringList(list);
    }

    public Node root;

    public void generateNodeByStringList(List<String> list) {
        for (String f : list) {
            Node ro = root;
            for (char c : f.toCharArray()) {
                if ((int) c >= arrLen) {
                    System.err.println(" 不是 ascii ");
                    break;
                }
                if (ro.sons[c] == null) {
                    ro.sons[c] = new Node();
                }
                ro = ro.sons[c];
            }
            ro.isEnd = true;
        }
    }

    public CharDicWithArr(String[] list) {
        root = new Node();
        generateNodeByStringList(list);
    }

    public void generateNodeByStringList(String[] list) {
        for (String f : list) {
            Node ro = root;
            for (char c : f.toCharArray()) {
                if ((int) c >= arrLen) {
                    System.err.println(" 不是 ascii ");
                    break;
                }
                if (ro.sons[c] == null) {
                    ro.sons[c] = new Node();
                }
                ro = ro.sons[c];
            }
            ro.isEnd = true;
        }
    }

    public boolean containsBy(String s) {
        for (int i = 0, left = 0; i < s.length(); i++, left = i) {
            for (Node ro = root; left < s.length() && s.charAt(left) < arrLen && (ro = ro.sons[s.charAt(left++)]) != null; ) {
                if (ro.isEnd) {
                    return true;
                }
            }
        }
        return false;
    }

    public int[] containsByReturnRange(CharSequence s) {
        for (int i = 0, left = 0; i < s.length(); i++, left = i) {
            for (Node ro = root; left < s.length() && s.charAt(left) < arrLen && (ro = ro.sons[s.charAt(left++)]) != null; ) {
                if (ro.isEnd) {
                    return new int[]{i, left};
                }
            }
        }
        return null;
    }

}
