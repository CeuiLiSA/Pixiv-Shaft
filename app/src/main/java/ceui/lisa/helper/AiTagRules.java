package ceui.lisa.helper;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 「用 AI 标签辅助识别 AI 作品」的规则表与匹配（issue #1252 需求 1）。
 *
 * <p>官方 {@code ai_type == 2} 仍是唯一权威标记，但有些作者只打 AI 相关标签、不打官方标识，
 * 「屏蔽 AI」就会漏掉它们。这里把「标签命中」做成 AI 判定的**第二个来源**：判定入口统一收在
 * {@link IllustNovelFilter#isAiWork(ceui.pixiv.api.model.Illust)} /
 * {@link IllustNovelFilter#isAiWork(ceui.loxia.Novel)}，命中标签与官方标记完全同等——参与
 * 屏蔽、仅看 AI、打码，不另立一套口径。
 *
 * <p>规则分两类，两类都能逐条停用：
 * <ul>
 *   <li>内置：[#BUILTIN_TAGS] 编译期固定，只能停用、不能删除；</li>
 *   <li>自定义：用户自己加的标签，可停用也可删除。</li>
 * </ul>
 * 停用集合（{@code aiTagDisabledTags}）两类共用一份：保存时已保证自定义标签不与内置标签撞名，
 * 所以同一个集合里不会出现「同一个词既是内置又自定义」的歧义。
 *
 * <p>匹配口径是**精确等值**（忽略大小写与首尾空白），不做子串匹配：标签是作者手写的短词，
 * 子串命中会把「AI生成作品集」以外的正常标签也吞进来，误伤的代价比漏判高得多。
 *
 * <p>纯静态、不读设置，方便裸 JVM 单测——读 {@code Shaft.sSettings} 的便捷版在
 * {@link IllustNovelFilter} 里。见 AiTagRulesTest。
 */
public final class AiTagRules {

    /**
     * 内置标签清单（取自 issue #1252，顺序即设置 sheet 里的展示顺序）。
     *
     * <p>清单只增不改：往里加条目对老用户是「默认启用」的新规则（不需要迁移），删条目则会让
     * 老用户的停用集合里留下永远匹配不到的词——无害，但没必要。
     */
    private static final String[] BUILTIN_TAGS = {
            "ai生成",
            "ai生成作品",
            "ai作画",
            "aiイラスト",
            "aigenerated",
            "ai-generated",
            "ai_generated",
            "aiassisted",
            "ai-assisted",
            "ai_assisted",
            "ai辅助",
            "aiアシスト",
            "aiアシスタンス",
            "ai+ps",
            "aiart",
            "aiartwork",
            "aigirl",
            "ai作品",
            "ai生成イラスト",
            "ai画像",
            "ai绘画",
            "novelai",
            "novelaidiffusion",
            "stablediffusion",
    };

    private static final List<String> BUILTIN_LIST =
            Collections.unmodifiableList(Arrays.asList(BUILTIN_TAGS));

    /** 规范化后的内置标签集合，逐条匹配时 O(1) 查表。 */
    private static final Set<String> BUILTIN_KEYS;

    static {
        Set<String> keys = new HashSet<>();
        for (String tag : BUILTIN_TAGS) {
            keys.add(normalize(tag));
        }
        BUILTIN_KEYS = Collections.unmodifiableSet(keys);
    }

    private AiTagRules() {
    }

    /** 内置标签（不可变，按展示顺序）。 */
    public static List<String> builtinTags() {
        return BUILTIN_LIST;
    }

    /**
     * 比较用的规范形式：去掉首尾空白 + 转小写。
     *
     * <p>用 {@link Locale#ROOT} 而不是默认 Locale：土耳其语等语言里 {@code "I".toLowerCase()}
     * 会变成无点的 {@code ı}，那会让「AI」这类标签在部分系统语言下突然匹配不上。
     */
    public static String normalize(String tag) {
        if (tag == null) {
            return "";
        }
        return tag.trim().toLowerCase(Locale.ROOT);
    }

    /** 该标签是不是内置标签（忽略大小写与首尾空白）。 */
    public static boolean isBuiltinTag(String tag) {
        return BUILTIN_KEYS.contains(normalize(tag));
    }

    /**
     * 单条标签是否命中 AI 规则。
     *
     * @param disabled 已停用的标签（内置 / 自定义混装，比较时各自规范化）
     * @param userTags 用户自定义标签
     */
    public static boolean isAiTag(String tagName, Set<String> disabled, Set<String> userTags) {
        String key = normalize(tagName);
        if (key.isEmpty()) {
            return false;
        }
        if (BUILTIN_KEYS.contains(key)) {
            return !containsNormalized(disabled, key);
        }
        return containsNormalized(userTags, key) && !containsNormalized(disabled, key);
    }

    /** 一组标签里只要有一条命中就算 AI 作品；空列表 / null 一律不算。 */
    public static boolean hasAiTag(List<String> tagNames, Set<String> disabled, Set<String> userTags) {
        if (tagNames == null || tagNames.isEmpty()) {
            return false;
        }
        for (String name : tagNames) {
            if (isAiTag(name, disabled, userTags)) {
                return true;
            }
        }
        return false;
    }

    /** 集合里是否已有这个标签（忽略大小写与首尾空白），用于保存时的去重。 */
    public static boolean containsTag(Iterable<String> tags, String tag) {
        return containsNormalized(tags, normalize(tag));
    }

    /** 当前仍启用的内置标签数量（停用集合里可能有已下线的历史词，不能直接用总数相减）。 */
    public static int enabledBuiltinCount(Set<String> disabled) {
        return enabledCount(BUILTIN_LIST, disabled);
    }

    /** 一组标签里当前「启用」的条数：不在停用集合里的都算。 */
    public static int enabledCount(Iterable<String> tags, Set<String> disabled) {
        if (tags == null) {
            return 0;
        }
        int count = 0;
        for (String tag : tags) {
            if (!containsNormalized(disabled, normalize(tag))) {
                count++;
            }
        }
        return count;
    }

    /** 规则集合里的有效条目（去掉 null / 空白），按传入顺序返回，供界面展示。 */
    public static List<String> sanitized(Iterable<String> tags) {
        List<String> out = new ArrayList<>();
        if (tags == null) {
            return out;
        }
        for (String tag : tags) {
            if (tag == null) {
                continue;
            }
            String trimmed = tag.trim();
            if (!trimmed.isEmpty()) {
                out.add(trimmed);
            }
        }
        return out;
    }

    private static boolean containsNormalized(Iterable<String> tags, String key) {
        if (tags == null || key.isEmpty()) {
            return false;
        }
        for (String tag : tags) {
            if (key.equals(normalize(tag))) {
                return true;
            }
        }
        return false;
    }
}
