package com.clara.orchestration.context;

import com.clara.dto.memory.TopicIsolationConfig;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 轻量话题边界判定：用显式词和字符 n-gram 相似度判断当前输入是否切到了新话题。
 *
 * <p>为什么不直接调用大模型：话题判定发生在每一轮请求装配前，额外一次模型调用会把首字延迟和成本
 * 都推高。这里使用确定性规则，既快又可解释；真正的语义理解仍交给主模型。
 *
 * <p>判定顺序：显式切换词 > 显式引用词 > 与最近用户输入的相似度 > 追问 / 指代词兜底。
 */
final class TopicBoundaryDetector {

    /** 最多比较最近多少条用户输入。 */
    private static final int RECENT_INPUTS = 5;

    /** 明确引用旧话题的说法。 */
    private static final String[] REFERENCE_MARKERS = {
        "继续刚才", "继续上面", "回到刚才", "回到上面", "刚才那个", "上面那个",
        "之前那个", "前面那个", "继续之前", "接着说", "继续聊刚才", "回到之前"
    };

    /** 可能延续当前话题的说法；单独出现时不足以判定为新话题。 */
    private static final String[] CONTINUATION_MARKERS = {
        "继续", "接着", "然后", "这个", "那个", "它", "还是", "同样", "再说说"
    };

    /** 明确切换话题的说法。 */
    private static final String[] SWITCH_MARKERS = {
        "换个话题", "换一个话题", "另一个问题", "另一个话题", "新问题", "重新开始",
        "不聊这个", "说点别的", "问个别的事", "另外问"
    };

    /**
     * 通用提问脚手架：这些词只表达「怎么问」，不承载话题内容。
     *
     * <p>不剥离它们时，「请帮我分析一下订单服务」和「请帮我分析一下支付服务」会因为公共前缀
     * 被算成高度相似，从而把旧话题错误地保留下来。
     */
    private static final String[] STOP_PHRASES = {
        "请帮我分析一下", "麻烦帮我分析一下", "帮我分析一下", "帮忙分析一下", "分析一下",
        "请帮我", "麻烦帮我", "帮我", "帮忙", "一下", "看看", "查一下", "查询", "查看",
        "为什么", "怎么", "如何", "什么", "哪些", "哪个", "这个", "那个", "使用情况",
        "被哪些", "有哪些", "引用过", "使用过"
    };

    private final double shiftThreshold;

    /**
     * 创建话题边界判定器。
     *
     * @param config 话题隔离配置，不可为 null
     */
    TopicBoundaryDetector(TopicIsolationConfig config) {
        this.shiftThreshold = config.getShiftThreshold();
    }

    /**
     * 判断当前输入与最近用户输入的关系。
     *
     * @param current             当前用户输入，可为 null
     * @param previousUserInputs  最近用户输入，可为 null
     * @return 话题关系，非 null
     */
    TopicRelation relation(String current, List<String> previousUserInputs) {
        String normalized = normalize(current);
        if (normalized.isEmpty()) {
            return TopicRelation.CONTINUE;
        }
        if (containsAny(current, SWITCH_MARKERS)) {
            return TopicRelation.SHIFT;
        }
        if (containsAny(current, REFERENCE_MARKERS)) {
            return TopicRelation.EXPLICIT_REFERENCE;
        }
        if (bestSimilarity(content(normalized), previousUserInputs) >= shiftThreshold) {
            return TopicRelation.CONTINUE;
        }
        if (containsAny(current, CONTINUATION_MARKERS) || normalized.length() <= 2) {
            return TopicRelation.AMBIGUOUS;
        }
        return TopicRelation.SHIFT;
    }

    /**
     * 计算两段文本的字符二元组 Dice 相似度。
     *
     * <p>Dice 比 Jaccard 更宽容：同一话题换一种说法时，长句公共片段少但仍应判为延续。
     *
     * @param left  左文本，可为 null
     * @param right 右文本，可为 null
     * @return [0, 1] 区间的相似度
     */
    double similarity(String left, String right) {
        String first = content(normalize(left));
        String second = content(normalize(right));
        if (first.isEmpty() || second.isEmpty()) {
            return 0.0d;
        }
        Set<String> firstGrams = grams(first);
        Set<String> secondGrams = grams(second);
        int intersection = 0;
        for (String gram : firstGrams) {
            if (secondGrams.contains(gram)) {
                intersection++;
            }
        }
        int total = firstGrams.size() + secondGrams.size();
        return total == 0 ? 0.0d : (2.0d * intersection) / total;
    }

    private double bestSimilarity(String current, List<String> previousUserInputs) {
        if (previousUserInputs == null || previousUserInputs.isEmpty()) {
            return 0.0d;
        }
        int from = Math.max(0, previousUserInputs.size() - RECENT_INPUTS);
        double best = 0.0d;
        for (int index = from; index < previousUserInputs.size(); index++) {
            best = Math.max(best, similarity(current, previousUserInputs.get(index)));
        }
        return best;
    }

    private static Set<String> grams(String normalized) {
        Set<String> grams = new HashSet<String>();
        if (normalized.isEmpty()) {
            return grams;
        }
        if (normalized.length() == 1) {
            grams.add(normalized);
            return grams;
        }
        for (int index = 0; index + 1 < normalized.length(); index++) {
            grams.add(normalized.substring(index, index + 2));
        }
        return grams;
    }

    private static String content(String normalized) {
        String content = normalized;
        for (String phrase : STOP_PHRASES) {
            content = content.replace(phrase, "");
        }
        return content;
    }

    private static String normalize(String text) {
        if (text == null) {
            return "";
        }
        String lower = text.toLowerCase(Locale.ROOT);
        StringBuilder normalized = new StringBuilder(lower.length());
        for (int index = 0; index < lower.length(); index++) {
            char value = lower.charAt(index);
            if (Character.isLetterOrDigit(value)) {
                normalized.append(value);
            }
        }
        return normalized.toString();
    }

    private static boolean containsAny(String text, String[] markers) {
        if (text == null) {
            return false;
        }
        for (String marker : markers) {
            if (text.contains(marker)) {
                return true;
            }
        }
        return false;
    }
}
