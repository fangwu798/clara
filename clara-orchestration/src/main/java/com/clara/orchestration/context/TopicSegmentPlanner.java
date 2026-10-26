package com.clara.orchestration.context;

import com.clara.core.message.IContent;
import com.clara.dto.memory.TopicIsolationConfig;
import com.clara.enums.RoleEnum;
import com.clara.kernel.message.delta.UserContent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/**
 * 话题段规划器：把历史按话题切段，并选出本轮真正需要携带的消息。
 *
 * <p>分段规则：第一条用户消息开启第一段；后续用户消息与最近用户输入相似度足够高时并入当前段，
 * 否则开启新段。明确切换、话题不明确、显式引用旧话题都会开启新段，但选择范围不同：
 * <ul>
 *   <li>延续：携带当前段全部消息；</li>
 *   <li>切换：只携带当前用户消息及其后的工具往返；</li>
 *   <li>不明确：额外携带上一段最近 {@code carryOverPreviousTurns} 轮；</li>
 *   <li>显式引用：额外携带向前查找命中的旧话题段。</li>
 * </ul>
 *
 * <p>执行期用户补充（以 {@code [用户实时补充]} 开头）不视为新话题，始终并入当前段。
 */
final class TopicSegmentPlanner {

    /** 执行期用户补充的固定前缀，与编排器写入历史的文本保持一致。 */
    private static final String INTERVENTION_PREFIX = "[用户实时补充]";

    /** 比较话题时最多回看多少条用户输入。 */
    private static final int RECENT_INPUTS = 5;

    private final TopicIsolationConfig config;
    private final TopicBoundaryDetector detector;

    /**
     * 创建话题段规划器。
     *
     * @param config 话题隔离配置，可为 null（使用默认配置）
     */
    TopicSegmentPlanner(TopicIsolationConfig config) {
        this.config = config == null ? TopicIsolationConfig.defaults() : config;
        this.detector = new TopicBoundaryDetector(this.config);
    }

    /**
     * 规划本轮可携带的历史消息。
     *
     * @param history      完整历史，可为 null
     * @param currentInput 当前用户输入，可为 null
     * @return 话题选择结果，非 null
     */
    TopicSelection plan(List<IContent> history, String currentInput) {
        if (history == null || history.isEmpty()) {
            return new TopicSelection(Collections.<IContent>emptySet(), TopicRelation.CONTINUE, "无历史");
        }
        if (!config.isEnabled()) {
            return selectAll(history, "话题隔离未启用");
        }
        List<Integer> userIndices = new ArrayList<Integer>();
        List<String> userTexts = new ArrayList<String>();
        for (int index = 0; index < history.size(); index++) {
            IContent message = history.get(index);
            if (message != null && message.getRoleType() == RoleEnum.USER
                    && message instanceof UserContent) {
                userIndices.add(index);
                userTexts.add(((UserContent) message).getContent());
            }
        }
        if (userIndices.isEmpty()) {
            return selectAll(history, "无用户消息");
        }
        int turnCount = userIndices.size();
        int[] segmentOfTurn = new int[turnCount];
        TopicRelation[] relations = new TopicRelation[turnCount];
        segmentOfTurn[0] = 0;
        relations[0] = TopicRelation.CONTINUE;
        for (int turn = 1; turn < turnCount; turn++) {
            TopicRelation relation = isIntervention(userTexts.get(turn))
                    ? TopicRelation.CONTINUE
                    : detector.relation(userTexts.get(turn), recent(userTexts, turn));
            relations[turn] = relation;
            segmentOfTurn[turn] = relation == TopicRelation.CONTINUE
                    ? segmentOfTurn[turn - 1] : segmentOfTurn[turn - 1] + 1;
        }
        int currentTurn = turnCount - 1;
        int currentSegment = segmentOfTurn[currentTurn];
        TopicRelation currentRelation = relations[currentTurn];
        Set<IContent> selected = identitySet();
        includeRange(history, selected, userIndices.get(currentTurn), history.size());
        includeRelatedTopic(history, selected, userIndices, userTexts, segmentOfTurn,
                currentSegment, currentRelation, currentInput);
        return new TopicSelection(selected, currentRelation,
                reason(currentRelation, config.getCarryOverPreviousTurns()));
    }

    private void includeRelatedTopic(List<IContent> history, Set<IContent> selected,
            List<Integer> userIndices, List<String> userTexts, int[] segmentOfTurn,
            int currentSegment, TopicRelation relation, String currentInput) {
        if (relation == TopicRelation.CONTINUE) {
            int firstTurn = firstTurnOfSegment(segmentOfTurn, currentSegment);
            includeRange(history, selected, userIndices.get(firstTurn), history.size());
            return;
        }
        if (relation == TopicRelation.AMBIGUOUS) {
            includeTailTurns(history, selected, userIndices, segmentOfTurn,
                    currentSegment - 1, config.getCarryOverPreviousTurns());
            return;
        }
        if (relation == TopicRelation.EXPLICIT_REFERENCE) {
            int referenced = chooseReferencedSegment(userTexts, segmentOfTurn, currentSegment, currentInput);
            if (referenced >= 0) {
                includeSegment(history, selected, userIndices, segmentOfTurn, referenced);
            }
        }
    }

