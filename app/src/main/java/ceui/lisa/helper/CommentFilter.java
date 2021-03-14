package ceui.lisa.helper;

import android.text.TextUtils;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import ceui.lisa.activities.Shaft;
import ceui.lisa.http.NullCtrl;
import ceui.lisa.http.Retro;
import ceui.lisa.models.CommentsBean;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.schedulers.Schedulers;
import okhttp3.ResponseBody;

public class CommentFilter {

    private static List<CommentFilterRule> rules = new ArrayList<>();

    static {
        updateRules();
    }

    public static boolean judge(CommentsBean commentsBean) {
        return rules.stream()
                .anyMatch(rule -> rule.judge(commentsBean.getComment()));
    }

    private static void updateRules() {
        updateRulesFromLocal();
        updateRulesFromRemote();
    }

    private static void updateRulesFromLocal() {
        try {
            InputStream inputStream = Shaft.getContext().getAssets().open("comment.filter.rule.txt");
            byte[] buffer = new byte[inputStream.available()];
            inputStream.read(buffer);
            inputStream.close();
            String content = new String(buffer);
            rules = Arrays.stream(content.split("\\r?\\n", -1))
                    .filter(string -> !TextUtils.isEmpty(string))
                    .map(CommentFilterRule::new)
                    .collect(Collectors.toList());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void updateRulesFromRemote() {

        Retro.getResourceApi().getCommentFilterRule()
                .subscribeOn(Schedulers.newThread())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(new NullCtrl<ResponseBody>() {
                    @Override
                    public void success(ResponseBody responseBody) {
                        try {
                            String content = responseBody.string();
                            if (TextUtils.isEmpty(content)) {
                                return;
                            }

                            rules = Arrays.stream(content.split("\\r?\\n", -1))
                                    .filter(string -> !TextUtils.isEmpty(string))
                                    .map(CommentFilterRule::new)
                                    .collect(Collectors.toList());
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                    }
                });
    }

    private static class CommentFilterRule {
        private static final int URL_DOMAIN = 0; // 根据域名过滤
        private static final int FULL_MATCH = 1; // 根据完整正则过滤

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
            if (ruleType == URL_DOMAIN) {
                String regexRuleValue = ruleValue.replace(".", "\\.");
                mPattern = Pattern.compile("https?://([0-9a-zA-Z]+\\.)*" + regexRuleValue);
            } else if (ruleType == FULL_MATCH) {
                mPattern = Pattern.compile(ruleValue);
            }
        }

        public boolean judge(String input) {
            if (mPattern == null) {
                return false;
            }
            if (ruleType == URL_DOMAIN) {
                return mPattern.matcher(input).find();
            } else if (ruleType == FULL_MATCH) {
                return mPattern.matcher(input).find();
            }
            return false;
        }
    }
}
