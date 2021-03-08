package ceui.lisa.helper;

import android.text.TextUtils;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import ceui.lisa.activities.Shaft;
import ceui.lisa.models.CommentsBean;

public class CommentFilter {

    private static final List<CommentFilterRule> rules = getRules();

    public static boolean judge(CommentsBean commentsBean) {
        return rules.stream()
                .anyMatch(rule -> rule.judge(commentsBean.getComment()));
    }

    public static List<CommentFilterRule> getRules() {
        List<CommentFilterRule> rules = new ArrayList<>();
        try {
            InputStream inputStream = Shaft.getContext().getAssets().open("comment.filter.rule.txt");
            byte[] buffer = new byte[inputStream.available()];
            inputStream.read(buffer);
            inputStream.close();
            String content = new String(buffer);
            return Arrays.stream(content.split("\\r?\\n", -1))
                    .filter(string -> !TextUtils.isEmpty(string))
                    .map(CommentFilterRule::new)
                    .collect(Collectors.toList());
        } catch (Exception e) {
            e.printStackTrace();
        }
        return rules;
    }

    private static class CommentFilterRule {
        private static final int URL = 0; // 根据域名过滤

        private int ruleType;
        private String ruleValue;
        private Pattern mPattern;

        public CommentFilterRule(int ruleType, String ruleValue) {
            this.ruleType = ruleType;
            this.ruleValue = ruleValue;
            init();
        }

        public CommentFilterRule(String ruleType, String ruleValue) {
            this.ruleType = Integer.parseInt(ruleType);
            this.ruleValue = ruleValue;
            init();
        }

        public CommentFilterRule(String ruleLine) {
            int separatorIndex = ruleLine.indexOf(",");
            this.ruleType = Integer.parseInt(ruleLine.substring(0, separatorIndex));
            this.ruleValue = ruleLine.substring(separatorIndex + 1);
            init();
        }

        private void init() {
            if (ruleType == URL) {
                String regexRuleValue = ruleValue.replace(".", "\\.");
                mPattern = Pattern.compile("https?://([0-9a-zA-Z]+\\.)*" + regexRuleValue);
            }
        }

        public boolean judge(String input) {
            if (ruleType == URL) {
                return mPattern.matcher(input).find();
            }
            return false;
        }
    }
}
