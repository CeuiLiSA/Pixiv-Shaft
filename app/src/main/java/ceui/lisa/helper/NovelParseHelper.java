package ceui.lisa.helper;

import android.text.TextUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import ceui.lisa.models.NovelDetail;

public class NovelParseHelper {

    private static final Pattern PAGE_PATTERN = Pattern.compile("\\[newpage\\]");
    private static final Pattern CHAPTER_PATTERN = Pattern.compile("\\[chapter:(.+)\\]");

    public static List<NovelDetail.NovelChapterBean> tryParseChapters(String content) {
        if (TextUtils.isEmpty(content)) {
            return null;
        }

        List<NovelDetail.NovelChapterBean> result = new ArrayList<>();

        List<String> pageContents = new ArrayList<>(Arrays.asList(PAGE_PATTERN.split(content)));
        for (int i = 0; i < pageContents.size(); i++) {
            String pageContent = pageContents.get(i);
            if (TextUtils.isEmpty(pageContent)) {
                continue;
            }
            NovelDetail.NovelChapterBean bean = new NovelDetail.NovelChapterBean();
            bean.setChapterIndex(result.size() + 1);

            Matcher matcher = CHAPTER_PATTERN.matcher(pageContent);
            if (matcher.find()) {
                bean.setChapterName(matcher.group(1));
                pageContent = pageContent.substring(0, matcher.start()).concat(pageContent.substring(matcher.end()));
            } else {
                // 或者加个String资源表示章节标题什么的
                bean.setChapterName(String.valueOf(bean.getChapterIndex()));
            }

            bean.setChapterContent(pageContent);
            result.add(bean);
        }

        return result;
    }
}