    private static void includeTailTurns(List<IContent> history, Set<IContent> selected,
            List<Integer> userIndices, int[] segmentOfTurn, int segment, int turns) {
        if (segment < 0 || turns <= 0) {
            return;
        }
        int lastTurn = lastTurnOfSegment(segmentOfTurn, segment);
        if (lastTurn < 0) {
            return;
        }
        int firstTurn = firstTurnOfSegment(segmentOfTurn, segment);
        int startTurn = Math.max(firstTurn, lastTurn - turns + 1);
        int end = segmentEnd(history.size(), userIndices, segmentOfTurn, segment);
        includeRange(history, selected, userIndices.get(startTurn), end);
    }

    private static void includeSegment(List<IContent> history, Set<IContent> selected,
            List<Integer> userIndices, int[] segmentOfTurn, int segment) {
        if (segment < 0) {
            return;
        }
        int firstTurn = firstTurnOfSegment(segmentOfTurn, segment);
        if (firstTurn < 0) {
            return;
        }
        int end = segmentEnd(history.size(), userIndices, segmentOfTurn, segment);
        includeRange(history, selected, userIndices.get(firstTurn), end);
    }

    private int chooseReferencedSegment(List<String> userTexts, int[] segmentOfTurn,
            int currentSegment, String currentInput) {
        int candidates = Math.max(1, config.getExplicitReferenceWindow());
        int from = Math.max(0, currentSegment - candidates);
        int bestSegment = -1;
        double bestScore = -1.0d;
        for (int segment = from; segment < currentSegment; segment++) {
            double score = detector.similarity(currentInput, segmentText(userTexts, segmentOfTurn, segment));
            if (score > bestScore) {
                bestScore = score;
                bestSegment = segment;
            }
        }
        if (bestSegment < 0) {
            return -1;
        }
        return bestScore > 0.0d ? bestSegment : currentSegment - 1;
    }

    private static String segmentText(List<String> userTexts, int[] segmentOfTurn, int segment) {
        StringBuilder text = new StringBuilder();
        for (int turn = 0; turn < segmentOfTurn.length; turn++) {
            if (segmentOfTurn[turn] != segment) {
                continue;
            }
            String value = userTexts.get(turn);
            if (value == null || value.trim().isEmpty()) {
                continue;
            }
            if (text.length() > 0) {
                text.append(' ');
            }
            text.append(value);
        }
        return text.toString();
    }

    private static int segmentEnd(int historySize, List<Integer> userIndices,
            int[] segmentOfTurn, int segment) {
        int nextSegmentFirstTurn = firstTurnOfSegment(segmentOfTurn, segment + 1);
        return nextSegmentFirstTurn < 0 ? historySize : userIndices.get(nextSegmentFirstTurn);
    }

    private static int firstTurnOfSegment(int[] segmentOfTurn, int segment) {
        for (int turn = 0; turn < segmentOfTurn.length; turn++) {
            if (segmentOfTurn[turn] == segment) {
                return turn;
            }
        }
        return -1;
    }

    private static int lastTurnOfSegment(int[] segmentOfTurn, int segment) {
        for (int turn = segmentOfTurn.length - 1; turn >= 0; turn--) {
            if (segmentOfTurn[turn] == segment) {
                return turn;
            }
        }
        return -1;
    }

    private static void includeRange(List<IContent> history, Set<IContent> selected,
            int start, int end) {
        int safeStart = Math.max(0, start);
        int safeEnd = Math.min(history.size(), end);
        for (int index = safeStart; index < safeEnd; index++) {
            IContent message = history.get(index);
            if (message != null && message.getRoleType() != RoleEnum.SYSTEM) {
                selected.add(message);
            }
        }
    }

    private static List<String> recent(List<String> userTexts, int turn) {
        int from = Math.max(0, turn - RECENT_INPUTS);
        return new ArrayList<String>(userTexts.subList(from, turn));
    }

    private static boolean isIntervention(String text) {
        return text != null && text.startsWith(INTERVENTION_PREFIX);
    }

    private static Set<IContent> identitySet() {
        return Collections.newSetFromMap(new IdentityHashMap<IContent, Boolean>());
    }

    private static TopicSelection selectAll(List<IContent> history, String reason) {
        Set<IContent> selected = identitySet();
        for (IContent message : history) {
            if (message != null && message.getRoleType() != RoleEnum.SYSTEM) {
                selected.add(message);
            }
        }
        return new TopicSelection(selected, TopicRelation.CONTINUE, reason);
    }

    private static String reason(TopicRelation relation, int carryOverTurns) {
        if (relation == TopicRelation.SHIFT) {
            return "检测到话题切换";
        }
        if (relation == TopicRelation.AMBIGUOUS) {
            return "话题不明确，保留上一话题最近 " + carryOverTurns + " 轮";
        }
        if (relation == TopicRelation.EXPLICIT_REFERENCE) {
            return "用户显式引用旧话题";
        }
        return "延续当前话题";
    }
}
